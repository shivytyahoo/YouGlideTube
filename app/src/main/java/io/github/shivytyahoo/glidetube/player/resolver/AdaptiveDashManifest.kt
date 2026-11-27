package io.github.shivytyahoo.glidetube.player.resolver

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.dash.manifest.AdaptationSet
import androidx.media3.exoplayer.dash.manifest.BaseUrl
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.Period
import androidx.media3.exoplayer.dash.manifest.RangedUri
import androidx.media3.exoplayer.dash.manifest.Representation
import androidx.media3.exoplayer.dash.manifest.SegmentBase
import io.github.shivytyahoo.glidetube.player.stream.StreamProcessor
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * One DASH manifest holding a whole quality ladder, built straight into Media3's manifest model so
 * its adaptive selection switches between the rungs chunk by chunk. Each rung is described the way
 * [ManifestGenerator] describes a single stream: the progressive URL as base URL, plus its init and
 * sidx byte ranges.
 */
@UnstableApi
internal object AdaptiveDashManifest {
    private const val MIN_BUFFER_TIME_MS = 1_500L

    /** Whether [stream] carries what a rung needs: a progressive URL, its byte ranges and a codec. */
    fun canDescribe(stream: VideoStream): Boolean {
        val itag = stream.itagItem ?: return false
        return stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
            stream.isVideoOnly &&
            stream.content.isNotBlank() &&
            !itag.codec.isNullOrBlank() &&
            itag.bitrate > 0 &&
            itag.initStart >= 0 &&
            itag.initEnd > itag.initStart &&
            itag.indexStart >= 0 &&
            itag.indexEnd > itag.indexStart
    }

    /** Whether [stream] carries what an audio rung needs: a progressive URL, its byte ranges and a codec. */
    fun canDescribeAudio(stream: AudioStream): Boolean {
        val itag = stream.itagItem ?: return false
        return stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
            stream.content.isNotBlank() &&
            !itag.codec.isNullOrBlank() &&
            itag.bitrate > 0 &&
            itag.initStart >= 0 &&
            itag.initEnd > itag.initStart &&
            itag.indexStart >= 0 &&
            itag.indexEnd > itag.indexStart
    }

    /** Null unless at least two rungs can be described and the duration is known. */
    fun build(
        rungs: List<VideoStream>,
        durationSeconds: Long,
        audioStreams: List<AudioStream> = emptyList(),
    ): DashManifest? {
        val representations = rungs.filter(::canDescribe).map(::representation)
        if (representations.size < 2) return null
        val durationMs =
            rungs
                .firstNotNullOfOrNull { stream -> stream.itagItem?.approxDurationMs?.takeIf { it > 0 } }
                ?: (durationSeconds * 1_000L)
        if (durationMs <= 0) return null
        val video = AdaptationSet(0, C.TRACK_TYPE_VIDEO, representations, emptyList(), emptyList(), emptyList())
        val audioSets = audioAdaptationSets(audioStreams)
        return DashManifest(
            C.TIME_UNSET,
            durationMs,
            MIN_BUFFER_TIME_MS,
            false,
            C.TIME_UNSET,
            C.TIME_UNSET,
            C.TIME_UNSET,
            C.TIME_UNSET,
            null,
            null,
            null,
            null,
            listOf(Period(null, 0, listOf(video) + audioSets)),
        )
    }

    /**
     * One audio AdaptationSet per (container, logical track), so a dub is a track the selector
     * can pick rather than a second copy of the default audio. The grouping key is the same one
     * the audio-track picker uses, so the manifest and the picker agree on what a track is.
     */
    private fun audioAdaptationSets(audioStreams: List<AudioStream>): List<AdaptationSet> {
        val grouped =
            audioStreams
                .filter(::canDescribeAudio)
                .groupBy { stream ->
                    (stream.format?.mimeType.orEmpty()) to
                        StreamProcessor.audioTrackGroupingKey(
                            trackId = stream.audioTrackId,
                            trackName = stream.audioTrackName,
                            languageTag = stream.audioLocale?.toLanguageTag(),
                            trackType = stream.audioTrackType?.name,
                        )
                }
        return grouped.entries.mapIndexed { index, (_, streams) ->
            AdaptationSet(
                (index + 1).toLong(),
                C.TRACK_TYPE_AUDIO,
                streams.map(::audioRepresentation),
                emptyList(),
                emptyList(),
                emptyList(),
            )
        }
    }

    private fun audioRepresentation(stream: AudioStream): Representation {
        val itag = stream.itagItem!!
        val formatBuilder =
            Format
                .Builder()
                .setId(itag.id.toString())
                .setContainerMimeType(stream.format?.mimeType)
                .setSampleMimeType(MimeTypes.getAudioMediaMimeType(itag.codec))
                .setCodecs(itag.codec)
                .setPeakBitrate(itag.bitrate)
                .setRoleFlags(
                    if (stream.audioTrackType == AudioTrackType.ORIGINAL) C.ROLE_FLAG_MAIN else C.ROLE_FLAG_DUB,
                )
        stream.audioLocale?.toLanguageTag()?.let { formatBuilder.setLanguage(it) }
        StreamProcessor.audioTrackDisplayName(stream)?.let { formatBuilder.setLabel(it) }
        val segmentBase =
            SegmentBase.SingleSegmentBase(
                RangedUri(null, itag.initStart.toLong(), (itag.initEnd - itag.initStart + 1).toLong()),
                1,
                0,
                itag.indexStart.toLong(),
                (itag.indexEnd - itag.indexStart + 1).toLong(),
            )
        return Representation.newInstance(0, formatBuilder.build(), listOf(BaseUrl(stream.content)), segmentBase)
    }

    private fun representation(stream: VideoStream): Representation {
        val itag = stream.itagItem!!
        val format =
            Format
                .Builder()
                .setId(itag.id.toString())
                .setContainerMimeType(stream.format?.mimeType)
                .setSampleMimeType(MimeTypes.getVideoMediaMimeType(itag.codec))
                .setCodecs(itag.codec)
                .setPeakBitrate(itag.bitrate)
                .setWidth(itag.width.takeIf { it > 0 } ?: Format.NO_VALUE)
                .setHeight(itag.height.takeIf { it > 0 } ?: Format.NO_VALUE)
                .setFrameRate(itag.fps.takeIf { it > 0 }?.toFloat() ?: Format.NO_VALUE.toFloat())
                .build()
        val segmentBase =
            SegmentBase.SingleSegmentBase(
                RangedUri(null, itag.initStart.toLong(), (itag.initEnd - itag.initStart + 1).toLong()),
                1,
                0,
                itag.indexStart.toLong(),
                (itag.indexEnd - itag.indexStart + 1).toLong(),
            )
        return Representation.newInstance(0, format, listOf(BaseUrl(stream.content)), segmentBase)
    }
}
