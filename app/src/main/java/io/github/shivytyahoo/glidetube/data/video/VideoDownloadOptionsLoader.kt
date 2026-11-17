package io.github.shivytyahoo.glidetube.data.video

import io.github.shivytyahoo.glidetube.data.model.Video
import io.github.shivytyahoo.glidetube.innertube.models.response.PlayerResponse
import io.github.shivytyahoo.glidetube.player.state.SubtitleOption
import io.github.shivytyahoo.glidetube.player.stream.CaptionTrackResolver
import io.github.shivytyahoo.glidetube.player.stream.InnerTubeVideoStreamExtractor
import io.github.shivytyahoo.glidetube.player.stream.StreamProcessor
import io.github.shivytyahoo.glidetube.player.stream.StreamSizeEstimator
import io.github.shivytyahoo.glidetube.player.stream.durationMs
import io.github.shivytyahoo.glidetube.player.stream.playableAudioFormats
import io.github.shivytyahoo.glidetube.player.stream.playableVideoFormats
import io.github.shivytyahoo.glidetube.utils.PerformanceDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

private const val EXTRACT_TIMEOUT_MS = 8_000L

/** Everything the download dialogs need to offer one video: its formats and each choice's size. */
data class VideoDownloadOptions(
    val video: Video,
    val videoFormats: List<PlayerResponse.StreamingData.Format>,
    val audioFormats: List<PlayerResponse.StreamingData.Format>,
    val streamSizes: Map<String, Long>,
    val subtitles: List<SubtitleOption> = emptyList(),
)

/**
 * Loads the formats for a video that is not playing, so a card can open the same download dialog
 * the player and Shorts open, with the same quality, codec and style settings applied.
 */
class VideoDownloadOptionsLoader
    @Inject
    constructor() {
        suspend fun load(video: Video): VideoDownloadOptions? =
            withContext(PerformanceDispatcher.networkIO) {
                val result =
                    withTimeoutOrNull(EXTRACT_TIMEOUT_MS) {
                        runCatching { InnerTubeVideoStreamExtractor.extract(video.id) }.getOrNull()
                    } ?: return@withContext null
                val videoFormats = result.playableVideoFormats()
                if (videoFormats.isEmpty()) return@withContext null
                val audioFormats = result.playableAudioFormats()
                VideoDownloadOptions(
                    video = video,
                    videoFormats = videoFormats,
                    audioFormats = audioFormats,
                    streamSizes = StreamSizeEstimator.fromInnerTubeFormats(videoFormats, audioFormats, result.durationMs() ?: 0L),
                    subtitles =
                        StreamProcessor.toSubtitleOptions(
                            StreamProcessor.processCaptions(CaptionTrackResolver.resolve(result.playerResponse)),
                        ),
                )
            }
    }
