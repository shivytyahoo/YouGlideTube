package io.github.shivytyahoo.glidetube.player.sabr.integration

import android.util.Log
import io.github.shivytyahoo.glidetube.innertube.models.YouTubeClient
import io.github.shivytyahoo.glidetube.player.sabr.core.SabrEvent
import io.github.shivytyahoo.glidetube.player.sabr.core.SabrStreamController
import io.github.shivytyahoo.glidetube.player.sabr.proto.FormatInitializationMetadata
import io.github.shivytyahoo.glidetube.player.stream.ClientGateTracker
import io.github.shivytyahoo.glidetube.utils.potoken.WebPoTokenSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

class SabrOrchestrator(
    private val controller: SabrStreamController,
    private val reloadResolver: (suspend (SabrEvent.ReloadRequired) -> SabrStreamInfo?)? = null,
) {
    companion object {
        private const val TAG = "SabrOrchestrator"

        // Three failures in a row usually means the cached session has gone stale — drop it
        // for a fresh player response instead of retrying against the same answer.
        private const val DROP_SESSION_AFTER_ERRORS = 3
        private const val MAX_FOLLOW_UP_ERRORS = 6

        // Ninety seconds with no new bytes while the buffer is starved is a stalled session,
        // not a slow one: end it so the fallback can take over instead of spinning forever.
        private const val STALL_TIMEOUT_MS = 90_000L
        private const val POLL_INTERVAL_MS = 250L
        private const val DEFAULT_MAX_REQUEST_GAP_MS = 8_000L
        private const val MIN_TARGET_READAHEAD_MS = 5_000L
        private const val URL_EXPIRY_MARGIN_MS = 60_000L
        private const val MAX_PLAYER_RESPONSE_RELOADS = 2
        private const val MAX_MIS_START_RESTARTS = 3
    }

    val audioBuffer = SabrSegmentBuffer()
    val videoBuffer = SabrSegmentBuffer()

    private var scope: CoroutineScope? = null
    private var eventCollectorJob: Job? = null
    private var segmentFetchJob: Job? = null

    @Volatile
    private var poTokenRefreshJob: Deferred<PoTokenRefreshResult>? = null

    @Volatile
    private var poTokenRefreshUrgent = false

    @Volatile
    private var playerResponseReloadJob: Deferred<Boolean>? = null
    private var playerResponseReloads = 0
    private var consecutiveErrors = 0

    // The last time a segment or init block arrived; the stall timeout is measured from here.
    @Volatile
    private var lastProgressMs = 0L

    // A mis-start restart the controller asked for, run by the follow-up loop between requests.
    @Volatile
    private var pendingRestartFromMs: Long? = null
    private var sessionRestarts = 0

    // Whether the byte allow-gate is holding the fetch back; cleared once the lead drains.
    private val byteAllowGate = SabrByteAllowGate()

    @Volatile
    var isRunning = false
        private set

    @Volatile
    var audioInitReceived = false
        private set

    @Volatile
    var videoInitReceived = false
        private set

    var onFormatInitialized: ((FormatInitializationMetadata) -> Unit)? = null
    var onError: ((Int, String, Boolean) -> Unit)? = null
    var onEndOfTrack: (() -> Unit)? = null

    fun start() {
        if (isRunning) return
        isRunning = true
        consecutiveErrors = 0
        sessionRestarts = 0
        pendingRestartFromMs = null
        byteAllowGate.reset()
        lastProgressMs = System.currentTimeMillis()
        audioInitReceived = false
        videoInitReceived = false
        audioBuffer.reset()
        videoBuffer.reset()

        val newScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope = newScope

        eventCollectorJob =
            newScope.launch {
                controller.events.collect { event ->
                    handleEvent(event)
                }
            }

        newScope.launch(Dispatchers.IO) {
            controller.startSession()
            startFollowUpLoop()
        }
    }

    fun stop() {
        isRunning = false
        eventCollectorJob?.cancel()
        segmentFetchJob?.cancel()
        poTokenRefreshJob?.cancel()
        playerResponseReloadJob?.cancel()
        controller.abort()
        audioBuffer.signalEndOfStream()
        videoBuffer.signalEndOfStream()
        scope?.cancel()
        scope = null
    }

    fun release() {
        stop()
        controller.release()
        audioBuffer.close()
        videoBuffer.close()
    }

    fun updatePlayhead(positionMs: Long) {
        controller.updatePlayheadPosition(positionMs)
    }

    /** Background audio-only mode: tells the server to stop sending video segments. */
    fun setAudioOnly(enabled: Boolean) {
        controller.sessionState.enabledTrackTypes = if (enabled) 1 else -1
    }

    private fun handleEvent(event: SabrEvent) {
        when (event) {
            is SabrEvent.FormatInitialized -> {
                val metadata = event.metadata
                val initData = metadata.initData
                lastProgressMs = System.currentTimeMillis()
                if (initData.isNotEmpty()) {
                    if (metadata.isAudio) {
                        audioBuffer.appendSegment(initData)
                        audioInitReceived = true
                        Log.d(TAG, "Audio init received: ${metadata.mimeType} ${metadata.codecs}, ${initData.size}B")
                    } else if (metadata.isVideo) {
                        videoBuffer.appendSegment(initData)
                        videoInitReceived = true
                        Log.d(
                            TAG,
                            "Video init received: ${metadata.mimeType} ${metadata.codecs}, ${metadata.width}x${metadata.height}, ${initData.size}B",
                        )
                    }
                }
                onFormatInitialized?.invoke(metadata)
            }

            is SabrEvent.SegmentReady -> {
                val segment = event.segment
                consecutiveErrors = 0
                lastProgressMs = System.currentTimeMillis()
                if (segment.isAudio) {
                    audioBuffer.appendSegment(segment.data)
                } else {
                    videoBuffer.appendSegment(segment.data)
                }
            }

            is SabrEvent.EndOfTrack -> {
                Log.d(TAG, "End of track")
                audioBuffer.signalEndOfStream()
                videoBuffer.signalEndOfStream()
                onEndOfTrack?.invoke()
            }

            is SabrEvent.Error -> {
                Log.e(TAG, "SABR error: code=${event.code}, msg=${event.message}, recoverable=${event.recoverable}")
                noteConsecutiveError()
                if (!event.recoverable || consecutiveErrors >= MAX_FOLLOW_UP_ERRORS) {
                    onError?.invoke(event.code, event.message, false)
                } else {
                    onError?.invoke(event.code, event.message, true)
                }
            }

            is SabrEvent.Redirect -> {
                Log.d(TAG, "Redirected to new URL")
            }

            is SabrEvent.BackoffRequired -> {
                Log.d(TAG, "Backoff: ${event.delayMs}ms")
            }

            is SabrEvent.ReloadRequired -> {
                Log.w(TAG, "Reload required: ${event.reason}")
                startPlayerResponseReload(event)
            }

            is SabrEvent.RestartRequired -> {
                if (sessionRestarts >= MAX_MIS_START_RESTARTS) {
                    Log.w(TAG, "Mis-start restart budget spent — playing on from ${event.restartFromMs}ms")
                } else {
                    sessionRestarts++
                    pendingRestartFromMs = event.restartFromMs
                    Log.w(TAG, "Mis-start restart $sessionRestarts/$MAX_MIS_START_RESTARTS: ${event.reason}")
                }
            }

            is SabrEvent.SeekDirective -> {
                Log.d(TAG, "Server seek directive: ${event.targetMs}ms")
            }

            is SabrEvent.AttestationNeeded -> {
                if (event.required) {
                    refreshPoToken(urgent = true)
                } else {
                    // A pending verdict alongside media is normal, so this must not be treated as a
                    // fault on its own. Recovering from a verdict that never resolves requires
                    // rotating the visitor identity (a re-minted token keeps the same visitorData and
                    // draws the same verdict), which is not implemented yet.
                    Log.d(TAG, "PoToken attestation is pending; waiting for the server verdict")
                }
            }
        }
    }

    private fun refreshPoToken(urgent: Boolean) {
        val existing = poTokenRefreshJob
        if (existing != null && existing.isActive) {
            // An attestation-required refresh must not be satisfied by a non-urgent mint
            // already in flight — that one can return the same cached (rejected) token.
            if (!urgent || poTokenRefreshUrgent) return
            existing.cancel()
            poTokenRefreshJob = null
        }
        poTokenRefreshUrgent = urgent
        poTokenRefreshJob =
            scope?.async(Dispatchers.IO) {
                val videoId = controller.sessionState.videoId
                val fresh =
                    try {
                        val visitorData = controller.sessionState.visitorId
                        if (visitorData.isBlank()) {
                            null
                        } else if (urgent) {
                            WebPoTokenSession.refreshForVisitorData(videoId, visitorData)
                        } else {
                            WebPoTokenSession.mintForVisitorData(videoId, visitorData)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "PoToken refresh threw: ${e.message}")
                        null
                    }
                // videoId-bound content token, same binding the session was created with
                val streamingToken = fresh?.playerRequestPoToken
                if (!streamingToken.isNullOrEmpty()) {
                    controller.sessionState.poToken = streamingToken
                    Log.w(TAG, "PoToken refreshed for $videoId (urgent=$urgent)")
                    PoTokenRefreshResult(success = true, required = urgent)
                } else if (urgent) {
                    onError?.invoke(-5, "PoToken refresh failed while attestation required", false)
                    PoTokenRefreshResult(success = false, required = true)
                } else {
                    PoTokenRefreshResult(success = false, required = false)
                }
            }
    }

    /**
     * One place for the failure budgets, so an error event and a failed follow-up request
     * count the same way: at 3 the cached session is dropped for a fresh answer, at 6 the
     * engine is cooled off and the session ends.
     */
    private fun noteConsecutiveError() {
        consecutiveErrors++
        if (consecutiveErrors == DROP_SESSION_AFTER_ERRORS) dropCachedSession()
        if (consecutiveErrors >= MAX_FOLLOW_UP_ERRORS) coolSabrClients()
    }

    private fun dropCachedSession() {
        startPlayerResponseReload(
            SabrEvent.ReloadRequired("$DROP_SESSION_AFTER_ERRORS consecutive SABR errors — dropping cached session"),
        )
    }

    private fun coolSabrClients() {
        ClientGateTracker.reportGated(YouTubeClient.MWEB.clientName)
        ClientGateTracker.reportGated(YouTubeClient.WEB.clientName)
        Log.w(TAG, "SABR cooled off after $MAX_FOLLOW_UP_ERRORS consecutive errors")
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun startPlayerResponseReload(event: SabrEvent.ReloadRequired) {
        if (playerResponseReloadJob?.isActive == true) return
        val resolver = reloadResolver
        if (resolver == null || playerResponseReloads >= MAX_PLAYER_RESPONSE_RELOADS) {
            io.github.shivytyahoo.glidetube.player.error.PlayerDiagnostics.logError(
                TAG,
                "SABR reload budget spent ($playerResponseReloads/$MAX_PLAYER_RESPONSE_RELOADS) — giving up",
            )
            isRunning = false
            audioBuffer.signalEndOfStream()
            videoBuffer.signalEndOfStream()
            onError?.invoke(-2, event.reason, false)
            return
        }

        playerResponseReloads++
        playerResponseReloadJob =
            scope?.async(Dispatchers.IO) {
                val fresh =
                    try {
                        resolver(event)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Player response reload failed", e)
                        null
                    } ?: run {
                        io.github.shivytyahoo.glidetube.player.error.PlayerDiagnostics
                            .logError(TAG, "SABR reload could not resolve a fresh player response")
                        return@async false
                    }

                controller.sessionState.applyPlayerResponseReload(
                    streamingUrl = fresh.streamingUrl,
                    ustreamerConfig = fresh.ustreamerConfig,
                    poToken = fresh.poToken,
                    visitorId = fresh.visitorId,
                    cpn = fresh.cpn,
                    audioItag = fresh.audioItag,
                    audioLmt = fresh.audioLmt,
                    videoItag = fresh.videoItag,
                    videoLmt = fresh.videoLmt,
                    audioTrackId = fresh.audioTrackId,
                    audioXtags = fresh.audioXtags,
                    videoXtags = fresh.videoXtags,
                )
                Log.w(
                    TAG,
                    "Reload formats: audio=${fresh.audioItag}/${fresh.audioLmt} " +
                        "video=${fresh.videoItag}/${fresh.videoLmt} track='${fresh.audioTrackId}' " +
                        "audioXtags='${fresh.audioXtags}'",
                )
                // Clear the controller's wedge counter/reload latch now that the fresh session is
                // installed, so medialess responses seen during the reload window don't immediately
                // re-trip the non-recoverable wedge on the resumed session.
                controller.onPlayerResponseReloaded()
                Log.w(
                    TAG,
                    "Player response reloaded in place at ${controller.sessionState.playheadPositionMs}ms " +
                        "(attempt $playerResponseReloads/$MAX_PLAYER_RESPONSE_RELOADS)",
                )
                io.github.shivytyahoo.glidetube.player.error.PlayerDiagnostics.logWarning(
                    TAG,
                    "SABR reload applied at ${controller.sessionState.playheadPositionMs}ms " +
                        "(attempt $playerResponseReloads/$MAX_PLAYER_RESPONSE_RELOADS) — resuming session",
                )
                true
            }
    }

    private suspend fun startFollowUpLoop() {
        var lastRequestAtMs = System.currentTimeMillis()
        while (isRunning && consecutiveErrors < MAX_FOLLOW_UP_ERRORS) {
            delay(POLL_INTERVAL_MS)
            if (!isRunning) break

            val reloadJob = playerResponseReloadJob
            if (reloadJob != null) {
                val reloaded =
                    try {
                        reloadJob.await()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Player response reload wait failed", e)
                        false
                    }
                if (playerResponseReloadJob === reloadJob) playerResponseReloadJob = null
                if (!reloaded) {
                    isRunning = false
                    audioBuffer.signalEndOfStream()
                    videoBuffer.signalEndOfStream()
                    onError?.invoke(-2, "Unable to reload SABR player response", false)
                    break
                }
                continue
            }

            // A mis-start restart runs between requests: the pipes are reset and the session
            // re-issued from the earlier time, so the player never sits on the gap.
            val restartFromMs = pendingRestartFromMs
            if (restartFromMs != null) {
                pendingRestartFromMs = null
                restartSessionAt(restartFromMs)
                lastRequestAtMs = System.currentTimeMillis()
                continue
            }

            // Pace requests by the server's readahead targets instead of hammering:
            // request only when the buffered lead over the playhead drops below target,
            // with a periodic heartbeat so the server can send policy/END_OF_TRACK parts.
            val state = controller.sessionState
            val now = System.currentTimeMillis()

            // GVS URLs die at their expire= timestamp (~6h); re-extract before mid-play 403s
            val expiresAtMs = state.urlExpiresAtMs()
            if (expiresAtMs > 0 && now >= expiresAtMs - URL_EXPIRY_MARGIN_MS) {
                Log.w(TAG, "SABR URL expiring (${(expiresAtMs - now) / 1000}s left) — requesting re-extraction")
                onError?.invoke(-4, "SABR streaming URL expiring", false)
                break
            }

            val maxGapMs =
                state.maxTimeSinceLastRequestMs.takeIf { it > 0 }
                    ?: DEFAULT_MAX_REQUEST_GAP_MS
            val heartbeatDue = now - lastRequestAtMs >= maxGapMs
            if (!heartbeatDue && bufferedAheadMs(state) >= targetReadaheadMs(state)) {
                continue
            }

            // Byte-level allow-gate, on top of the server's time targets: while the fetch has
            // run 24 MB ahead of what the player consumed, hold the request — the pipes, not
            // the server, are full. The stall check below never runs while gated, because a
            // full pipe is the opposite of a starved one.
            val aheadBytes = audioBuffer.bytesBuffered() + videoBuffer.bytesBuffered()
            val wasGated = byteAllowGate.engaged
            if (byteAllowGate.update(aheadBytes)) {
                if (!wasGated) Log.d(TAG, "Byte allow-gate engaged: ${aheadBytes / 1024} KiB ahead")
                continue
            } else if (wasGated) {
                Log.d(TAG, "Byte allow-gate released: ${aheadBytes / 1024} KiB ahead")
            }

            // Ninety seconds with no new bytes while the buffer is starved is a stalled
            // session, not a slow one — end it so the fallback can take over. A healthy
            // buffer never trips this, however long the server has been quiet.
            if (now - lastProgressMs >= STALL_TIMEOUT_MS && bufferedAheadMs(state) < targetReadaheadMs(state)) {
                Log.w(TAG, "SABR stalled: no new bytes for ${STALL_TIMEOUT_MS / 1000}s")
                io.github.shivytyahoo.glidetube.player.error.PlayerDiagnostics
                    .logError(TAG, "SABR stalled — no new bytes for 90s")
                onError?.invoke(-6, "SABR stalled", false)
                break
            }

            val refreshJob = poTokenRefreshJob
            if (refreshJob != null) {
                val refreshResult =
                    try {
                        refreshJob.await()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Distinguish our own cancellation from a superseded refresh job:
                        // an urgent re-attest cancels the non-urgent mint it replaces.
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        if (poTokenRefreshJob === refreshJob) poTokenRefreshJob = null
                        continue
                    } catch (e: Exception) {
                        Log.w(TAG, "PoToken refresh wait failed", e)
                        PoTokenRefreshResult(success = false, required = true)
                    }
                if (poTokenRefreshJob === refreshJob) poTokenRefreshJob = null
                if (!refreshResult.success && refreshResult.required) {
                    break
                }
            }

            try {
                controller.requestNextSegments()
                lastRequestAtMs = System.currentTimeMillis()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Follow-up request failed", e)
                noteConsecutiveError()
                if (consecutiveErrors >= MAX_FOLLOW_UP_ERRORS) {
                    onError?.invoke(-1, "Too many consecutive errors", false)
                    break
                }
                delay(1000L * consecutiveErrors)
            }
        }
    }

    /**
     * A mis-start restart: stop the in-flight fetch, empty the pipes, and re-issue the session
     * from the earlier time. The player is still in its first seconds here — it has consumed
     * nothing meaningful — so the fresh init plus the earlier segments read as a new stream
     * start, not a mid-playback cut.
     */
    private suspend fun restartSessionAt(restartFromMs: Long) {
        Log.w(TAG, "Restarting SABR session at ${restartFromMs}ms after mis-start")
        controller.abort()
        audioBuffer.reset()
        videoBuffer.reset()
        controller.sessionState.resetForRestart(restartFromMs)
        byteAllowGate.reset()
        lastProgressMs = System.currentTimeMillis()
        controller.startSession()
    }

    private fun bufferedAheadMs(state: io.github.shivytyahoo.glidetube.player.sabr.core.SabrSessionState): Long {
        val audioEndMs =
            state.audioBufferedRanges.maxOfOrNull { it.startTimeMs + it.durationMs }
                ?: return 0L
        // Audio-only mode: video lead is frozen by design — pace by audio alone
        if (state.enabledTrackTypes == 1) return audioEndMs - state.playheadPositionMs
        val videoEndMs =
            state.videoBufferedRanges.maxOfOrNull { it.startTimeMs + it.durationMs }
                ?: return 0L
        return minOf(audioEndMs, videoEndMs) - state.playheadPositionMs
    }

    private fun targetReadaheadMs(state: io.github.shivytyahoo.glidetube.player.sabr.core.SabrSessionState): Long =
        minOf(state.targetAudioReadaheadMs, state.targetVideoReadaheadMs)
            .coerceAtLeast(MIN_TARGET_READAHEAD_MS)

    private data class PoTokenRefreshResult(
        val success: Boolean,
        val required: Boolean,
    )
}
