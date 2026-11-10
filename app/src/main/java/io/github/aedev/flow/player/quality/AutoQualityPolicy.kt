package io.github.aedev.flow.player.quality

import io.github.aedev.flow.player.config.PlayerConfig
import io.github.aedev.flow.player.stream.VideoCodecUtils
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Auto's rules for picking a stream by bandwidth, judged on each stream's own bitrate. Media3's
 * adaptive selection uses the same budget ([PlayerConfig.AUTO_BANDWIDTH_FRACTION] of the estimate),
 * so a ladder source and a single-stream source start at the same quality.
 */
internal object AutoQualityPolicy {
    /** The tallest quality whose stream fits the budget, in the best-ranked codec at that height. */
    fun initialPick(
        streams: List<VideoStream>,
        estimateBps: Long,
        preferredCodec: String?,
    ): VideoStream? {
        if (streams.isEmpty()) return null
        val budget = (estimateBps * PlayerConfig.AUTO_BANDWIDTH_FRACTION).toLong()
        val fitting = streams.filter { it.bitrate.toLong() in 1..budget }
        val height = (fitting.ifEmpty { null }?.maxOf(::height)) ?: streams.minOf(::height)
        return streams
            .filter { height(it) == height }
            .minWithOrNull(
                compareBy<VideoStream> { VideoCodecUtils.codecRankWithPreference(it, preferredCodec) }
                    .thenBy { if (it.bitrate <= budget) 0 else 1 }
                    .thenByDescending { it.bitrate },
            )
    }

    /** One quality up, when the estimate covers its bitrate with [PlayerConfig.QUALITY_UPGRADE_THRESHOLD] to spare. */
    fun stepUp(
        current: VideoStream,
        streams: List<VideoStream>,
        estimateBps: Long,
        preferredCodec: String?,
    ): VideoStream? {
        val next = neighbour(current, streams, preferredCodec) { height(it) > height(current) } ?: return null
        val required = next.bitrate * PlayerConfig.QUALITY_UPGRADE_THRESHOLD
        return next.takeIf { it.bitrate == 0 || estimateBps > required }
    }

    /**
     * Straight to the tallest quality the current estimate can sustain, instead of one rung at a
     * time: a collapsed link walked 1080p to 360p one rung per 10 s while the player stalled at
     * every step. Null when the fitting rung is not below the current one.
     */
    fun jumpDown(
        current: VideoStream,
        streams: List<VideoStream>,
        estimateBps: Long,
        preferredCodec: String?,
    ): VideoStream? {
        val fitting = initialPick(streams, estimateBps, preferredCodec) ?: return null
        return fitting.takeIf { height(it) < height(current) }
    }

    fun stepDown(
        current: VideoStream,
        streams: List<VideoStream>,
        preferredCodec: String?,
    ): VideoStream? = neighbour(current, streams, preferredCodec, nearestFirst = false) { height(it) < height(current) }

    private fun neighbour(
        current: VideoStream,
        streams: List<VideoStream>,
        preferredCodec: String?,
        nearestFirst: Boolean = true,
        side: (VideoStream) -> Boolean,
    ): VideoStream? {
        val candidates = streams.filter(side).ifEmpty { return null }
        val height = if (nearestFirst) candidates.minOf(::height) else candidates.maxOf(::height)
        val codec = VideoCodecUtils.codecKeyFromStream(current)
        return candidates
            .filter { height(it) == height }
            .minWithOrNull(
                compareBy<VideoStream> { if (VideoCodecUtils.codecKeyFromStream(it) == codec) 0 else 1 }
                    .thenBy { VideoCodecUtils.codecRankWithPreference(it, preferredCodec) }
                    .thenByDescending { it.bitrate },
            )
    }

    private fun height(stream: VideoStream): Int = VideoCodecUtils.normalizeQualityHeight(VideoCodecUtils.qualityHeightFromStream(stream))
}
