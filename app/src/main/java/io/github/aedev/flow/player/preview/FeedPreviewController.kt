package io.github.aedev.flow.player.preview

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.github.aedev.flow.player.cache.SharedPlayerCacheProvider
import io.github.aedev.flow.player.datasource.YouTubeHttpDataSource
import io.github.aedev.flow.player.stream.InnerTubeVideoStreamExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One player for feed previews, shared by every card: only the most-visible card gets a
 * surface at a time, and the player is re-pointed at whatever that card is.
 *
 * The preview plays a stream near 480p, not the stream the full player would start on and
 * not the lowest one: a preview is a moving thumbnail on a full-width card, so 144p reads
 * as broken, while 720p+ would burn data for a glimpse. Live videos are never previewed.
 * Previews start muted; the speaker button on the previewing card unmutes that preview only,
 * and the next preview starts muted again.
 *
 * The video pick is a video-only adaptive stream, so it carries no audio track at all —
 * unmuting it alone could never produce sound. The preview therefore merges the best
 * audio-only stream alongside the video, so unmute works instantly (the audio loads
 * with the video even while muted, a small data cost for gapless unmute).
 */
object FeedPreviewController {
    private const val TAG = "FeedPreview"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: ExoPlayer? = null
    private var mediaSourceFactory: ProgressiveMediaSource.Factory? = null
    private var playJob: Job? = null
    private var playGeneration = 0

    private val _previewVideoId = MutableStateFlow<String?>(null)
    val previewVideoId: StateFlow<String?> = _previewVideoId.asStateFlow()

    private val _previewReady = MutableStateFlow(false)
    val previewReady: StateFlow<Boolean> = _previewReady.asStateFlow()

    private val _isMuted = MutableStateFlow(true)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    val currentPlayer: ExoPlayer?
        get() = player

    /** The preview's position for [videoId], or 0 when that video is not the live preview. */
    fun positionIfPreviewing(videoId: String): Long =
        if (_previewVideoId.value == videoId) player?.currentPosition ?: 0L else 0L

    fun toggleMute() {
        val current = player ?: return
        val muted = !_isMuted.value
        _isMuted.value = muted
        current.volume = if (muted) 0f else 1f
    }

    private val readyListener =
        object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (_previewVideoId.value != null) _previewReady.value = true
            }
        }

    fun play(context: Context, videoId: String) {
        if (_previewVideoId.value == videoId) return
        playJob?.cancel()
        _previewReady.value = false
        val generation = ++playGeneration
        playJob =
            scope.launch {
                val extracted =
                    runCatching { InnerTubeVideoStreamExtractor.extract(videoId) }.getOrNull()
                if (generation != playGeneration) return@launch
                if (extracted == null || extracted.isLive || extracted.liveHlsUrl != null) return@launch
                val candidates = extracted.videoFormats.filter { it.url != null }
                val format =
                    candidates
                        .filter { (it.height ?: 0) in 360..720 }
                        .minByOrNull { kotlin.math.abs((it.height ?: 0) - 480) }
                        ?: candidates.filter { (it.height ?: 0) >= 360 }.minByOrNull { it.height ?: Int.MAX_VALUE }
                        ?: candidates.minByOrNull { it.height ?: Int.MAX_VALUE }
                val url = format?.url ?: return@launch
                val factory =
                    mediaSourceFactory ?: withContext(Dispatchers.IO) { buildFactory(context) }.also {
                        mediaSourceFactory = it
                    }
                val current =
                    player ?: ExoPlayer.Builder(context.applicationContext).build().also {
                        it.volume = 0f
                        it.repeatMode = Player.REPEAT_MODE_ONE
                        it.addListener(readyListener)
                        player = it
                    }
                current.stop()
                current.clearMediaItems()
                current.volume = 0f
                _isMuted.value = true
                _previewVideoId.value = videoId
                // The video stream is video-only: without a merged audio track the
                // speaker button would unmute silence. Highest-bitrate audio, with
                // the loudness-flattened DRC copy skipped when a plain one exists.
                val audioFormats = extracted.audioFormats.filter { it.url != null }
                val audioUrl =
                    (audioFormats.filter { !it.isDynamicRangeCompressed }.ifEmpty { audioFormats })
                        .maxByOrNull { it.bitrate }?.url
                val videoSource = factory.createMediaSource(MediaItem.fromUri(url))
                val source =
                    if (audioUrl != null) {
                        MergingMediaSource(videoSource, factory.createMediaSource(MediaItem.fromUri(audioUrl)))
                    } else {
                        videoSource
                    }
                current.setMediaSource(source)
                current.prepare()
                current.playWhenReady = true
            }
    }

    fun pause() {
        player?.playWhenReady = false
    }

    fun resume() {
        if (_previewVideoId.value != null) player?.playWhenReady = true
    }

    fun stop() {
        playJob?.cancel()
        player?.playWhenReady = false
        _previewVideoId.value = null
        _previewReady.value = false
    }

    fun release() {
        stop()
        player?.removeListener(readyListener)
        player?.release()
        player = null
    }

    private fun buildFactory(context: Context): ProgressiveMediaSource.Factory {
        val cache = SharedPlayerCacheProvider.getOrCreate(context.applicationContext)
        val cacheFactory =
            CacheDataSource
                .Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(
                    DefaultDataSource.Factory(context.applicationContext, YouTubeHttpDataSource.Factory()),
                )
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return ProgressiveMediaSource.Factory(cacheFactory)
    }
}
