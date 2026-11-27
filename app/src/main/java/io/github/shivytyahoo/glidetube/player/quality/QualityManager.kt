package io.github.shivytyahoo.glidetube.player.quality

import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import io.github.shivytyahoo.glidetube.player.config.PlayerConfig
import io.github.shivytyahoo.glidetube.player.config.VideoSizeCap
import io.github.shivytyahoo.glidetube.player.config.adaptiveTrackSelectorDefaults
import io.github.shivytyahoo.glidetube.player.error.StreamDenialClassifier
import io.github.shivytyahoo.glidetube.player.state.EnhancedPlayerState
import io.github.shivytyahoo.glidetube.player.state.QualityOption
import io.github.shivytyahoo.glidetube.player.stream.ClientGateTracker
import io.github.shivytyahoo.glidetube.player.stream.VideoCodecUtils
import io.github.shivytyahoo.glidetube.utils.ShivLog
import kotlinx.coroutines.flow.MutableStateFlow
import org.schabi.newpipe.extractor.stream.VideoStream

@UnstableApi
class QualityManager(
    private val bandwidthMeter: DefaultBandwidthMeter?,
    private val trackSelector: DefaultTrackSelector?,
    private val videoSizeCap: VideoSizeCap,
    private val stateFlow: MutableStateFlow<EnhancedPlayerState>,
    // Receives the stream to switch to and the position playback must resume from.
    private val onQualitySwitch: (VideoStream, Long) -> Unit,
    // A weak device ranks H.264 first whatever the preference says; on TV the codec families
    // rank by the tallest height they decode. Both default to today's behaviour.
    private val weakDevice: Boolean = false,
    private val familyOrder: List<String>? = null,
    // The track selector's mime order on TV, so a later quality switch does not silently
    // revert it to the phone order.
    private val tvMimeTypes: Array<String>? = null,
) {
    companion object {
        private const val TAG = "QualityManager"
        private const val MIN_QUALITY_SWITCH_INTERVAL_MS = 10_000L

        fun normalizeQualityHeight(rawHeight: Int): Int = VideoCodecUtils.normalizeQualityHeight(rawHeight)
    }

    // Quality mode tracking
    var isAdaptiveQualityEnabled = true
        private set
    var manualQualityHeight: Int? = null
        private set

    // Adaptive quality monitoring
    var lastBandwidthCheckTime = 0L
        private set
    var lastAdaptiveQualityHeight = 0
        private set
    var consecutiveBufferingCount = 0
        private set

    private var lastQualitySwitchTime = 0L

    /**
     * The loaded source is a multi-representation DASH manifest — a generated ladder, or
     * YouTube's own manifest — so a quality change can move within it instead of reloading.
     * A player-response `dashManifestUrl` alone does not prove this: a pinned load bypasses
     * the native manifest for a generated single-representation one.
     */
    var isMultiRungDashSource = false

    /** Media3 is adapting over a ladder source, so Auto's own bandwidth checks stand down. */
    var isAdaptiveLadderActive = false

    // Available streams
    private var availableVideoStreams: List<VideoStream> = emptyList()
    private var currentVideoStream: VideoStream? = null

    var preferredCodecKey: String? = null

    // Track failed streams
    private val failedStreamUrls = mutableSetOf<String>()
    private val failedStreamVariants = mutableSetOf<String>()
    var streamErrorCount = 0
        private set

    /**
     * Update available video streams.
     */
    fun setAvailableStreams(streams: List<VideoStream>) {
        availableVideoStreams = streams.distinctBy { it.getContent() }.sortedByDescending { qualityHeight(it) }
    }

    /**
     * Set the current video stream.
     */
    fun setCurrentStream(stream: VideoStream?) {
        currentVideoStream = stream
        lastAdaptiveQualityHeight = stream?.let(::qualityHeight) ?: 0
    }

    /**
     * Get the current video stream.
     */
    fun getCurrentStream(): VideoStream? = currentVideoStream

    /**
     * Reset quality state for a new video.
     * Defaults to adaptive mode; call setManualMode() after if user has non-AUTO preference.
     */
    fun resetForNewVideo() {
        failedStreamUrls.clear()
        failedStreamVariants.clear()
        streamErrorCount = 0
        currentVideoStream = null
        isAdaptiveQualityEnabled = true
        manualQualityHeight = null
        consecutiveBufferingCount = 0
        lastQualitySwitchTime = 0L
        isMultiRungDashSource = false
        isAdaptiveLadderActive = false
        applyAdaptiveTrackSelectorDefaults()
    }

    /**
     * Set manual (fixed) quality mode. Called when user has a non-AUTO quality preference.
     * Disables adaptive quality switching.
     */
    fun setManualMode(height: Int) {
        isAdaptiveQualityEnabled = false
        manualQualityHeight = normalizeQualityHeight(height)
        Log.d(TAG, "Manual quality mode set: ${manualQualityHeight}p (adaptive disabled)")
    }

    /**
     * Mark a stream URL as failed.
     */
    fun markStreamFailed(url: String) {
        failedStreamUrls.add(url)
        availableVideoStreams.firstOrNull { streamKey(it) == url }?.let { stream ->
            failedStreamVariants.add(failureVariantKey(stream))
        }
        streamErrorCount++
        Log.w(TAG, "Stream marked as failed: $url - Error count: $streamErrorCount")
    }

    /**
     * Reset stream error count.
     */
    fun resetStreamErrors() {
        streamErrorCount = 0
    }

    /**
     * Check if a stream URL has failed.
     */
    fun hasStreamFailed(url: String): Boolean {
        if (failedStreamUrls.contains(url)) return true
        return availableVideoStreams
            .firstOrNull { streamKey(it) == url }
            ?.let { failedStreamVariants.contains(failureVariantKey(it)) }
            ?: false
    }

    /**
     * Get all working streams (streams that haven't failed).
     */
    fun getWorkingStreams(): List<VideoStream> =
        availableVideoStreams.filter {
            !failedStreamUrls.contains(streamKey(it)) &&
                !failedStreamVariants.contains(failureVariantKey(it))
        }

    private fun qualityHeight(stream: VideoStream): Int = normalizeQualityHeight(VideoCodecUtils.qualityHeightFromStream(stream))

    /**
     * Select smart initial quality based on bandwidth.
     */
    fun selectSmartInitialQuality(): VideoStream? {
        val estimatedBandwidth = bandwidthMeter?.bitrateEstimate ?: PlayerConfig.INITIAL_BANDWIDTH_ESTIMATE
        val smartStream =
            AutoQualityPolicy.cappedInitialPick(getWorkingStreams(), estimatedBandwidth, preferredCodecKey, weakDevice, familyOrder)
        Log.d(
            TAG,
            "Smart quality selection: bandwidth=${estimatedBandwidth / 1_000_000}Mbps, selected=${smartStream?.let(::qualityHeight)}p",
        )
        return smartStream
    }

    /**
     * Cap Media3's rendered rung at [stream]'s height for a fresh Auto load. The anchor alone
     * cannot lower it: Media3 picks the ladder's initial rung from the live bandwidth estimate,
     * which a burst phase inflates far above the sustained link. The anchor is moved to the
     * capped pick too, so the ceiling, the logged anchor and the shown quality agree.
     */
    fun applyStartLowConstraint(stream: VideoStream?) {
        if (!isAdaptiveQualityEnabled || stream == null) return
        if (adaptiveLadder(stream).size < 2) return
        currentVideoStream = stream
        lastAdaptiveQualityHeight = qualityHeight(stream)
        switchDashQualitySeamlessly(stream.height, qualityHeight(stream))
    }

    /** The rungs around [anchor] whether a quality is pinned or not. A pinned load plays the
     * same ladder with the pin as a track-selector constraint, so switches within it need no reload. */
    fun pinnedLadder(anchor: VideoStream?): List<VideoStream> = AdaptiveLadder.rungs(anchor, getWorkingStreams())

    /** The qualities Auto hands to Media3 around [anchor], or none while a quality is pinned. */
    fun adaptiveLadder(anchor: VideoStream?): List<VideoStream> = if (isAdaptiveQualityEnabled) pinnedLadder(anchor) else emptyList()

    /**
     * Constrain the track selector to the pinned height. No-op unless a quality is pinned and
     * its ladder has at least two rungs: a single-representation load already plays only the
     * pin, and a switch before the load lands has nothing to constrain yet.
     */
    fun applyPinConstraint() {
        val pinHeight = manualQualityHeight ?: return
        if (isAdaptiveQualityEnabled) return
        val anchor = currentVideoStream ?: return
        if (pinnedLadder(anchor).size < 2) return
        switchDashQualitySeamlessly(anchor.height, pinHeight)
    }

    /**
     * Whether [targetStream] is a rung of the loaded multi-rung manifest. Call before adopting
     * the target as the current stream: the check anchors on the loaded ladder's family, which
     * the current stream still carries. A native manifest carries every format, so any
     * available stream is a rung of it; a generated ladder carries only its own rungs, so the
     * target must be one of them. A codec-family change, or a height the loaded family does
     * not carry, still needs a reload.
     */
    fun isRungOfLoadedLadder(targetStream: VideoStream): Boolean {
        if (!isMultiRungDashSource) return false
        if (!isAdaptiveLadderActive) return true
        val anchor = currentVideoStream ?: return false
        return pinnedLadder(anchor).any { streamKey(it) == streamKey(targetStream) }
    }

    /**
     * Build quality options list for UI.
     */
    fun buildQualityOptions(): List<QualityOption> {
        return listOf(
            QualityOption(height = 0, label = "Auto", bitrate = 0L),
        ) +
            availableVideoStreams
                .groupBy { "${qualityHeight(it)}_${VideoCodecUtils.codecKeyFromStream(it)}" }
                .values
                .mapNotNull { streams ->
                    val bestStream = streams.maxByOrNull { it.bitrate } ?: return@mapNotNull null
                    val height = qualityHeight(bestStream)
                    val codecKey = VideoCodecUtils.codecKeyFromStream(bestStream)
                    val fps = VideoCodecUtils.frameRateFromStream(bestStream)
                    QualityOption(
                        height = height,
                        label = "${VideoCodecUtils.qualityLabelWithFrameRate(height, fps)} ${VideoCodecUtils.codecLabelFromKey(codecKey)}",
                        bitrate = bestStream.bitrate.toLong(),
                        codecKey = codecKey,
                        streamKey = streamKey(bestStream),
                    )
                }.sortedWith(
                    compareByDescending<QualityOption> { it.height }
                        .thenBy { VideoCodecUtils.playbackCodecRank(it.codecKey) }
                        .thenByDescending { it.bitrate },
                )
    }

    /**
     * Switch quality by height. Height 0 means auto (adaptive).
     */
    fun switchQualityByHeight(
        height: Int,
        currentPosition: Long,
        videoId: String? = null,
    ): Boolean {
        // Height 0 means Auto (adaptive quality)
        if (height == 0) {
            enableAdaptiveQuality(currentPosition)
            return true
        }

        // Check if we're already at this quality
        if (manualQualityHeight == height) {
            Log.d(TAG, "Already at ${height}p, no change needed")
            return false
        }

        // Disable adaptive and set fixed quality
        isAdaptiveQualityEnabled = false
        manualQualityHeight = height

        Log.d(TAG, "Switching to FIXED quality: ${height}p")

        // Find the stream matching this height
        val targetStream =
            getWorkingStreams()
                .filter { qualityHeight(it) == height }
                .minWithOrNull(
                    compareBy<VideoStream> { VideoCodecUtils.codecRankWithPreference(it, preferredCodecKey, weakDevice, familyOrder) }
                        .thenByDescending { it.bitrate },
                )
        if (targetStream == null) {
            Log.w(TAG, "No stream found for ${height}p, available: ${availableVideoStreams.map { qualityHeight(it) }}")
            return false
        }

        val targetHeight = qualityHeight(targetStream)
        // Decide the path before adopting the target: the rung check anchors on the loaded
        // ladder's family, which the current stream still carries.
        val fromStream = currentVideoStream
        val seamless = isRungOfLoadedLadder(targetStream)
        currentVideoStream = targetStream
        if (seamless) {
            switchDashQualitySeamlessly(targetStream.height, targetHeight)
        } else {
            onQualitySwitch(targetStream, currentPosition)
        }
        logQualitySwitch(videoId, fromStream, targetStream, viaSeamless = seamless)

        stateFlow.value =
            stateFlow.value.copy(
                currentQuality = targetHeight,
                effectiveQuality = targetHeight,
                currentQualityKey = streamKey(targetStream),
            )
        return true
    }

    fun switchQuality(
        option: QualityOption,
        currentPosition: Long,
        videoId: String? = null,
    ): Boolean {
        option.streamKey?.let { return switchQualityByStreamKey(it, currentPosition, videoId) }
        return switchQualityByHeight(option.height, currentPosition, videoId)
    }

    fun switchQualityByStreamKey(
        streamKey: String,
        currentPosition: Long,
        videoId: String? = null,
    ): Boolean {
        val targetStream = availableVideoStreams.firstOrNull { streamKey(it) == streamKey }
        if (targetStream == null) {
            Log.w(TAG, "No stream found for key=$streamKey")
            return false
        }

        val targetHeight = qualityHeight(targetStream)
        val targetCodec = VideoCodecUtils.codecKeyFromStream(targetStream)
        // Decide the path before adopting the target: the rung check anchors on the loaded
        // ladder's family, which the current stream still carries.
        val fromStream = currentVideoStream
        val seamless = isRungOfLoadedLadder(targetStream)
        isAdaptiveQualityEnabled = false
        manualQualityHeight = targetHeight
        currentVideoStream = targetStream
        Log.d(TAG, "Switching to FIXED quality: ${targetHeight}p ${VideoCodecUtils.codecLabelFromKey(targetCodec)}")

        if (seamless) {
            switchDashQualitySeamlessly(targetStream.height, targetHeight)
        } else {
            onQualitySwitch(targetStream, currentPosition)
        }
        logQualitySwitch(videoId, fromStream, targetStream, viaSeamless = seamless)

        stateFlow.value =
            stateFlow.value.copy(
                currentQuality = targetHeight,
                effectiveQuality = targetHeight,
                currentQualityKey = streamKey,
            )
        return true
    }

    /**
     * Enable adaptive quality mode. A pinned single-stream source reloads whenever a ladder can
     * take over from it, not only when the starting quality differs — but when the ladder is
     * already loaded, lifting the pin constraint is the whole switch and no reload is needed.
     */
    private fun enableAdaptiveQuality(currentPosition: Long) {
        val alreadyAdapting = isAdaptiveQualityEnabled && isAdaptiveLadderActive
        isAdaptiveQualityEnabled = true
        manualQualityHeight = null

        Log.d(TAG, "Enabling adaptive quality mode")
        applyAdaptiveTrackSelectorDefaults()
        if (alreadyAdapting) {
            stateFlow.value = stateFlow.value.copy(currentQuality = 0, currentQualityKey = null)
            return
        }

        // The ladder is already loaded — a pinned ladder load, or a native multi-rung manifest —
        // so Media3 adapts over it from the current rung once the pin constraint is lifted.
        // Reloading here would only discard the buffer.
        if (isMultiRungDashSource) {
            stateFlow.value = stateFlow.value.copy(currentQuality = 0, currentQualityKey = null)
            lastAdaptiveQualityHeight = currentVideoStream?.let(::qualityHeight) ?: 0
            return
        }

        val estimatedBandwidth = bandwidthMeter?.bitrateEstimate ?: PlayerConfig.INITIAL_BANDWIDTH_ESTIMATE
        val targetStream =
            AutoQualityPolicy.initialPick(getWorkingStreams(), estimatedBandwidth, preferredCodecKey, weakDevice, familyOrder)
        Log.d(TAG, "Auto quality: estimated bandwidth ${estimatedBandwidth / 1_000_000}Mbps -> ${targetStream?.let(::qualityHeight)}p")

        val heightChanges = targetStream != null && qualityHeight(targetStream) != currentVideoStream?.let(::qualityHeight)
        if (targetStream != null && (heightChanges || adaptiveLadder(targetStream).isNotEmpty())) {
            val selectedHeight = qualityHeight(targetStream)
            currentVideoStream = targetStream
            onQualitySwitch(targetStream, currentPosition)

            stateFlow.value =
                stateFlow.value.copy(
                    currentQuality = 0,
                    effectiveQuality = selectedHeight,
                    currentQualityKey = null,
                )
            lastAdaptiveQualityHeight = selectedHeight
        } else {
            stateFlow.value = stateFlow.value.copy(currentQuality = 0, currentQualityKey = null)
            lastAdaptiveQualityHeight = currentVideoStream?.let(::qualityHeight) ?: 0
        }
    }

    /**
     * Check if we can upgrade quality based on current bandwidth.
     * Called periodically when playback is smooth.
     */
    fun checkAdaptiveQualityUpgrade(
        currentPosition: Long,
        bufferedAheadMs: Long = -1L,
    ) {
        if (!isAdaptiveQualityEnabled) return
        val current = currentVideoStream ?: return
        if (isAdaptiveLadderActive) {
            // Media3 owns the ladder, but the load-time ceiling caps it at the start-low rung:
            // raise the ceiling one rung only once the buffer proves the link sustains more.
            // A pinned quality never reaches here (guard above), so this cannot raise a pin.
            if (bufferedAheadMs < PlayerConfig.ABR_MIN_BUFFER_FOR_QUALITY_INCREASE_MS) return
            val estimatedBandwidth = bandwidthMeter?.bitrateEstimate ?: return
            val ceilingStream =
                getWorkingStreams()
                    .filter { qualityHeight(it) == lastAdaptiveQualityHeight }
                    .minWithOrNull(
                        compareBy<VideoStream> { VideoCodecUtils.codecRankWithPreference(it, preferredCodecKey, weakDevice, familyOrder) }
                            .thenByDescending { it.bitrate },
                    ) ?: current
            val next =
                AutoQualityPolicy.stepUp(ceilingStream, getWorkingStreams(), estimatedBandwidth, preferredCodecKey, weakDevice, familyOrder)
                    ?: return
            val nextHeight = qualityHeight(next)
            if (nextHeight == lastAdaptiveQualityHeight) return
            val now = System.currentTimeMillis()
            if (now - lastQualitySwitchTime < MIN_QUALITY_SWITCH_INTERVAL_MS) return
            currentVideoStream = next
            lastAdaptiveQualityHeight = nextHeight
            lastQualitySwitchTime = now
            switchDashQualitySeamlessly(next.height, nextHeight)
            Log.d(
                TAG,
                "Adaptive UPGRADE (ladder): ${qualityHeight(ceilingStream)}p -> ${nextHeight}p (bufferedAhead=${bufferedAheadMs}ms)",
            )
            stateFlow.value =
                stateFlow.value.copy(
                    currentQuality = 0,
                    effectiveQuality = nextHeight,
                    currentQualityKey = null,
                )
            return
        }
        val estimatedBandwidth = bandwidthMeter?.bitrateEstimate ?: return
        val next =
            AutoQualityPolicy.stepUp(current, getWorkingStreams(), estimatedBandwidth, preferredCodecKey, weakDevice, familyOrder) ?: return
        Log.d(
            TAG,
            "Adaptive UPGRADE: ${qualityHeight(current)}p -> ${qualityHeight(next)}p (bandwidth: ${estimatedBandwidth / 1_000_000}Mbps)",
        )
        performAdaptiveQualitySwitch(next, currentPosition)
    }

    /**
     * One stable telemetry line per stall event, kept in-app for the Diagnostics report.
     * The stream is passed in because the ladder-build call sites log the anchor stream,
     * which the manager may not have adopted yet.
     */
    fun logPlayStall(
        videoId: String?,
        stream: VideoStream?,
        bufferedAheadMs: Long,
    ) {
        val itag =
            stream?.itagItem?.id?.toString()
                ?: runCatching { stream?.id }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: "?"
        ShivLog.i(
            "playStall id=${videoId ?: "?"} itag=$itag h=${stream?.let(::qualityHeight) ?: 0}p " +
                "br=${stream?.bitrate?.toLong() ?: -1L} est=${bandwidthMeter?.bitrateEstimate ?: -1L} " +
                "bufAheadMs=$bufferedAheadMs rebuffers=$consecutiveBufferingCount " +
                "ladder=${if (isAdaptiveLadderActive) "y" else "n"} pinned=${if (isAdaptiveQualityEnabled) "n" else "y"}",
        )
    }

    /**
     * One stable telemetry line per quality switch, kept in-app for the Diagnostics report.
     * The effective rung is the target stream: on a reload it is the stream the reload loads,
     * on a seamless switch it is the rung the selector is constrained to. `src` is the loaded
     * source's shape, so a future report can tell a real step-down from a state-only one.
     */
    fun logQualitySwitch(
        videoId: String?,
        fromStream: VideoStream?,
        targetStream: VideoStream?,
        viaSeamless: Boolean,
    ) {
        val itag =
            targetStream?.itagItem?.id?.toString()
                ?: runCatching { targetStream?.id }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: "?"
        ShivLog.i(
            "qualitySwitch id=${videoId ?: "?"} from=${fromStream?.let(::qualityHeight) ?: 0}p " +
                "to=${targetStream?.let(::qualityHeight) ?: 0}p itag=$itag " +
                "via=${if (viaSeamless) "seamless" else "reload"} src=${if (isMultiRungDashSource) "ladder" else "single"}",
        )
    }

    /**
     * Check if we need to downgrade quality due to buffering or low bandwidth.
     */
    fun checkAdaptiveQualityDowngrade(
        forceCheck: Boolean,
        currentPosition: Long,
        videoId: String? = null,
        bufferedAheadMs: Long = -1L,
    ) {
        // A ladder source stalls too: Media3's ABR keeps the top rung while the link cannot
        // sustain it, so the stall safety net (forceCheck) must act even while the ladder
        // is active. Only the periodic bandwidth check stands down to Media3.
        if (isAdaptiveLadderActive && !forceCheck) return
        // A pinned quality is the user's choice, so it is not moved by the periodic
        // bandwidth check — but a stall episode is ground truth that the link cannot
        // sustain this rung: after repeated rebuffers it steps down one rung instead
        // of stalling forever at the pinned height. The pin is kept at the new rung;
        // the next video starts from the user's default quality as before.
        if (!isAdaptiveQualityEnabled) {
            if (!forceCheck) return
            logPlayStall(videoId, currentVideoStream, bufferedAheadMs)
            val current = currentVideoStream ?: return
            // A stall on a gated client is a refusal, not bandwidth: every rung of that
            // client is refused the same way, so stepping down only replays the wall.
            // The refusal swapper owns this case; the net stands down here.
            if (ClientGateTracker.isGated(StreamDenialClassifier.clientOf(current.getContent()))) return
            val lower = AutoQualityPolicy.stepDown(current, getWorkingStreams(), preferredCodecKey, weakDevice, familyOrder) ?: return
            Log.d(TAG, "Pinned DOWNGRADE (buffering): ${qualityHeight(current)}p -> ${qualityHeight(lower)}p")
            switchQualityByStreamKey(streamKey(lower), currentPosition)
            return
        }
        val current = currentVideoStream ?: return
        val streams = getWorkingStreams()
        if (forceCheck) {
            val estimatedBandwidth = bandwidthMeter?.bitrateEstimate
            val lower =
                estimatedBandwidth?.let { AutoQualityPolicy.jumpDown(current, streams, it, preferredCodecKey, weakDevice, familyOrder) }
                    ?: AutoQualityPolicy.stepDown(current, streams, preferredCodecKey, weakDevice, familyOrder)
            // No lower rung exists: already at the bottom, so the stall is the link, not the
            // choice. Log it so the report shows the net fired and had nowhere to go.
            logPlayStall(videoId, current, bufferedAheadMs)
            if (lower == null) return
            Log.d(TAG, "Adaptive DOWNGRADE (buffering): ${qualityHeight(current)}p -> ${qualityHeight(lower)}p")
            // A stall is ground truth, so the stall path skips the switch debounce: the
            // 19:34:48 stall in Report 55 logged but did not switch because the debounce held
            // it 3 s after the previous switch. One decision per 3-stall episode (the caller
            // resets the count after each forceCheck) keeps this from thrashing.
            performAdaptiveQualitySwitch(lower, currentPosition, force = true, videoId = videoId)
            return
        }
        val estimatedBandwidth = bandwidthMeter?.bitrateEstimate ?: return
        val lower = AutoQualityPolicy.jumpDown(current, streams, estimatedBandwidth, preferredCodecKey, weakDevice, familyOrder) ?: return
        logPlayStall(videoId, current, bufferedAheadMs)
        Log.d(
            TAG,
            "Adaptive DOWNGRADE (low bandwidth): ${qualityHeight(current)}p -> ${qualityHeight(lower)}p " +
                "(bandwidth: ${estimatedBandwidth / 1_000_000}Mbps)",
        )
        performAdaptiveQualitySwitch(lower, currentPosition, videoId = videoId)
    }

    /**
     * Perform the actual quality switch for adaptive mode.
     * Includes time-based debounce to prevent rapid switching; a stall ([force]) skips it.
     */
    private fun performAdaptiveQualitySwitch(
        targetStream: VideoStream,
        currentPosition: Long,
        force: Boolean = false,
        videoId: String? = null,
    ) {
        val targetHeight = qualityHeight(targetStream)
        // Don't switch if we just switched recently (same height debounce)
        if (targetHeight == lastAdaptiveQualityHeight) {
            Log.d(TAG, "Adaptive: Skipping switch, already at ${targetHeight}p")
            return
        }

        val now = System.currentTimeMillis()
        if (!force && now - lastQualitySwitchTime < MIN_QUALITY_SWITCH_INTERVAL_MS) {
            Log.d(
                TAG,
                "Adaptive: Debouncing quality switch (${now - lastQualitySwitchTime}ms since last switch, min=${MIN_QUALITY_SWITCH_INTERVAL_MS}ms)",
            )
            return
        }

        // Decide the path before adopting the target: the rung check anchors on the loaded
        // ladder's family, which the current stream still carries.
        val fromStream = currentVideoStream
        val seamless = isRungOfLoadedLadder(targetStream)
        currentVideoStream = targetStream
        lastAdaptiveQualityHeight = targetHeight
        lastQualitySwitchTime = now

        if (seamless) {
            // The rung is already in the loaded manifest: constrain the selector and keep the
            // buffer. Reloading here would discard it for no gain — the ladder does not change.
            switchDashQualitySeamlessly(targetStream.height, targetHeight)
        } else {
            // The ladder source survives a reload, so move the selector ceiling to the new rung
            // first or Media3 keeps rendering the old one.
            if (isAdaptiveLadderActive) {
                switchDashQualitySeamlessly(targetStream.height, targetHeight)
            }
            onQualitySwitch(targetStream, currentPosition)
        }
        logQualitySwitch(videoId, fromStream, targetStream, viaSeamless = seamless)

        stateFlow.value =
            stateFlow.value.copy(
                currentQuality = 0,
                effectiveQuality = targetHeight,
                currentQualityKey = null,
            )
    }

    /**
     * Switch quality seamlessly via TrackSelector when using a DASH source.
     * This constrains the maximum video height, letting ExoPlayer
     * do a smooth in-buffer quality switch without interrupting playback.
     */
    private fun switchDashQualitySeamlessly(
        maxHeight: Int,
        qualityHeight: Int,
    ) {
        trackSelector?.let { selector ->
            val minHeight = (maxHeight - 1).coerceAtLeast(0)
            val params =
                selector
                    .buildUponParameters()
                    .setPreferredVideoMimeTypes(*(tvMimeTypes ?: PlayerConfig.PREFERRED_VIDEO_MIME_TYPES))
                    .setMinVideoSize(0, minHeight)
                    .setMaxVideoSize(Int.MAX_VALUE, maxHeight)
                    .setForceHighestSupportedBitrate(true)
                    .build()
            selector.setParameters(params)
            Log.d(TAG, "DASH seamless quality switch: constrained raw max height to ${maxHeight}px for ${qualityHeight}p")
        }
    }

    /**
     * Apply default track selector parameters for adaptive quality.
     */
    fun applyAdaptiveTrackSelectorDefaults() {
        trackSelector?.let { selector ->
            selector.setParameters(adaptiveTrackSelectorDefaults(selector.buildUponParameters(), videoSizeCap, tvMimeTypes).build())
        }
    }

    private fun streamKey(stream: VideoStream): String = stream.getContent().takeIf { it.isNotBlank() } ?: stream.hashCode().toString()

    private fun failureVariantKey(stream: VideoStream): String {
        val height = qualityHeight(stream)
        val codec = VideoCodecUtils.codecKeyFromStream(stream)
        val itag =
            stream.itagItem?.id?.toString()
                ?: runCatching { stream.id }.getOrNull()?.takeIf { it.isNotBlank() }
        return listOfNotNull("${height}p", codec, itag?.let { "itag=$it" }).joinToString("|")
    }

    /**
     * Increment buffering count for adaptive quality tracking.
     */
    fun incrementBufferingCount() {
        // Counted in pinned mode too: a stall episode at the pinned height steps the pin
        // down one rung (see checkAdaptiveQualityDowngrade).
        consecutiveBufferingCount++
    }

    /**
     * Reset buffering count (called when playback is smooth).
     */
    fun resetBufferingCount() {
        consecutiveBufferingCount = 0
    }

    /**
     * Check if buffering threshold is reached for quality downgrade.
     */
    fun hasReachedBufferingThreshold(): Boolean = consecutiveBufferingCount >= PlayerConfig.BUFFERING_COUNT_THRESHOLD

    /**
     * Update bandwidth check time.
     */
    fun updateBandwidthCheckTime() {
        lastBandwidthCheckTime = System.currentTimeMillis()
    }

    /**
     * Check if enough time has passed since last bandwidth check.
     */
    fun shouldCheckBandwidth(): Boolean {
        val currentTime = System.currentTimeMillis()
        return currentTime - lastBandwidthCheckTime >= PlayerConfig.BANDWIDTH_CHECK_INTERVAL_MS
    }

    /**
     * Attempt quality downgrade when stream is corrupted.
     * Returns the new stream or null if no alternatives available.
     */
    fun attemptQualityDowngrade(): VideoStream? {
        if (!isAdaptiveQualityEnabled) {
            Log.w(TAG, "Manual quality selected - skipping automatic downgrade")
            return currentVideoStream
        }

        val workingStreams = getWorkingStreams()

        if (workingStreams.isEmpty()) {
            Log.e(TAG, "No working streams available - all streams failed")
            return null
        }

        if (failedStreamUrls.size >= availableVideoStreams.size) {
            Log.e(TAG, "All available streams exhausted - stopping playback")
            return null
        }

        // Prefer MP4 over WebM for better compatibility
        val lowerQualityStream =
            workingStreams
                .sortedWith(
                    compareBy(
                        { it.format?.mimeType?.contains("webm", ignoreCase = true) == true },
                        { qualityHeight(it) },
                    ),
                ).firstOrNull()

        if (lowerQualityStream != null) {
            val lowerHeight = qualityHeight(lowerQualityStream)
            Log.w(
                TAG,
                "Downgrading quality to ${lowerHeight}p (${lowerQualityStream.format?.mimeType}) - Failed: ${failedStreamUrls.size}/${availableVideoStreams.size}",
            )
            currentVideoStream = lowerQualityStream
            streamErrorCount = 0

            stateFlow.value =
                stateFlow.value.copy(
                    currentQuality = lowerHeight,
                    currentQualityKey = streamKey(lowerQualityStream),
                    isBuffering = true,
                )

            return lowerQualityStream
        }

        return null
    }

    fun fallbackToAlternateCodec(currentPosition: Long): Boolean {
        val current = currentVideoStream ?: return false
        val failingCodec = VideoCodecUtils.codecKeyFromStream(current)
        if (!failingCodec.equals("av1", ignoreCase = true)) return false

        val currentHeight = qualityHeight(current)
        markStreamFailed(current.getContent())

        val candidate =
            getWorkingStreams()
                .filter { !VideoCodecUtils.codecKeyFromStream(it).equals("av1", ignoreCase = true) }
                .minWithOrNull(
                    compareBy<VideoStream> { kotlin.math.abs(qualityHeight(it) - currentHeight) }
                        .thenBy { VideoCodecUtils.playbackCodecRank(it) }
                        .thenByDescending { it.bitrate },
                )
        if (candidate == null) {
            Log.w(TAG, "Codec fallback: no non-AV1 stream available for ${currentHeight}p")
            return false
        }

        val targetHeight = qualityHeight(candidate)
        val targetCodec = VideoCodecUtils.codecKeyFromStream(candidate)
        currentVideoStream = candidate
        streamErrorCount = 0
        lastAdaptiveQualityHeight = targetHeight
        if (!isAdaptiveQualityEnabled) manualQualityHeight = targetHeight

        Log.w(
            TAG,
            "Codec fallback: ${VideoCodecUtils.codecLabelFromKey(
                failingCodec,
            )} ${currentHeight}p gated by CDN (403) → ${VideoCodecUtils.codecLabelFromKey(targetCodec)} ${targetHeight}p",
        )

        onQualitySwitch(candidate, currentPosition)

        stateFlow.value =
            stateFlow.value.copy(
                currentQuality = if (isAdaptiveQualityEnabled) 0 else targetHeight,
                effectiveQuality = targetHeight,
                currentQualityKey = if (isAdaptiveQualityEnabled) null else streamKey(candidate),
                isBuffering = true,
                error = null,
            )
        return true
    }
}
