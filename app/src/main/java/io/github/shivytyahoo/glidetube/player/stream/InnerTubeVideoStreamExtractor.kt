package io.github.shivytyahoo.glidetube.player.stream

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.util.UnstableApi
import io.github.shivytyahoo.glidetube.innertube.YouTube
import io.github.shivytyahoo.glidetube.innertube.models.AttestationPlatform
import io.github.shivytyahoo.glidetube.innertube.models.YouTubeClient
import io.github.shivytyahoo.glidetube.innertube.models.YouTubeLocale
import io.github.shivytyahoo.glidetube.innertube.models.response.PlayerResponse
import io.github.shivytyahoo.glidetube.player.error.PlayerDiagnostics
import io.github.shivytyahoo.glidetube.player.sabr.SabrRoutingPolicy
import io.github.shivytyahoo.glidetube.player.sabr.core.SabrCpn
import io.github.shivytyahoo.glidetube.player.sabr.integration.SabrClientIdentity
import io.github.shivytyahoo.glidetube.player.sabr.integration.SabrStreamInfo
import io.github.shivytyahoo.glidetube.player.sabr.integration.SabrUrlResolver
import io.github.shivytyahoo.glidetube.utils.cipher.CipherDeobfuscator
import io.github.shivytyahoo.glidetube.utils.cipher.PipePipeNsigDecoder
import io.github.shivytyahoo.glidetube.utils.potoken.PlayerWallReroll
import io.github.shivytyahoo.glidetube.utils.potoken.PoTokenResult
import io.github.shivytyahoo.glidetube.utils.potoken.WebPoTokenSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object InnerTubeVideoStreamExtractor {
    private const val TAG = "InnerTubeVideoExtractor"
    private const val PER_CLIENT_TIMEOUT_MS = 6000L
    private const val WEB_PLAYER_TIMEOUT_MS = 10000L

    // A cold player script is a 3 MB download. Past this the web request goes without a signature
    // timestamp and the download keeps running for the next extraction.
    private const val SIGNATURE_TIMESTAMP_WAIT_MS = 4000L

    // How long a confirmed winner will wait for the streaming pot before shipping its
    // URLs pot-less. The mint starts alongside the ladder, so a warm session is ready well before
    // any winner and pays nothing here; this only bounds the slow tail (cold WebView, the
    // low-trust re-attest loop) so first frame is never held open by BotGuard. A pot-less ship is
    // the pre-fix behavior: the URLs still start instantly, and if GVS cuts them ~60s in the
    // existing 403 -> reload path re-extracts with the (by then cached) token and recovers.
    private const val STREAM_POT_ATTACH_GRACE_MS = 2500L

    // Unconsumed deferred WEB tracking entries are best-effort blobs, not playback state;
    // past this the oldest are dropped rather than letting the map grow with the session.
    private const val DEFERRED_TRACKING_MAX_ENTRIES = 16

    /**
     * [VideoExtractionResult.webTrackFail] value meaning the WEB tracking fetch is still
     * running deferred — the result shipped without it; see [takeDeferredWebTracking].
     */
    const val WEB_TRACKING_DEFERRED = "deferred"

    // A cached extraction stays usable this long: far inside a freshly minted URL's
    // life, so a cached entry cannot be the thing a URL_EXPIRED denial fires on.
    private const val EXTRACTION_CACHE_TTL_MS = 5 * 60 * 1000L
    private const val EXTRACTION_CACHE_MAX_ENTRIES = 20
    private val N_PARAM_REGEX = Regex("""(?:^|[?&])n=([^&]+)""")
    private val POT_PARAM_REGEX = Regex("""[?&]pot=""")

    @Volatile
    private var lastSeenIdentityGeneration = WebPoTokenSession.identityGeneration
    private val extractionCoalescer =
        InFlightRequestCoalescer<ExtractionKey, VideoExtractionResult?>(
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )

    // Short-lived memory-only cache of extraction results, so a re-tap or a
    // back-and-reopen reuses the URLs the preview or an earlier tap already minted
    // instead of walking the client ladder again. Keyed by the full [ExtractionKey],
    // so a direct-only extraction never shares with a full one. Access-ordered with
    // eldest eviction, so a long session cannot grow it without limit.
    private data class CachedExtraction(
        val result: VideoExtractionResult,
        val cachedAtMs: Long,
        val identityGeneration: Int,
        val cookieHash: Int?,
    )

    private val extractionCache =
        object : LinkedHashMap<ExtractionKey, CachedExtraction>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ExtractionKey, CachedExtraction>): Boolean =
                size > EXTRACTION_CACHE_MAX_ENTRIES
        }

    /**
     * The fresh cached result for [key], or null when there is none, it is stale, or the
     * session it was minted under is gone. A result is session-bound two ways: the visitor
     * identity it was attested under ([WebPoTokenSession.identityGeneration], bumped on
     * every rotation) and the signed-in account ([YouTube.cookie], changed on sign-in,
     * sign-out and account switch — only its hash is kept, never the cookie itself). A
     * result from a client GVS is currently refusing is dropped too: the gate is recorded
     * synchronously on the refusal while the visitor re-roll it triggers runs async, so
     * without this a denial reload would be handed the same dead URLs it just failed on.
     */
    private fun cachedExtraction(key: ExtractionKey): VideoExtractionResult? {
        synchronized(extractionCache) {
            val cached = extractionCache[key] ?: return null
            val stale =
                SystemClock.elapsedRealtime() - cached.cachedAtMs > EXTRACTION_CACHE_TTL_MS ||
                    cached.identityGeneration != WebPoTokenSession.identityGeneration ||
                    cached.cookieHash != YouTube.cookie?.hashCode() ||
                    ClientGateTracker.isGated(cached.result.usedClient.clientName)
            if (stale) extractionCache.remove(key)
            return if (stale) null else cached.result
        }
    }

    private fun storeExtraction(
        key: ExtractionKey,
        result: VideoExtractionResult,
    ) {
        synchronized(extractionCache) {
            extractionCache[key] =
                CachedExtraction(
                    result = result,
                    cachedAtMs = SystemClock.elapsedRealtime(),
                    identityGeneration = WebPoTokenSession.identityGeneration,
                    cookieHash = YouTube.cookie?.hashCode(),
                )
        }
    }

    // Fire-and-forget WEB tracking fetches (see [deferWebTracking]): a supervisor scope on IO
    // so one failed fetch can neither cancel the others nor touch playback, and the work
    // outlives the extraction's coroutineScope, which ends when the result ships.
    private val deferredTrackingScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // WEB tracking fetched after the result shipped, keyed by video. Consumed once via
    // [takeDeferredWebTracking]; see [deferWebTracking] for the identity guard.
    private val deferredWebTracking =
        java.util.concurrent.ConcurrentHashMap<String, PlayerResponse.PlaybackTracking>()

    private data class ExtractionKey(
        val videoId: String,
        val forceSabr: Boolean,
        val audioOnly: Boolean,
        val skipSabrUpgrade: Boolean,
        // A forced method is a different question from Auto for the same video: without it
        // a cached direct result would be served for a SABR-mode request, and vice versa.
        // It also keeps the in-flight coalescer from joining a load started under another
        // method — the key is the coalescer's key too.
        val forcedMethod: String,
    )

    // The token-free direct client. VISIONOS alone: it serves direct adaptive URLs with no PO Token
    // and no `n` parameter, so first frame costs neither an attestation nor an nsig decode. GVS
    // honours them for the whole video unless it has walled this visitor, which it does a minute in
    // on every video (#921); then the ladder goes to TV_TIZEN and the visitor is re-rolled.
    private val FAST_CLIENTS: List<YouTubeClient> =
        listOf(
            YouTubeClient.VISIONOS,
        )

    /**
     * Direct clients GVS cuts off without a PO Token YouGlideTube cannot mint (DroidGuard). The gate is
     * bound to the byte offset, not wall-clock time, and measured between media position 60s and
     * 67s. Never promote these above [SABR_CLIENTS]. 1.65.10 leads as the only build still
     * answering; the others are kept because a bot wall is reputation-bound, not structural.
     */
    private val GATED_FALLBACK_CLIENTS: List<YouTubeClient> =
        listOf(
            YouTubeClient.ANDROID_VR_1_65_10,
            YouTubeClient.ANDROID_VR_1_61_48,
            YouTubeClient.ANDROID_VR_NO_AUTH,
            YouTubeClient.ANDROID_VR_1_43_32,
        )

    // Last-resort token-free client, tried after everything else.
    private val LAST_RESORT_CLIENTS: List<YouTubeClient> =
        listOf(
            YouTubeClient.MOBILE,
        )

    // MWEB first: it carries every dubbed audio track and is web-family, so its GVS token is one
    // YouGlideTube's own BotGuard session can mint.
    private val SABR_CLIENTS: List<YouTubeClient> =
        listOf(
            YouTubeClient.MWEB,
            YouTubeClient.WEB,
        )

    // ANDROID_VR leads: on a live broadcast it is the only client that returns a DASH manifest
    // alongside the HLS one, and VISIONOS returns HLS only.
    private val LIVE_MANIFEST_CLIENTS: List<YouTubeClient> =
        listOf(
            YouTubeClient.ANDROID_VR_1_65_10,
            YouTubeClient.VISIONOS,
        )

    data class VideoExtractionResult(
        val videoFormats: List<PlayerResponse.StreamingData.Format>,
        val audioFormats: List<PlayerResponse.StreamingData.Format>,
        val playerResponse: PlayerResponse,
        val usedClient: YouTubeClient,
        val sabrInfo: SabrStreamInfo?,
        val isLive: Boolean = false,
        val liveHlsUrl: String? = null,
        val liveDashUrl: String? = null,
        /**
         * Tracking URLs minted for WEB by a WEB player response, when the
         * winning response came from another client. The history beacons
         * must present a blob minted for the client they claim: a
         * VISIONOS-minted ei/plid presented with WEB params + WEB auth
         * answered 204 on device yet never landed in the account history
         * (0.3.47–0.3.53). Null when the winner already is WEB, when the WEB
         * tracking fetch failed, or while the fetch is still deferred
         * (webTrackFail "deferred" — see [takeDeferredWebTracking]) — callers
         * then fall back to the winning response's own tracking URLs.
         */
        val webTracking: PlayerResponse.PlaybackTracking? = null,
        /** Why the WEB tracking fetch produced nothing, for the statsDiag line. */
        val webTrackFail: String? = null,
    )

    /**
     * Drops every cached extraction for [videoId], so a playback-method change re-extracts
     * instead of replaying the old method's URLs. In-flight requests need no eviction: they
     * are keyed by the full [ExtractionKey], so a load under the new method never joins one
     * started under the old.
     */
    fun clearCachedExtractions(videoId: String) {
        synchronized(extractionCache) {
            extractionCache.keys.removeAll { it.videoId == videoId }
        }
    }

    /**
     * @param audioOnly the caller plays only [VideoExtractionResult.audioFormats], so a capped
     *   video ladder is not worth the SABR quality upgrade's wait.
     * @param skipSabrUpgrade when true, the direct ladder is returned as resolved and the
     *   guarded SABR upgrade is not attempted inline — the caller ships the playable direct
     *   result immediately and runs the upgrade in the background via [upgradeToSabr] instead,
     *   so the upgrade's token mint never delays first frame. Part of the key, so a
     *   direct-only extraction never shares a coalesced result with a full one.
     * @param forcedMethod a single client to try on its own (Auto walks the ladder). SABR
     *   here means the same forced-SABR path as [forceSabr].
     */
    @OptIn(UnstableApi::class)
    // allowLowTrust: a cold token is fine for a Short (over before GVS enforcement bites ~60s
    // in) but stalls long-form mid-playback, so only the Shorts path passes true.
    suspend fun extract(
        videoId: String,
        forceSabr: Boolean = false,
        audioOnly: Boolean = false,
        allowLowTrust: Boolean = false,
        skipSabrUpgrade: Boolean = false,
        forcedMethod: PlaybackMethod = PlaybackMethod.AUTO,
    ): VideoExtractionResult? {
        val sabr = forceSabr || forcedMethod == PlaybackMethod.SABR
        val key = ExtractionKey(videoId, sabr, audioOnly, skipSabrUpgrade, forcedMethod.storedValue)
        // A walled visitor's cached result hands back the URLs that just 403'd, and the
        // re-roll the denial report fired races the reload that needs it. Await a fresh
        // visitor while the fast path is demoted: the generation bump invalidates the
        // cache below, so the ladder runs again under the new identity. Auto only: a
        // forced method retries the same client under the new identity inside its own
        // path instead.
        if (forcedMethod == PlaybackMethod.AUTO && FAST_CLIENTS.ungated().isEmpty()) {
            WebPoTokenSession.rerollAfterWallNow(FAST_CLIENTS.first().clientName)
        }
        // Only the plain calls are cached. A forced-SABR result is a recovery path that
        // must always re-run the ladder, an audio-only result answers a different
        // question, and a low-trust Shorts result was minted under a trust exception the
        // full player must not inherit — those callers re-extract as before. A forced
        // method is never cached either: it is an explicit per-video choice, and caching
        // it would let a stale forced result outlive the setting that asked for it.
        val cacheable = !sabr && !audioOnly && !allowLowTrust && forcedMethod == PlaybackMethod.AUTO
        if (cacheable) cachedExtraction(key)?.let { return it }
        val result =
            extractionCoalescer.run(key) {
                selectStreams(videoId, sabr, audioOnly, allowLowTrust, skipSabrUpgrade, forcedMethod)
            }
        if (cacheable && result != null) storeExtraction(key, result)
        return result
    }

    /**
     * Runs only the guarded SABR upgrade over an already-resolved direct result: the direct
     * result is returned unchanged when it is live, already SABR-backed, or not
     * quality-incomplete, when the upgrade times out, or when it does not beat the direct
     * ceiling. The returned result is never SABR-only — a SABR session without playable direct
     * formats is dropped inside the upgrade, exactly as the inline path does.
     */
    suspend fun upgradeToSabr(
        videoId: String,
        direct: VideoExtractionResult,
    ): VideoExtractionResult = maybeUpgradeToSabr(videoId, direct, mutableListOf())

    private suspend fun selectStreams(
        videoId: String,
        forceSabr: Boolean,
        audioOnly: Boolean,
        allowLowTrust: Boolean = false,
        skipSabrUpgrade: Boolean = false,
        forcedMethod: PlaybackMethod = PlaybackMethod.AUTO,
    ): VideoExtractionResult? =
        withContext(Dispatchers.IO) {
            Log.w(TAG, "Extraction start for $videoId (forceSabr=$forceSabr, method=${forcedMethod.name})")
            PlayerDiagnostics.logWarning(TAG, "extract start $videoId forceSabr=$forceSabr method=${forcedMethod.name}")
            dropGatesOnIdentityChange()
            blockedVideoIds.remove(videoId)
            goneVideoIds.remove(videoId)
            if (forceSabr) {
                val failureReasons = mutableListOf<String>()
                trySabrClients(videoId, failureReasons)?.let {
                    Log.w(TAG, "Extraction OK for $videoId via ${it.usedClient.clientName} (mode=SABR/forced)")
                    PlayerDiagnostics.logWarning(TAG, "extract OK $videoId mode=SABR/forced via ${it.usedClient.clientName}")
                    return@withContext it
                }
                Log.e(TAG, "Forced SABR extraction failed for $videoId. Reasons: ${failureReasons.joinToString(" | ")}")
                PlayerDiagnostics.logError(TAG, "forced SABR FAILED $videoId: ${failureReasons.joinToString(" | ")}")
                recordBlock(videoId, failureReasons)
                return@withContext null
            }

            if (forcedMethod != PlaybackMethod.AUTO) {
                // A forced method is tried on its own: no other client is attempted, so a
                // refusal here ends the load instead of walking the ladder. A walled visitor
                // may still re-roll its identity and retry THE SAME client — that is a new
                // identity, not a fallback to another client. Live is the one exception: a
                // live broadcast always plays through the live branch, whatever is forced.
                val failureReasons = mutableListOf<String>()
                val liveDetected = booleanArrayOf(false)
                tryForcedMethod(videoId, forcedMethod, failureReasons, liveDetected)?.let {
                    Log.w(TAG, "Extraction OK for $videoId via ${it.usedClient.clientName} (mode=FORCED/${forcedMethod.name})")
                    PlayerDiagnostics.logWarning(
                        TAG,
                        "extract OK $videoId mode=FORCED/${forcedMethod.name} via ${it.usedClient.clientName}",
                    )
                    return@withContext it
                }
                if (liveDetected[0]) {
                    tryLiveClients(videoId, failureReasons)?.let {
                        Log.w(
                            TAG,
                            "Live manifest for $videoId via ${it.usedClient.clientName} " +
                                "(live-clients; forced ${forcedMethod.name} does not serve live)",
                        )
                        return@withContext it
                    }
                }
                Log.e(TAG, "Forced ${forcedMethod.name} extraction failed for $videoId. Reasons: ${failureReasons.joinToString(" | ")}")
                PlayerDiagnostics.logError(TAG, "forced ${forcedMethod.name} FAILED $videoId: ${failureReasons.joinToString(" | ")}")
                recordBlock(videoId, failureReasons)
                return@withContext null
            }

            var failureReasons = mutableListOf<String>()
            val walledGeneration = WebPoTokenSession.identityGeneration
            walkLadder(videoId, audioOnly, failureReasons, allowLowTrust, skipSabrUpgrade, walledGeneration)?.let { return@withContext it }

            // A wall at /player issues no stream URLs, so no 403 ever reaches the re-roll that GVS
            // refusals trigger, and a retry would ask again with the same walled visitor (#1354).
            val fullWall = PlayabilityVerdict.everyClientWalled(failureReasons)
            val reroll = if (fullWall) WebPoTokenSession.rerollAfterPlayerWall(walledGeneration) else null
            if (reroll == PlayerWallReroll.REROLLED) {
                PlayerDiagnostics.logWarning(TAG, "every client bot-walled $videoId, retrying once with a fresh visitor")
                dropGatesOnIdentityChange()
                failureReasons = mutableListOf()
                walkLadder(
                    videoId,
                    audioOnly,
                    failureReasons,
                    allowLowTrust,
                    skipSabrUpgrade,
                    walledGeneration,
                )?.let { return@withContext it }
            }
            val freshVisitorWalled = reroll == PlayerWallReroll.REROLLED || reroll == PlayerWallReroll.FRESH_VISITOR_WALLED

            Log.e(TAG, "All clients failed for $videoId (forceSabr=$forceSabr). Reasons: ${failureReasons.joinToString(" | ")}")
            PlayerDiagnostics.logError(TAG, "ALL clients failed $videoId: ${failureReasons.joinToString(" | ")}")
            if (PlayabilityVerdict.everyClientWalled(failureReasons)) {
                PlayerDiagnostics.logError(TAG, "bot wall $videoId: ${WebPoTokenSession.identitySummary()} freshWalled=$freshVisitorWalled")
            }
            if (PlayabilityVerdict.isGone(failureReasons)) goneVideoIds += videoId
            recordBlock(videoId, failureReasons, freshVisitorWalled)
            null
        }

    /**
     * Steps 1 to 5 of the ladder, from the fast token-free clients to the demoted retry. Null when
     * every step failed, with why in [failureReasons].
     */
    private suspend fun walkLadder(
        videoId: String,
        audioOnly: Boolean,
        failureReasons: MutableList<String>,
        allowLowTrust: Boolean = false,
        skipSabrUpgrade: Boolean = false,
        walledGeneration: Int,
    ): VideoExtractionResult? {
        val liveDetected = booleanArrayOf(false)
        // The first gated client step 1 raced (ANDROID_VR_1_65_10 while ungated); step 3
        // must not fetch it a second time. Null when no race ran.
        var racedGatedClient: YouTubeClient? = null

        // 1) Fast path: token-free clients with direct URLs. Skipped while GVS is enforcing
        // attestation on them — without that memory the ladder walks into the same refusal on
        // every single video, which is what "every video stops at a minute" looks like.
        val fastClients = FAST_CLIENTS.ungated()
        if (fastClients.isEmpty()) {
            Log.w(TAG, "Fast clients demoted for $videoId (gated: ${ClientGateTracker.gatedClients()}) — trying TV_TIZEN")
            PlayerDiagnostics.logWarning(
                TAG,
                "fast path SKIPPED $videoId — gated clients: ${ClientGateTracker.gatedClients().joinToString()}",
            )
            // The walled visitor's other app clients and token-backed web clients stop at the
            // same minute; TV_TIZEN is the measured way past it (#921).
            TizenStreamResolver.resolve(videoId, failureReasons)?.let {
                Log.w(TAG, "Extraction OK for $videoId via TV_TIZEN (mode=DIRECT/walled)")
                PlayerDiagnostics.logWarning(TAG, "extract OK $videoId via TV_TIZEN mode=DIRECT/walled")
                return it
            }
        } else {
            // The wall is known the moment the fast race ends: every fast client refused
            // this visitor. Re-roll NOW and re-race, instead of spending the user's
            // patience on SABR/Tizen/gated steps that need the same walled visitor — that
            // tail is why a wall used to end with the user leaving before the end-of-ladder
            // recovery ever ran (#67). Once per generation, like the ladder-end recovery.
            var reRaced = false
            while (true) {
                // A cold start pays DNS and TLS inside the first request, which can outlast the
                // per-client timeout; one retry on the warm connection beats falling to the web path.
                // The first gated client races the fast path instead of waiting behind the SABR
                // and Tizen steps: whichever answers playable first wins, the loser is cancelled,
                // and step 3 below retries only the gated clients this race did not already try.
                val raceGatedFirst = GATED_FALLBACK_CLIENTS.ungated().firstOrNull()
                racedGatedClient = raceGatedFirst
                val direct =
                    if (raceGatedFirst != null) {
                        raceFirstTwoDirectClients(videoId, fastClients, raceGatedFirst, failureReasons, liveDetected, allowLowTrust)
                    } else {
                        tryDirectClients(
                            videoId,
                            fastClients,
                            failureReasons,
                            liveDetected = liveDetected,
                            retryTimeoutOnce = true,
                            allowLowTrust = allowLowTrust,
                        )
                    }
                direct?.let {
                    val result = if (audioOnly || skipSabrUpgrade) it else maybeUpgradeToSabr(videoId, it, failureReasons)
                    Log.w(TAG, "Extraction OK for $videoId via ${result.usedClient.clientName} (mode=${resultMode(result)})")
                    PlayerDiagnostics.logWarning(TAG, "extract OK $videoId via ${result.usedClient.clientName} mode=${resultMode(result)}")
                    return result
                }
                if (!reRaced && PlayabilityVerdict.everyClientWalled(failureReasons) &&
                    WebPoTokenSession.rerollAfterPlayerWall(walledGeneration) == PlayerWallReroll.REROLLED
                ) {
                    reRaced = true
                    Log.w(TAG, "Fast clients walled for $videoId — re-rolled the visitor, re-racing them")
                    PlayerDiagnostics.logWarning(TAG, "fast path walled $videoId — re-rolled visitor, re-racing")
                    dropGatesOnIdentityChange()
                    failureReasons.clear()
                    continue
                }
                break
            }
        }

        if (liveDetected[0]) {
            tryLiveClients(videoId, failureReasons)?.let {
                Log.w(TAG, "Live manifest for $videoId via ${it.usedClient.clientName} (live-clients)")
                return it
            }
        }

        // When the fast clients all refused the video itself (UNPLAYABLE — a content
        // restriction, not a wall), the video comes from SABR or Tizen and nothing else
        // in the ladder can serve it sooner. Race them instead of walking SABR first
        // and Tizen after: a working SABR still wins in ~1s, and a failing SABR no
        // longer costs its full time before Tizen starts (#69 — 6.5s to first frame).
        val allUnplayable =
            !liveDetected[0] && failureReasons.isNotEmpty() && failureReasons.all { "status=UNPLAYABLE" in it }
        if (allUnplayable) {
            raceSabrVsTizen(videoId, failureReasons)?.let {
                if (it.usedClient == YouTubeClient.TV_TIZEN) {
                    Log.w(TAG, "Extraction OK for $videoId via TV_TIZEN (mode=DIRECT/web-fallback)")
                    PlayerDiagnostics.logWarning(TAG, "extract OK $videoId via TV_TIZEN mode=DIRECT/web-fallback")
                    return it
                }
                Log.w(TAG, "Extraction OK for $videoId via ${it.usedClient.clientName} (mode=SABR)")
                PlayerDiagnostics.logWarning(TAG, "extract OK $videoId mode=SABR (durable) via ${it.usedClient.clientName}")
                return if (liveDetected[0] && !it.isLive) it.copy(isLive = true) else it
            }
        } else {
            // 2) Durable path: web client + BotGuard PoToken + SABR. Ranked above every remaining
            // direct client because it is the only other path whose token YouGlideTube can actually mint —
            // the clients below it are all served unattested and can be cut off mid-playback.
            trySabrClients(videoId, failureReasons)?.let {
                Log.w(TAG, "Extraction OK for $videoId via ${it.usedClient.clientName} (mode=SABR)")
                PlayerDiagnostics.logWarning(TAG, "extract OK $videoId mode=SABR (durable) via ${it.usedClient.clientName}")
                return if (liveDetected[0] && !it.isLive) it.copy(isLive = true) else it
            }

            // 2b) Label and kids videos are UNPLAYABLE on the app clients, so once the web clients
            // fail this is their only source (#1292). The gated clients below stop at a minute and
            // swap to it mid-playback anyway. Skipped when step 1 already asked it.
            if (fastClients.isNotEmpty() && !liveDetected[0]) {
                TizenStreamResolver.resolve(videoId, failureReasons)?.let {
                    Log.w(TAG, "Extraction OK for $videoId via TV_TIZEN (mode=DIRECT/web-fallback)")
                    PlayerDiagnostics.logWarning(TAG, "extract OK $videoId via TV_TIZEN mode=DIRECT/web-fallback")
                    return it
                }
            }
        }

        // 3) Gated direct clients. Playable, but GVS stops serving them ~60s in, so they rank
        // below anything attested and are only reached when the paths above are unavailable.
        // Step 1 already raced the first ungated one; retrying it here would be a second
        // fetch for the same cause.
        val remainingGated = GATED_FALLBACK_CLIENTS.ungated().filterNot { it == racedGatedClient }
        tryDirectClients(
            videoId,
            remainingGated,
            failureReasons,
            liveDetected = liveDetected,
            allowLowTrust = allowLowTrust,
        )?.let { direct ->
            val result = if (audioOnly || skipSabrUpgrade) direct else maybeUpgradeToSabr(videoId, direct, failureReasons)
            Log.w(TAG, "Extraction OK for $videoId via ${result.usedClient.clientName} (mode=${resultMode(result)}/gated)")
            PlayerDiagnostics.logWarning(
                TAG,
                "extract OK $videoId via ${result.usedClient.clientName} mode=${resultMode(result)}/GATED — " +
                    "unattested, GVS may stop serving ~60s in",
            )
            return result
        }

        // 4) Last resort: remaining token-free clients
        tryDirectClients(
            videoId,
            LAST_RESORT_CLIENTS.ungated(),
            failureReasons,
            allowUntransformedN = true,
            liveDetected = liveDetected,
            allowLowTrust = allowLowTrust,
        )?.let {
            Log.w(TAG, "Extraction OK for $videoId via ${it.usedClient.clientName} (mode=DIRECT/last-resort)")
            PlayerDiagnostics.logWarning(TAG, "extract OK $videoId via ${it.usedClient.clientName} mode=DIRECT/last-resort")
            return if (liveDetected[0] && !it.isLive) it.copy(isLive = true) else it
        }

        // 5) Every attested path is gone and the demotion has left nothing to try. A client
        // that plays a minute beats a client that plays nothing, so the demotions are ignored
        // for one pass rather than returning a failure the user cannot act on.
        val demoted = (FAST_CLIENTS + GATED_FALLBACK_CLIENTS + LAST_RESORT_CLIENTS).filter { ClientGateTracker.isGated(it.clientName) }
        if (demoted.isNotEmpty()) {
            Log.w(TAG, "Nothing playable for $videoId without the demoted clients — retrying them: ${demoted.map { it.clientName }}")
            PlayerDiagnostics.logWarning(TAG, "retrying DEMOTED clients for $videoId: ${demoted.joinToString { it.clientName }}")
            tryDirectClients(
                videoId,
                demoted,
                failureReasons,
                allowUntransformedN = true,
                liveDetected = liveDetected,
                allowLowTrust = allowLowTrust,
            )?.let {
                PlayerDiagnostics.logWarning(TAG, "extract OK $videoId via ${it.usedClient.clientName} mode=DIRECT/demoted-retry")
                return if (liveDetected[0] && !it.isLive) it.copy(isLive = true) else it
            }
        }

        return null
    }

    /**
     * Races the fast path against the first gated direct client (VISIONOS vs
     * ANDROID_VR_1_65_10 — the ladder's first two direct clients) instead of trying them
     * one after another. Both run under their existing per-client timeouts, the first
     * playable result wins, and the loser is cancelled outright — a bare coroutineScope
     * would wait for it, so the race would cost max(fast, gated) instead of min. When the
     * first finisher fails, the other is waited out up to its own timeout — a fast
     * failure must not end the race. Neither client is WEB-attested (VISIONOS is
     * IOSGUARD, ANDROID_VR is DROIDGUARD), so neither starts a PoToken mint and the
     * branches share nothing but [liveDetected] (a write-once flag) and their own
     * failure lists, merged below in ladder order because the caller's list is not
     * thread-safe.
     */
    private suspend fun raceFirstTwoDirectClients(
        videoId: String,
        fastClients: List<YouTubeClient>,
        gatedFirst: YouTubeClient,
        failureReasons: MutableList<String>,
        liveDetected: BooleanArray?,
        allowLowTrust: Boolean,
    ): VideoExtractionResult? {
        // Per-branch failure lists: the caller's list is not thread-safe, so each racer
        // records into its own. Merged only after the scope below has returned, when
        // both branches — including a cancelled loser — have fully finished.
        val fastFailures = mutableListOf<String>()
        val gatedFailures = mutableListOf<String>()
        val winner =
            coroutineScope {
                val fast =
                    async {
                        tryDirectClients(
                            videoId,
                            fastClients,
                            fastFailures,
                            liveDetected = liveDetected,
                            retryTimeoutOnce = true,
                            allowLowTrust = allowLowTrust,
                        )
                    }
                val gated =
                    async {
                        tryDirectClients(
                            videoId,
                            listOf(gatedFirst),
                            gatedFailures,
                            liveDetected = liveDetected,
                            allowLowTrust = allowLowTrust,
                        )
                    }
                var winner: VideoExtractionResult? = null
                select<Unit> {
                    fast.onAwait { winner = it }
                    gated.onAwait { winner = it }
                }
                if (winner != null) {
                    // coroutineScope does not return until every child completes, so without
                    // this the race would cost max(fast, gated) instead of min: cancel the
                    // loser outright. The scope's exit still waits for the cancelled child
                    // to actually stop, so its cancellation propagates before we return.
                    if (!fast.isCompleted) fast.cancel()
                    if (!gated.isCompleted) gated.cancel()
                } else {
                    // The first finisher failed; wait out the other one's existing timeout.
                    winner = if (fast.isCompleted) gated.await() else fast.await()
                }
                winner
            }
        failureReasons += fastFailures
        failureReasons += gatedFailures
        return winner
    }

    /**
     * Races SABR against Tizen for a video the fast clients all refused as UNPLAYABLE.
     * Both run under their existing timeouts, the first playable result wins, and the
     * loser is cancelled outright — same shape as [raceFirstTwoDirectClients]. A working
     * SABR still wins in ~1s; a failing one no longer costs its full time before Tizen
     * starts, which is what made such videos take ~6s to first frame (#69). Per-branch
     * failure lists for the same reason as the direct race: the caller's list is not
     * thread-safe.
     */
    private suspend fun raceSabrVsTizen(
        videoId: String,
        failureReasons: MutableList<String>,
    ): VideoExtractionResult? {
        val sabrFailures = mutableListOf<String>()
        val tizenFailures = mutableListOf<String>()
        val winner =
            coroutineScope {
                val sabr = async { trySabrClients(videoId, sabrFailures) }
                val tizen = async { TizenStreamResolver.resolve(videoId, tizenFailures) }
                var winner: VideoExtractionResult? = null
                select<Unit> {
                    sabr.onAwait { winner = it }
                    tizen.onAwait { winner = it }
                }
                if (winner != null) {
                    if (!sabr.isCompleted) sabr.cancel()
                    if (!tizen.isCompleted) tizen.cancel()
                } else {
                    winner = if (sabr.isCompleted) tizen.await() else sabr.await()
                }
                winner
            }
        failureReasons += sabrFailures
        failureReasons += tizenFailures
        return winner
    }

    /** Videos YouTube reported as removed or unplayable on every client; a queue skips them. */
    private val goneVideoIds: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet()

    fun isGone(videoId: String): Boolean = videoId in goneVideoIds

    private val blockedVideoIds = java.util.concurrent.ConcurrentHashMap<String, PlaybackBlock>()

    /** Why the last extraction of [videoId] failed, when YouTube refused the viewer rather than the video. */
    fun blockOf(videoId: String): PlaybackBlock? = blockedVideoIds[videoId]

    private fun recordBlock(
        videoId: String,
        failureReasons: List<String>,
        freshVisitorWalled: Boolean = false,
    ) {
        PlayabilityVerdict.block(failureReasons, freshVisitorWalled)?.let { blockedVideoIds[videoId] = it }
    }

    /** The clients GVS is not currently refusing to serve. See [ClientGateTracker]. */
    private fun List<YouTubeClient>.ungated(): List<YouTubeClient> = filterNot { ClientGateTracker.isGated(it.clientName) }

    /**
     * A demotion is a verdict GVS reached about one visitor identity. Once that identity has been
     * replaced the verdict says nothing, so the ladder gets its fast clients back immediately
     * instead of waiting out the registry's timer.
     */
    private fun dropGatesOnIdentityChange() {
        val generation = WebPoTokenSession.identityGeneration
        if (generation == lastSeenIdentityGeneration) return
        lastSeenIdentityGeneration = generation
        if (ClientGateTracker.gatedClients().isEmpty()) return
        Log.w(TAG, "Visitor identity rotated — clearing client demotions")
        PlayerDiagnostics.logWarning(TAG, "visitor identity rotated — client demotions cleared")
        ClientGateTracker.clear()
    }

    private fun resultMode(r: VideoExtractionResult): String =
        when {
            r.isLive -> "LIVE"
            r.sabrInfo != null -> "SABR-upgrade"
            else -> "DIRECT"
        }

    /**
     * Guarded SABR upgrade. YouTube increasingly serves the high rungs of the ladder as
     * SABR-only (no direct URL), so a token-free client can succeed while only exposing e.g.
     * 360p — leaving the user stuck low with "no other quality". When the direct ladder is
     * quality-incomplete ([SabrRoutingPolicy.shouldAttemptSabrUpgrade]), try the
     * WEB+PoToken+SABR path and prefer it ONLY if it beats the direct ceiling. If SABR is
     * unavailable (bot-walled, no PoToken) the original direct result is returned unchanged, so
     * a video that plays today can never regress.
     */
    private suspend fun maybeUpgradeToSabr(
        videoId: String,
        direct: VideoExtractionResult,
        failureReasons: MutableList<String>,
    ): VideoExtractionResult {
        if (direct.isLive || direct.sabrInfo != null) return direct

        val directMaxHeight = direct.videoFormats.maxOfOrNull { it.height ?: 0 } ?: 0
        if (!SabrRoutingPolicy.shouldAttemptSabrUpgrade(directMaxHeight)) return direct

        Log.w(
            TAG,
            "Direct ladder for $videoId capped at ${directMaxHeight}p (< ${SabrRoutingPolicy.QUALITY_UPGRADE_FLOOR}p); attempting SABR upgrade",
        )
        val sabr =
            withTimeoutOrNull(SabrRoutingPolicy.UPGRADE_BUDGET_MS) { trySabrClients(videoId, failureReasons) }
                ?: return direct.also {
                    PlayerDiagnostics.logWarning(TAG, "SABR upgrade unavailable or out of budget for $videoId, playing direct")
                }
        val sabrHeight = sabr.sabrInfo?.videoHeight
        return if (SabrRoutingPolicy.upgradeReplacesDirect(directMaxHeight, sabrHeight)) {
            Log.w(TAG, "Upgraded $videoId: ${directMaxHeight}p direct → ${sabrHeight}p SABR")
            sabr
        } else {
            direct
        }
    }

    /**
     * @param client the client that opened the session being reloaded. A reload must stay on it —
     *   re-resolving an MWEB session as WEB would contradict the player response the server is
     *   already tracking — so this deliberately does not walk [SABR_CLIENTS].
     */
    suspend fun resolveSabrDownload(
        videoId: String,
        targetHeight: Int = 0,
        preferredCodec: String? = null,
        cpn: String = SabrCpn.generate(),
        reloadToken: String? = null,
        client: YouTubeClient = YouTubeClient.WEB,
    ): SabrStreamInfo? =
        withContext(Dispatchers.IO) {
            val failureReasons = mutableListOf<String>()
            tryWebSabr(
                videoId = videoId,
                failureReasons = failureReasons,
                targetHeight = targetHeight,
                preferredCodec = preferredCodec,
                cpn = cpn,
                reloadToken = reloadToken,
                client = client,
            )?.sabrInfo.also { sabrInfo ->
                if (sabrInfo == null) {
                    Log.w(TAG, "SABR download resolve failed for $videoId: ${failureReasons.joinToString(" | ")}")
                }
            }
        }

    /**
     * WEB tracking, off the critical path: the playable result ships with
     * [VideoExtractionResult.webTracking] null and webTrackFail "deferred", and the WEB
     * /player fetch runs fire-and-forget on [deferredTrackingScope] afterwards. Returns
     * the webTrackFail value the result should carry: "winner-is-web" when the winner
     * already is WEB, so nothing is fetched; [WEB_TRACKING_DEFERRED] otherwise. [fetchWebTracking]
     * keeps its own error handling — a failed fetch returns its reason and never throws
     * except on cancellation — so this can never break playback. The fetch is guarded
     * on [WebPoTokenSession.identityGeneration], the same hook
     * [dropGatesOnIdentityChange] uses: if the visitor identity rotated between
     * deferring and fetching (wall re-roll, attestation-stuck rotation, manual session
     * reset), the fetch is dropped instead of minting and presenting a blob for a
     * visitor the playback no longer belongs to. Sign-out / account switch does NOT
     * bump that generation (AccountSessionManager never touches the visitor session),
     * so a switch mid-fetch can still present a guest-minted blob with new account
     * auth — measured (0.3.59) to answer 204 without recording, i.e. a lost beacon,
     * never a wrong-account recording.
     */
    private fun deferWebTracking(
        videoId: String,
        winner: YouTubeClient,
        poToken: String?,
    ): String {
        if (winner.clientName == YouTubeClient.WEB.clientName) return "winner-is-web"
        val generation = WebPoTokenSession.identityGeneration
        deferredTrackingScope.launch {
            if (WebPoTokenSession.identityGeneration != generation) {
                Log.w(TAG, "Dropping deferred WEB tracking for $videoId — visitor identity rotated")
                return@launch
            }
            val (tracking, fail) = fetchWebTracking(videoId, winner, poToken)
            if (WebPoTokenSession.identityGeneration != generation) {
                Log.w(TAG, "Dropping deferred WEB tracking for $videoId — visitor identity rotated mid-fetch")
                return@launch
            }
            if (tracking != null) {
                if (deferredWebTracking.size >= DEFERRED_TRACKING_MAX_ENTRIES) deferredWebTracking.clear()
                deferredWebTracking[videoId] = tracking
                PlayerDiagnostics.logWarning(TAG, "deferred WEB tracking ready for $videoId")
            } else {
                PlayerDiagnostics.logWarning(TAG, "deferred WEB tracking unavailable for $videoId: $fail")
            }
        }
        return WEB_TRACKING_DEFERRED
    }

    /**
     * Takes WEB tracking the deferred fetch produced for [videoId], or null when it has
     * not arrived (yet). Consume-once: the entry is removed, so a later video never
     * reuses a stale blob. The history beacon reads this when the result's own
     * [VideoExtractionResult.webTracking] is null with webTrackFail "deferred" — until
     * that call site is wired, the beacon falls back to the winning response's own
     * tracking URLs, as it does for any failed WEB fetch today.
     */
    fun takeDeferredWebTracking(videoId: String): PlayerResponse.PlaybackTracking? = deferredWebTracking.remove(videoId)

    /** The mint's player token, only if the mint already finished — this never waits for it. */
    private fun Deferred<PoTokenResult?>?.readyPlayerToken(): String? {
        if (this == null || !isCompleted) return null
        return try {
            getCompleted()?.playerRequestPoToken?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Best-effort WEB-minted tracking URLs for the history beacons. The
     * direct ladder usually wins on VISIONOS, whose ei/plid blob the server
     * will not attribute to a WEB-context beacon (0.3.47–0.3.53: 204, no
     * record). The fetch uses the SABR path's exact WEB request shape
     * ([YouTube.playerWeb]): the generic attested WEB request returned a
     * response with no tracking at all (0.3.58, webFail=no-tracking) —
     * it omits the full contentPlaybackContext (referer, vis, splay,
     * lact, html5Preference) and the X-Goog-Visitor-Id header that
     * playerWeb sends, and the token is minted for the same visitorData
     * the request carries. The fast path (FAST_CLIENTS = VISIONOS only)
     * never starts the ladder's mint, so the fetch mints its own token;
     * that can cost seconds on a cold session's first video, later videos
     * reuse the warm session. The fetch also sends the session auth
     * (cookie/SAPISIDHASH or OAuth bearer): a guest-minted blob presented
     * with account auth was accepted (204) but never recorded (0.3.59) —
     * the blob must be account-minted for the server to attribute it to
     * the account's history. Returns the tracking plus a failure reason
     * (null on success) so the statsDiag line can say WHY the fetch
     * produced nothing: callers fall back to the winning response's URLs.
     * Never throws except on cancellation, so tracking can never break
     * playback.
     */
    private suspend fun fetchWebTracking(
        videoId: String,
        winner: YouTubeClient,
        poToken: String?,
    ): Pair<PlayerResponse.PlaybackTracking?, String?> {
        if (winner.clientName == YouTubeClient.WEB.clientName) return null to "winner-is-web"
        val visitorData =
            WebPoTokenSession.sessionVisitorData()
                ?: return null to "no-visitor"
        val token =
            poToken?.takeIf { it.isNotBlank() }
                ?: withTimeoutOrNull(10_000L) {
                    WebPoTokenSession.mintForVisitorData(videoId, visitorData)
                }?.playerRequestPoToken
        if (token.isNullOrBlank()) return null to "mint-failed"
        val sts =
            withTimeoutOrNull(SIGNATURE_TIMESTAMP_WAIT_MS) {
                CipherDeobfuscator.ensureSignatureTimestamp()
            }
        return try {
            val result =
                withTimeoutOrNull(WEB_PLAYER_TIMEOUT_MS) {
                    YouTube.playerWeb(
                        videoId = videoId,
                        signatureTimestamp = sts,
                        poToken = token,
                        visitorData = visitorData,
                        locale = YouTubeLocale.EXTRACTION,
                        cpn = SabrCpn.generate(),
                        reloadToken = null,
                        client = YouTubeClient.WEB,
                        withAuth = true,
                    )
                }
            val response = result?.getOrNull()
            when {
                result == null -> null to "timeout"
                response == null -> null to "player-failed"
                response.playbackTracking == null -> null to "no-tracking"
                else -> response.playbackTracking to null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null to "error:${e.javaClass.simpleName}"
        }
    }

    /**
     * One forced method, and nothing else. VISIONOS and KIDS are single clients; ANDROID_VR is
     * its version variants in ladder order (still one client name — a demoted variant retried
     * here is the same client, not a fallback to another one); TV is the Tizen resolver only;
     * SABR is the web-client SABR path. Live detection rides along for the direct clients so
     * the caller can fall through to the live branch when the forced client cannot serve a
     * live broadcast.
     */
    private suspend fun tryForcedMethod(
        videoId: String,
        method: PlaybackMethod,
        failureReasons: MutableList<String>,
        liveDetected: BooleanArray,
    ): VideoExtractionResult? =
        when (method) {
            PlaybackMethod.AUTO -> {
                null
            }

            PlaybackMethod.VISIONOS -> {
                tryDirectClients(
                    videoId,
                    FAST_CLIENTS,
                    failureReasons,
                    liveDetected = liveDetected,
                    retryTimeoutOnce = true,
                )
            }

            PlaybackMethod.ANDROID_VR -> {
                tryDirectClients(
                    videoId,
                    GATED_FALLBACK_CLIENTS,
                    failureReasons,
                    liveDetected = liveDetected,
                )
            }

            PlaybackMethod.KIDS -> {
                tryDirectClients(
                    videoId,
                    listOf(YouTubeClient.ANDROID_KIDS),
                    failureReasons,
                    liveDetected = liveDetected,
                    retryTimeoutOnce = true,
                )
            }

            PlaybackMethod.SABR -> {
                trySabrClients(videoId, failureReasons)
            }

            PlaybackMethod.TV -> {
                TizenStreamResolver.resolve(videoId, failureReasons)
            }
        }

    @OptIn(UnstableApi::class)
    private suspend fun tryDirectClients(
        videoId: String,
        clients: List<YouTubeClient>,
        failureReasons: MutableList<String>,
        allowUntransformedN: Boolean = false,
        liveDetected: BooleanArray? = null,
        retryTimeoutOnce: Boolean = false,
        allowLowTrust: Boolean = false,
    ): VideoExtractionResult? =
        coroutineScope {
            val sts: Int? =
                if (clients.any { it.useSignatureTimestamp }) {
                    CipherDeobfuscator.ensureSignatureTimestamp()
                } else {
                    null
                }

            // Attested player requests yield direct URLs that survive GVS enforcement; unattested
            // ones get cut off roughly a minute in (served briefly, then 403 once the buffer drains).
            // Skipped when no client here can carry the token: minting only to cancel it left the
            // shared BotGuard session needing a fresh attestation on the next open.
            val mintable = clients.any { it.attestation == AttestationPlatform.WEB }
            val mint = if (mintable) async { WebPoTokenSession.mintBounded(videoId, allowLowTrust = allowLowTrust) } else null
            var mintLogged = false

            suspend fun awaitMint(): PoTokenResult? {
                // No WEB client in this list, so no mint was ever started: a null here is not
                // a mint failure, and logging it as one misled the batch-107 diagnosis.
                val deferred = mint ?: return null
                val result = deferred.await()
                if (!mintLogged) {
                    mintLogged = true
                    if (result == null) {
                        Log.w(TAG, "Direct clients for $videoId running without a PoToken (mint unavailable in time)")
                        PlayerDiagnostics.logWarning(
                            TAG,
                            "fast-path UNATTESTED $videoId (no PoToken in time) — direct URLs typically 403 ~60s in",
                        )
                    } else {
                        PlayerDiagnostics.logWarning(
                            TAG,
                            "fast-path attested $videoId (playerPot len=${result.playerRequestPoToken.length}, " +
                                "streamingPot len=${result.streamingDataPoToken.length})",
                        )
                    }
                }
                return result
            }

            for (client in clients) {
                try {
                    Log.d(TAG, "Trying ${client.clientName} v${client.clientVersion}")

                    // Only web-family clients get the BotGuard token. Injecting it into an
                    // ANDROID_VR/IOS /player request makes YT reject it outright (non-OK / empty
                    // streamingData), and stamping it on their URLs is worse than sending nothing:
                    // it is a claim GVS validates and refuses. Those clients are usable only for as
                    // long as YouTube serves them unattested.
                    val webAttested = client.attestation == AttestationPlatform.WEB
                    val clientPoToken = if (webAttested) awaitMint()?.playerRequestPoToken else null

                    suspend fun requestPlayer(): Result<PlayerResponse>? =
                        withTimeoutOrNull(PER_CLIENT_TIMEOUT_MS) {
                            // Force en-US extraction locale so the response is deterministic across regions.
                            // Route video extraction to www.youtube.com (not the music host): the main site
                            // serves usable ANDROID_VR direct adaptive formats that survive GVS enforcement,
                            // instead of the SABR-only responses the music endpoint returns for these clients.
                            YouTube.player(
                                videoId,
                                client = client,
                                signatureTimestamp = if (client.useSignatureTimestamp) sts else null,
                                poToken = clientPoToken,
                                localeOverride = YouTubeLocale.EXTRACTION,
                                apiUrl = YouTubeClient.API_URL_YOUTUBE,
                            )
                        }

                    var attempt = requestPlayer()
                    if (retryTimeoutOnce && attempt.isTimeout()) {
                        currentCoroutineContext().ensureActive()
                        Log.w(TAG, "${client.clientName} timed out for $videoId, retrying once")
                        PlayerDiagnostics.logWarning(TAG, "${client.clientName} timed out for $videoId, retrying once")
                        attempt = requestPlayer()
                    }
                    val playerResponse = attempt?.getOrNull()

                    if (playerResponse == null) {
                        failureReasons.add("${client.clientName}: timeout or null response")
                        PlayerDiagnostics.logWarning(
                            TAG,
                            "skip ${client.clientName} v${client.clientVersion}: null/timeout pot=${clientPoToken != null}",
                        )
                        continue
                    }

                    val status = playerResponse.playabilityStatus.status
                    if (status != "OK") {
                        val reason = playerResponse.playabilityStatus.reason
                        val tag = if (PlayabilityVerdict.isBotWall(reason)) "BOT_WALL" else "status=$status"
                        failureReasons.add("${client.clientName}: $tag, reason=$reason")
                        Log.w(TAG, "${client.clientName}: $tag, reason=$reason")
                        PlayerDiagnostics.logWarning(
                            TAG,
                            "skip ${client.clientName} v${client.clientVersion}: $tag reason=$reason pot=${clientPoToken != null}",
                        )
                        continue
                    }

                    if (playerResponse.isLiveNow()) {
                        // A genuine live manifest wins outright. Live playback never consumes the
                        // mint, so cancel it rather than letting coroutineScope wait it out.
                        val liveResult = playerResponse.toLiveResultOrNull(client)
                        if (liveResult != null) {
                            val webFail = deferWebTracking(videoId, client, mint.readyPlayerToken())
                            mint?.cancel()
                            return@coroutineScope liveResult.copy(webTracking = null, webTrackFail = webFail)
                        }

                        if (playerResponse.videoDetails?.isLive == true) {
                            liveDetected?.set(0, true)
                            failureReasons.add("${client.clientName}: live but no hls/dash manifest")
                            PlayerDiagnostics.logWarning(
                                TAG,
                                "skip ${client.clientName} v${client.clientVersion}: live (broadcasting), no hls/dash pot=${clientPoToken != null}",
                            )
                            continue
                        }
                        PlayerDiagnostics.logWarning(
                            TAG,
                            "${client.clientName} v${client.clientVersion}: live-flag but VOD (post-live-DVR/premiere) — using adaptive formats",
                        )
                    }

                    val adaptiveFormats = playerResponse.streamingData?.adaptiveFormats
                    if (adaptiveFormats.isNullOrEmpty()) {
                        failureReasons.add("${client.clientName}: no adaptive formats")
                        PlayerDiagnostics.logWarning(
                            TAG,
                            "skip ${client.clientName} v${client.clientVersion}: no adaptive formats pot=${clientPoToken != null}",
                        )
                        continue
                    }

                    val deciphered = adaptiveFormats.decipherWhilePossible(videoId)
                    primeRemoteNsigIfNeeded(videoId, deciphered)

                    val formatsWithUrl = deciphered.mapNotNull { it.withPlayableUrl(videoId, allowUntransformedN) }
                    // Capability probe : logs, per client,
                    // how many adaptive formats carried a direct URL vs how many resolved to a playable
                    // stream, plus whether the response is SABR-capable. Makes "why was this client skipped"
                    // visible in the in-app diagnostics instead of only when every client fails.
                    val rawUrlCount = adaptiveFormats.count { !it.url.isNullOrEmpty() }
                    val sabrPresent = !playerResponse.streamingData?.serverAbrStreamingUrl.isNullOrEmpty()
                    PlayerDiagnostics.logWarning(
                        TAG,
                        "probe ${client.clientName} v${client.clientVersion}: adaptive=${adaptiveFormats.size} " +
                            "hasUrl=$rawUrlCount resolvable=${formatsWithUrl.size} sabr=$sabrPresent " +
                            "pot=${clientPoToken != null} attest=${playerResponse.attestationDemand()}",
                    )
                    if (formatsWithUrl.isEmpty()) {
                        failureReasons.add("${client.clientName}: ${adaptiveFormats.size} formats, none resolvable (SABR-only)")
                        continue
                    }

                    val videoFormats = formatsWithUrl.filter { !it.isAudio && it.height != null && it.width != null }
                    val audioFormats = formatsWithUrl.filter { it.isAudio }
                    if (videoFormats.isEmpty()) {
                        failureReasons.add("${client.clientName}: no video formats with direct URLs")
                        continue
                    }
                    if (audioFormats.isEmpty()) {
                        failureReasons.add("${client.clientName}: no audio formats with direct URLs")
                        continue
                    }

                    val heights = videoFormats.mapNotNull { it.height }.distinct().sorted()
                    Log.i(
                        TAG,
                        "Success with ${client.clientName}: ${videoFormats.size} video (${heights.joinToString()}p), ${audioFormats.size} audio (direct URLs)",
                    )

                    val urlPot =
                        if (webAttested) {
                            withTimeoutOrNull(STREAM_POT_ATTACH_GRACE_MS) { awaitMint() }?.streamingDataPoToken
                        } else {
                            null
                        }
                    if (urlPot == null && webAttested) {
                        PlayerDiagnostics.logWarning(
                            TAG,
                            "${client.clientName} winner for $videoId shipping pot-less (mint not ready in " +
                                "${STREAM_POT_ATTACH_GRACE_MS}ms) — direct URLs may 403 ~60s in",
                        )
                    }

                    val webFail = deferWebTracking(videoId, client, mint.readyPlayerToken())
                    mint?.cancel()

                    return@coroutineScope VideoExtractionResult(
                        videoFormats = if (urlPot != null) videoFormats.map { it.withUrlPoToken(urlPot) } else videoFormats,
                        audioFormats = if (urlPot != null) audioFormats.map { it.withUrlPoToken(urlPot) } else audioFormats,
                        playerResponse = playerResponse,
                        usedClient = client,
                        sabrInfo = null,
                        webTracking = null,
                        webTrackFail = webFail,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failureReasons.add("${client.clientName}: exception=${e.javaClass.simpleName}: ${e.message}")
                    Log.w(TAG, "${client.clientName} failed: ${e.message}")
                    PlayerDiagnostics.logWarning(
                        TAG,
                        "skip ${client.clientName} v${client.clientVersion}: exception=${e.javaClass.simpleName}: ${e.message}",
                    )
                }
            }
            mint?.cancel()
            null
        }

    /**
     * Walks [SABR_CLIENTS] until one resolves a SABR session, so a bot wall on WEB alone does not
     * end the durable path.
     */
    private suspend fun trySabrClients(
        videoId: String,
        failureReasons: MutableList<String>,
        targetHeight: Int = 0,
        preferredCodec: String? = null,
        cpn: String = SabrCpn.generate(),
        reloadToken: String? = null,
    ): VideoExtractionResult? {
        val clients = SABR_CLIENTS.ungated()
        if (clients.isEmpty()) {
            // Returning null rather than retrying a refused client is what lets the forced-SABR
            // reload fall through to the full ladder in PlaybackLoadResolver, instead of re-minting
            // a BotGuard token for a client GVS has already refused twice.
            failureReasons.add("all SABR clients demoted: ${ClientGateTracker.gatedClients().joinToString()}")
            PlayerDiagnostics.logWarning(TAG, "SABR clients demoted for $videoId — falling through to the direct ladder")
            return null
        }
        for (client in clients) {
            tryWebSabr(
                videoId = videoId,
                failureReasons = failureReasons,
                targetHeight = targetHeight,
                preferredCodec = preferredCodec,
                cpn = cpn,
                reloadToken = reloadToken,
                client = client,
            )?.let { return it }
        }
        return null
    }

    /**
     * A web-like [client] + a WebView BotGuard PoToken + forced en-US locale. Produces a SABR
     * session (the response is SABR-only). Returns null when no visitorData / PoToken is available.
     */
    private suspend fun tryWebSabr(
        videoId: String,
        failureReasons: MutableList<String>,
        targetHeight: Int = 0,
        preferredCodec: String? = null,
        cpn: String = SabrCpn.generate(),
        reloadToken: String? = null,
        client: YouTubeClient = YouTubeClient.WEB,
    ): VideoExtractionResult? {
        val label = client.clientName
        try {
            val visitorData = WebPoTokenSession.sessionVisitorData()
            if (visitorData.isNullOrEmpty()) {
                failureReasons.add("$label: no visitorData")
                Log.w(TAG, "$label+SABR: no visitorData available")
                return null
            }
            val poToken = WebPoTokenSession.mintForVisitorData(videoId, visitorData)
            if (poToken == null) {
                failureReasons.add("$label: PoToken unavailable (WebView missing/broken?)")
                Log.w(TAG, "$label+SABR: PoToken mint returned null (WebView missing/broken?)")
                return null
            }
            val sts = withTimeoutOrNull(SIGNATURE_TIMESTAMP_WAIT_MS) { CipherDeobfuscator.ensureSignatureTimestamp() }

            val playerResult =
                withTimeoutOrNull(WEB_PLAYER_TIMEOUT_MS) {
                    YouTube
                        .playerWeb(
                            videoId = videoId,
                            signatureTimestamp = sts,
                            poToken = poToken.playerRequestPoToken,
                            visitorData = visitorData,
                            locale = YouTubeLocale.EXTRACTION,
                            cpn = cpn,
                            reloadToken = reloadToken,
                            client = client,
                            // Report 31: the anonymous SABR /player (visitor
                            // + PoToken only) is walled on every client while
                            // the official app plays the same video on the
                            // same network — send the signed-in session with
                            // it, the way fetchWebTracking already does.
                            withAuth = true,
                        )
                }
            val playerResponse = playerResult?.getOrNull()
            if (playerResponse == null) {
                // A timeout, an HTTP failure and a null body are three
                // different walls — report 32's "timeout/null" hid which one
                // the live /player actually hit.
                val cause =
                    when {
                        playerResult == null -> {
                            "timeout"
                        }

                        playerResult.exceptionOrNull() != null -> {
                            "error=${playerResult.exceptionOrNull()?.javaClass?.simpleName}: ${playerResult.exceptionOrNull()?.message}"
                        }

                        else -> {
                            "null response"
                        }
                    }
                failureReasons.add("$label: $cause")
                Log.w(TAG, "$label+SABR: player request $cause")
                return null
            }

            val status = playerResponse.playabilityStatus.status
            if (status != "OK") {
                val reason = playerResponse.playabilityStatus.reason
                val tag = if (PlayabilityVerdict.isBotWall(reason)) "BOT_WALL" else "status=$status"
                failureReasons.add("$label: $tag, reason=$reason")
                Log.w(TAG, "$label: $tag, reason=$reason")
                PlayerDiagnostics.logWarning(TAG, "skip $label v${client.clientVersion}: $tag reason=$reason pot=true")
                return null
            }

            // A live broadcast carries no adaptive formats — only HLS/DASH
            // manifests — so the SABR resolve below can never serve it, and
            // the live step never runs either because it waits for an
            // answering direct /player that never comes (report 32). The
            // authenticated WEB /player is the one request that
            // demonstrably answers for walled videos, so take the live
            // manifest straight from it before the VOD logic.
            if (playerResponse.isLiveNow()) {
                playerResponse.toLiveResultOrNull(client)?.let {
                    Log.w(
                        TAG,
                        "$label+SABR: live manifest via ${client.clientName} " +
                            "(hls=${it.liveHlsUrl != null}, dash=${it.liveDashUrl != null})",
                    )
                    PlayerDiagnostics.logWarning(
                        TAG,
                        "$label+SABR $videoId: live manifest hls=${it.liveHlsUrl != null} dash=${it.liveDashUrl != null}",
                    )
                    return it
                }
            }

            // StreamerContext.po_token carries the videoId-bound content token, which is what GVS
            // accepts here in practice.
            //
            // The visitor-bound token used to draw sabr.media_serving_enforcement_id_error, and the
            // reason is now understood: it was minted against the whole visitorData blob rather than
            // the 11-char visitor ID GVS actually binds to (see [VisitorId]). That binding is fixed,
            // so the streaming token is a candidate here too — but this path is the durable fallback
            // and the videoId-bound token is the one measured to work, so switching wants an
            // on-device A/B rather than an assumption.
            val resolved =
                if (targetHeight > 0) {
                    SabrUrlResolver.resolveForQuality(
                        playerResponse,
                        targetHeight = targetHeight,
                        preferredCodec = preferredCodec,
                        injectedPoToken = poToken.playerRequestPoToken,
                        injectedVisitorData = visitorData,
                    )
                } else {
                    SabrUrlResolver.resolve(
                        playerResponse,
                        preferredCodec = preferredCodec,
                        injectedPoToken = poToken.playerRequestPoToken,
                        injectedVisitorData = visitorData,
                    )
                }
            if (resolved == null) {
                failureReasons.add("$label: SABR resolve failed (no serverAbrStreamingUrl / formats)")
                Log.w(TAG, "$label+SABR: resolve failed — no serverAbrStreamingUrl/formats (pot/ustreamer present?)")
                return null
            }

            val withTransformedN =
                try {
                    val transformedUrl =
                        transformNParamInUrlOrNull(
                            videoId = videoId,
                            rawUrl = resolved.streamingUrl,
                            label = "SABR",
                        )
                    if (transformedUrl == null) {
                        Log.w(TAG, "$label+SABR: n-transform unavailable; using the server SABR endpoint unchanged")
                        resolved.copy(cpn = cpn)
                    } else {
                        resolved.copy(streamingUrl = transformedUrl, cpn = cpn)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "$label+SABR: n-transform threw; using the server SABR endpoint unchanged: ${e.message}")
                    resolved.copy(cpn = cpn)
                }

            // Carried on the session so the GVS streaming request and any later reload report the
            // same client that minted this response.
            val sabrInfo =
                withTransformedN.copy(
                    clientNameId = SabrClientIdentity.clientNameId(client),
                    clientVersion = client.clientVersion,
                    clientUserAgent = client.userAgent,
                )

            // The SABR session is the point of this path, but these formats are what playback falls
            // back to whenever it is not the preferred source — and they used to be handed over
            // exactly as the response carried them: signature-ciphered, n-untransformed and
            // carrying no `pot`. Anything that reached them answered 403 on the first byte range,
            // which is the "stream URLs keep expiring" loop. Resolve them the way the direct path
            // resolves its own, so the fallback is a real fallback.
            val rawFormats = playerResponse.streamingData?.adaptiveFormats.orEmpty()
            val decipheredFormats = rawFormats.decipherWhilePossible(videoId)
            primeRemoteNsigIfNeeded(videoId, decipheredFormats)
            val streamingPot = poToken.streamingDataPoToken.takeIf { it.isNotEmpty() }
            val playableFormats =
                decipheredFormats
                    .mapNotNull { it.withPlayableUrl(videoId, allowUntransformedN = false) }
                    .let { formats ->
                        if (streamingPot == null) formats else formats.map { it.withUrlPoToken(streamingPot) }
                    }
            val videoFormats = playableFormats.filter { !it.isAudio && it.height != null }
            val audioFormats = playableFormats.filter { it.isAudio }

            val heights = videoFormats.mapNotNull { it.height }.distinct().sorted()
            Log.w(
                TAG,
                "$label+PoToken (SABR) resolved: ${videoFormats.size} video (${heights.joinToString()}p), " +
                    "${audioFormats.size} audio, sabr=true, fallbackAttested=${streamingPot != null} " +
                    "(${playableFormats.size}/${rawFormats.size} formats playable)",
            )
            PlayerDiagnostics.logWarning(
                TAG,
                "$label+SABR $videoId: session ready, direct fallback ${playableFormats.size}/${rawFormats.size} " +
                    "formats pot=${streamingPot != null}",
            )

            // A SABR session on its own cannot play — the server never sends an init segment, so
            // ExoPlayer has nothing to sniff. Its direct formats are the whole value of this path,
            // and without them the ladder is better off trying the next client than returning a
            // result that only looks playable.
            if (videoFormats.isEmpty() || audioFormats.isEmpty()) {
                failureReasons.add("$label: SABR resolved but no format survived decipher/n-transform")
                PlayerDiagnostics.logWarning(
                    TAG,
                    "$label+SABR $videoId: session resolved but 0 playable formats — falling through to the next client",
                )
                return null
            }

            return VideoExtractionResult(
                videoFormats = videoFormats,
                audioFormats = audioFormats,
                playerResponse = playerResponse,
                usedClient = client,
                sabrInfo = sabrInfo,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failureReasons.add("$label: exception=${e.javaClass.simpleName}: ${e.message}")
            Log.w(TAG, "$label+SABR failed: ${e.message}")
            return null
        }
    }

    private suspend fun tryLiveClients(
        videoId: String,
        failureReasons: MutableList<String>,
    ): VideoExtractionResult? {
        val sts: Int? =
            if (LIVE_MANIFEST_CLIENTS.any { it.useSignatureTimestamp }) {
                CipherDeobfuscator.ensureSignatureTimestamp()
            } else {
                null
            }

        for (client in LIVE_MANIFEST_CLIENTS) {
            try {
                val playerResponse =
                    withTimeoutOrNull(PER_CLIENT_TIMEOUT_MS) {
                        YouTube
                            .player(
                                videoId,
                                client = client,
                                signatureTimestamp = if (client.useSignatureTimestamp) sts else null,
                                localeOverride = YouTubeLocale.EXTRACTION,
                                apiUrl = YouTubeClient.API_URL_YOUTUBE,
                            ).getOrNull()
                    }
                if (playerResponse == null) {
                    failureReasons.add("${client.clientName}(live): timeout or null response")
                    continue
                }
                if (playerResponse.playabilityStatus.status != "OK") {
                    failureReasons.add("${client.clientName}(live): status=${playerResponse.playabilityStatus.status}")
                    continue
                }
                playerResponse.toLiveResultOrNull(client)?.let {
                    Log.w(TAG, "Live manifest via ${client.clientName} (hls=${it.liveHlsUrl != null}, dash=${it.liveDashUrl != null})")
                    return it
                }
                failureReasons.add("${client.clientName}(live): no hls/dash manifest")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failureReasons.add("${client.clientName}(live): exception=${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return null
    }

    /** A status refusal is an answer; only a request that never came back is worth repeating. */
    private fun Result<PlayerResponse>?.isTimeout(): Boolean = this == null || exceptionOrNull() is TimeoutCancellationException

    private fun PlayerResponse.isLiveNow(): Boolean =
        LiveDetectionRules.isLiveNow(
            isLive = videoDetails?.isLive,
            isPostLiveDvr = videoDetails?.isPostLiveDvr,
            hasLiveStreamability = playabilityStatus.liveStreamability != null,
            hasHlsManifest = !streamingData?.hlsManifestUrl.isNullOrBlank(),
            hasAdaptiveFormats = !streamingData?.adaptiveFormats.isNullOrEmpty(),
        )

    private fun PlayerResponse.toLiveResultOrNull(client: YouTubeClient): VideoExtractionResult? {
        val hls = streamingData?.hlsManifestUrl?.takeIf { it.isNotBlank() }
        val dash = streamingData?.dashManifestUrl?.takeIf { it.isNotBlank() }
        if (hls == null && dash == null) return null
        return VideoExtractionResult(
            videoFormats = emptyList(),
            audioFormats = emptyList(),
            playerResponse = this,
            usedClient = client,
            sabrInfo = null,
            isLive = true,
            liveHlsUrl = hls,
            liveDashUrl = dash,
        )
    }

    /**
     * Whether YouTube asked this client to attest for this video, and under what shape.
     *
     * Reported next to the format counts because the two together are the question that matters
     * when a client's URLs are refused: was the response served freely, or did the server already
     * signal that it wanted proof this client could not give?
     */
    internal fun PlayerResponse.attestationDemand(): String {
        val renderer = attestation?.playerAttestationRenderer ?: return "none"
        val challenge = renderer.challenge ?: return "empty"
        val shared = if (renderer.useSharedChallenge == true) ",shared" else ""
        return "yes(${challenge.length}c$shared)"
    }

    /**
     * Gives every format of a ladder a real `url`, solving its `signatureCipher` first. Done for the
     * whole ladder before any of it is n-transformed, because [primeRemoteNsigIfNeeded] can only
     * sample formats that already have a URL. Formats that cannot be solved are dropped.
     */
    private suspend fun List<PlayerResponse.StreamingData.Format>.decipherWhilePossible(
        videoId: String,
    ): List<PlayerResponse.StreamingData.Format> {
        val ciphers =
            filter { it.url.isNullOrEmpty() }
                .mapNotNull { format -> SignatureCipher.parse(format.signatureCipher ?: format.cipher)?.let { format to it } }
                .toMap()
        val solved = if (ciphers.isEmpty()) emptyMap() else solveSignatures(videoId, ciphers.values.map { it.signature })
        val result =
            mapNotNull { format ->
                when {
                    !format.url.isNullOrEmpty() -> format
                    else -> ciphers[format]?.let { cipher -> solved[cipher.signature]?.let { format.copy(url = cipher.signedUrl(it)) } }
                }
            }
        // A silent drop here used to read as "0/N formats playable" with no cause
        // attached (#69); name how many formats needed solving and how many survived.
        val dropped = size - result.size
        if (dropped > 0) {
            Log.w(TAG, "decipher dropped $dropped/$size formats for $videoId (ciphers=${ciphers.size}, solved=${solved.size})")
        }
        return result
    }

    /**
     * The player script's own signature function first, then one remote batch for whatever it
     * left. Whether the local function works is a property of the script, not of the format, so
     * the first local failure sends the rest remote instead of retrying each (measured 2026-09-22:
     * ~30 failing WebView round trips blew the extraction budget).
     *
     * The remote decoder is asked for the local script's hash: a signature only matches the
     * timestamp the `/player` request carried, which is the local script's. When there is no
     * local script at all, the `/player` request carried no timestamp, so the decoder's
     * latest player is the right one to solve against.
     */
    private suspend fun solveSignatures(
        videoId: String,
        signatures: List<String>,
    ): Map<String, String> {
        val wanted = signatures.distinct()
        val solved = mutableMapOf<String, String>()
        for (signature in wanted) {
            solved[signature] = CipherDeobfuscator.deobfuscateSignature(signature) ?: break
        }
        val missing = wanted.filterNot { it in solved }
        if (missing.isEmpty()) return solved
        val playerHash = CipherDeobfuscator.playerHash()
        if (playerHash == null) {
            // No local player script, so the /player request carried no signature
            // timestamp either — its signatures are against YouTube's current player,
            // which is what the decoder's latest id is. Solving against that instead
            // of giving up keeps ciphered formats playable when the local script is
            // missing (#69).
            Log.w(TAG, "No local player script for $videoId; solving signatures against the decoder's latest player")
            solved += PipePipeNsigDecoder.decodeSignatures(missing)
            return solved
        }
        Log.w(TAG, "Local signature function unavailable for $videoId; solving ${missing.size} remotely for $playerHash")
        PlayerDiagnostics.logWarning(TAG, "local signature FAILED for $videoId — remote decoder for player $playerHash")
        solved += PipePipeNsigDecoder.decodeSignatures(missing, playerId = playerHash)
        return solved
    }

    /**
     * GVS's non-SABR stream protection: the streaming (visitor-bound) PoToken rides on the
     * direct URL as the `pot` query param. Appended after the n-transform so the transform
     * never sees or reorders it.
     */
    private fun PlayerResponse.StreamingData.Format.withUrlPoToken(pot: String): PlayerResponse.StreamingData.Format {
        val rawUrl = url ?: return this
        if (POT_PARAM_REGEX.containsMatchIn(rawUrl)) return this
        val separator = if ("?" in rawUrl) "&" else "?"
        return copy(url = "$rawUrl${separator}pot=${Uri.encode(pot)}")
    }

    private suspend fun PlayerResponse.StreamingData.Format.withPlayableUrl(
        videoId: String,
        allowUntransformedN: Boolean,
    ): PlayerResponse.StreamingData.Format? {
        val rawUrl = url ?: return this
        val transformed = transformNParamInUrlOrNull(videoId, rawUrl, "itag=$itag")
        return when {
            transformed != null -> {
                copy(url = transformed)
            }

            allowUntransformedN -> {
                Log.w(TAG, "Using untransformed n URL as last-resort fallback for $videoId itag=$itag; playback may throttle")
                this
            }

            else -> {
                Log.w(TAG, "Rejecting untransformed n URL for $videoId itag=$itag; direct playback would likely throttle")
                null
            }
        }
    }

    private suspend fun transformNParamInUrlOrNull(
        videoId: String,
        rawUrl: String,
        label: String,
    ): String? {
        val rawN = extractNParameter(rawUrl) ?: return rawUrl
        return try {
            val transformed =
                localNTransformOrNull(rawUrl, rawN)
                    ?: PipePipeNsigDecoder
                        .deobfuscateUrl(rawUrl)
                        ?.takeIf { isNParameterTransformed(rawN, it) }
            if (transformed != null) {
                Log.d(TAG, "Applied n-transform for $videoId $label")
            }
            transformed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "n-transform failed for $videoId $label: ${e.message}")
            null
        }
    }

    /** The player script's own n function. Null when it could not transform [rawN]. */
    private suspend fun localNTransformOrNull(
        rawUrl: String,
        rawN: String,
    ): String? =
        CipherDeobfuscator
            .transformNParamInUrl(rawUrl)
            .takeIf { isNParameterTransformed(rawN, it) }

    /**
     * Batch-primes the remote n decoder, but only once the local decoder has failed on a
     * representative URL.
     *
     * The remote decoder is the fallback in [transformNParamInUrlOrNull], yet priming it
     * unconditionally put a third-party round trip in front of *every* extraction — the dominant
     * cost of the InnerTube leg on a cold start. Probing locally first keeps that round trip off
     * the path to first frame whenever the local decoder can do the job, while still collapsing the
     * whole ladder into one request when it genuinely is needed. The probe is not wasted work:
     * the local decoder caches its player script, so it warms exactly what the per-format loop
     * below is about to ask for.
     */
    private suspend fun primeRemoteNsigIfNeeded(
        videoId: String,
        adaptiveFormats: List<PlayerResponse.StreamingData.Format>,
    ) {
        val urls = adaptiveFormats.mapNotNull { it.url }
        val sample =
            urls.firstNotNullOfOrNull { url ->
                extractNParameter(url)?.let { url to it }
            } ?: return
        if (localNTransformOrNull(sample.first, sample.second) != null) return
        Log.w(TAG, "Local n-decoder failed for $videoId; priming remote decoder for ${urls.size} formats")
        PlayerDiagnostics.logWarning(
            TAG,
            "local n-decoder FAILED for $videoId" +
                (CipherDeobfuscator.unparseablePlayerHash?.let { " — player JS $it is unreadable to this build" } ?: "") +
                " — falling back to the remote decoder",
        )
        PipePipeNsigDecoder.prefetch(urls)
    }

    private fun isNParameterTransformed(
        rawN: String,
        candidateUrl: String,
    ): Boolean {
        if (candidateUrl.isBlank()) return false
        val candidateN = extractNParameter(candidateUrl) ?: return candidateUrl != rawN
        return candidateN != rawN
    }

    private fun extractNParameter(url: String): String? =
        try {
            Uri.parse(url).getQueryParameter("n")
        } catch (_: Exception) {
            N_PARAM_REGEX.find(url)?.groupValues?.getOrNull(1)
        }
}
