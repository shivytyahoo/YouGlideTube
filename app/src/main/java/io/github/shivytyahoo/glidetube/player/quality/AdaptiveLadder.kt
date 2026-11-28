package io.github.shivytyahoo.glidetube.player.quality

import io.github.shivytyahoo.glidetube.player.resolver.AdaptiveDashManifest
import io.github.shivytyahoo.glidetube.player.stream.VideoCodecUtils
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * The qualities Auto hands to Media3 as one source: one rung per height, the lightest stream at
 * that height, across codec families — the manifest carries one AdaptationSet per family, so the
 * selector can move between families without a reload. A weak device keeps the old single-family
 * ladder: it stutters on AV1/VP9 even where they claim hardware decode. SDR and HDR never share
 * a ladder: Media3 cannot switch between them without a visible decoder change.
 */
internal object AdaptiveLadder {
    private const val MIN_RUNGS = 2

    /** Empty when fewer than two qualities qualify, which leaves Auto on a single stream. */
    fun rungs(
        anchor: VideoStream?,
        streams: List<VideoStream>,
        decodes: ((VideoStream) -> Boolean)? = null,
        weakDevice: Boolean = false,
    ): List<VideoStream> {
        anchor ?: return emptyList()
        val deep = isHighBitDepth(anchor)
        val candidates =
            streams.filter {
                AdaptiveDashManifest.canDescribe(it) &&
                    isHighBitDepth(it) == deep &&
                    (decodes == null || decodes(it))
            }
        // A weak device plays H.264 only: the anchor's family is the whole ladder, as before.
        val family =
            if (weakDevice) {
                val codec = VideoCodecUtils.codecKeyFromStream(anchor)
                candidates.filter { VideoCodecUtils.codecKeyFromStream(it) == codec && it.format == anchor.format }
            } else {
                candidates
            }
        return family
            .groupBy(VideoCodecUtils::qualityHeightFromStream)
            .values
            .map { sameHeight -> sameHeight.minBy { it.bitrate } }
            .sortedBy { it.bitrate }
            .takeIf { it.size >= MIN_RUNGS }
            .orEmpty()
    }

    /**
     * The ladder a preloaded video plays on: around the pin when a quality is pinned ([pinned]
     * set) — the pin is a track-selector constraint on the ladder, not a single stream — else
     * one around the bandwidth-fitting anchor when an estimate is known. Promotion recomputes
     * it from the same inputs to learn what is playing.
     */
    fun forPreload(
        pinned: VideoStream?,
        streams: List<VideoStream>,
        preferredCodec: String?,
        bandwidthEstimateBps: Long? = null,
        weakDevice: Boolean = false,
        familyOrder: List<String>? = null,
        decodes: ((VideoStream) -> Boolean)? = null,
    ): List<VideoStream> =
        if (pinned != null) {
            // A pinned quality is the viewer's explicit choice: it escapes the decoder gate
            // the way it escapes the size cap, and plays alone if its ladder comes out empty.
            rungs(pinned, streams, weakDevice = weakDevice)
        } else {
            rungs(anchorFor(streams, preferredCodec, bandwidthEstimateBps, weakDevice, familyOrder, decodes), streams, decodes, weakDevice)
        }

    /**
     * The anchor of a ladder built before the video has had any bandwidth to judge by. With an
     * estimate, the anchor is the tallest stream that fits the budget — the old highest-height
     * anchor started slow networks on 1080p and stalled the first seconds. Without one, the
     * highest height stays, as before.
     */
    private fun anchorFor(
        streams: List<VideoStream>,
        preferredCodec: String?,
        bandwidthEstimateBps: Long? = null,
        weakDevice: Boolean = false,
        familyOrder: List<String>? = null,
        decodes: ((VideoStream) -> Boolean)? = null,
    ): VideoStream? {
        // The anchor itself must be decodable, or the ladder is built in a family whose top
        // rung the device cannot play.
        val candidates =
            streams.filter { AdaptiveDashManifest.canDescribe(it) && (decodes == null || decodes(it)) }
        return bandwidthEstimateBps?.let { AutoQualityPolicy.cappedInitialPick(candidates, it, preferredCodec, weakDevice, familyOrder) }
            ?: candidates
                .minWithOrNull(
                    compareBy<VideoStream> { VideoCodecUtils.codecRankWithPreference(it, preferredCodec, weakDevice, familyOrder) }
                        .thenByDescending(VideoCodecUtils::qualityHeightFromStream),
                )
    }

    /** HDR and 10-bit SDR: VP9 profile 2, AV1 at 10 bits, HEVC Main 10. */
    internal fun isHighBitDepth(stream: VideoStream): Boolean {
        val codecs = (stream.itagItem?.codec ?: "").lowercase()
        val parts = codecs.split('.')
        return when (parts.firstOrNull()) {
            "vp09" -> parts.getOrNull(1) == "02" || parts.getOrNull(1) == "03"
            "av01" -> parts.getOrNull(3) == "10" || parts.getOrNull(3) == "12"
            "hev1", "hvc1" -> parts.getOrNull(1) == "2"
            else -> false
        }
    }
}
