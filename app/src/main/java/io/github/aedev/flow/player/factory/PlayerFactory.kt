package io.github.aedev.flow.player.factory

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.util.EventLogger
import io.github.aedev.flow.BuildConfig
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.player.audio.shouldHandleAudioFocus
import io.github.aedev.flow.player.config.PlayerConfig
import io.github.aedev.flow.player.config.VideoSizeCap
import io.github.aedev.flow.player.renderer.CustomRenderersFactory
import io.github.aedev.flow.player.subtitle.SubtitleDelay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@UnstableApi
class PlayerFactory {
    companion object {
        private const val TAG = "PlayerFactory"
        private const val EVENT_LOG_TAG = "FlowVideoEvents"
    }

    private class CachedPrefs(
        val audioLanguage: String,
        val minBufferMs: Int,
        val maxBufferMs: Int,
        val bufferForPlaybackMs: Int,
        val bufferRebufferMs: Int,
        val playDuringCalls: Boolean,
        val bandwidthEstimateBps: Long?,
    )

    private var cachedPrefs: CachedPrefs? = null

    private fun ensurePrefs(context: Context): CachedPrefs {
        cachedPrefs?.let { return it }
        val prefs = PlayerPreferences(context)
        val result =
            runBlocking {
                val buffers = prefs.effectiveBufferDurations.first()
                CachedPrefs(
                    audioLanguage = prefs.preferredAudioLanguage.first(),
                    minBufferMs = buffers.minMs,
                    maxBufferMs = buffers.maxMs,
                    bufferForPlaybackMs = buffers.playbackMs,
                    bufferRebufferMs = buffers.rebufferMs,
                    playDuringCalls = prefs.playDuringCalls.first(),
                    bandwidthEstimateBps = prefs.bandwidthEstimateBps.first(),
                )
            }
        cachedPrefs = result
        return result
    }

    suspend fun preloadPreferences(context: Context) {
        if (cachedPrefs != null) return
        val prefs = PlayerPreferences(context)
        val buffers = prefs.effectiveBufferDurations.first()
        cachedPrefs =
            CachedPrefs(
                audioLanguage = prefs.preferredAudioLanguage.first(),
                minBufferMs = buffers.minMs,
                maxBufferMs = buffers.maxMs,
                bufferForPlaybackMs = buffers.playbackMs,
                bufferRebufferMs = buffers.rebufferMs,
                playDuringCalls = prefs.playDuringCalls.first(),
                bandwidthEstimateBps = prefs.bandwidthEstimateBps.first(),
            )
    }

    fun createBandwidthMeter(context: Context): DefaultBandwidthMeter {
        // Seed from the last measured estimate so the ABR starts at the real link speed
        // instead of re-walking the ladder after every restart; an unseeded first launch
        // falls back to the (deliberately modest) default estimate. The seed is capped:
        // a stale-high estimate from another network (fast Wi-Fi, now slow 4G) would
        // otherwise start the session at 4K and stall the first seconds.
        val seeded = ensurePrefs(context).bandwidthEstimateBps?.coerceIn(100_000L, PlayerConfig.MAX_INITIAL_BANDWIDTH_ESTIMATE_BPS)
        return DefaultBandwidthMeter
            .Builder(context)
            .setInitialBitrateEstimate(seeded ?: PlayerConfig.INITIAL_BANDWIDTH_ESTIMATE)
            .setResetOnNetworkTypeChange(true)
            .build()
    }

    fun createTrackSelector(
        context: Context,
        videoSizeCap: VideoSizeCap,
    ): DefaultTrackSelector {
        val trackSelectionFactory =
            AdaptiveTrackSelection.Factory(
                PlayerConfig.ABR_MIN_BUFFER_FOR_QUALITY_INCREASE_MS,
                PlayerConfig.ABR_MAX_BUFFER_FOR_QUALITY_DECREASE_MS,
                AdaptiveTrackSelection.DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS,
                PlayerConfig.AUTO_BANDWIDTH_FRACTION,
            )
        val prefs = ensurePrefs(context)

        return DefaultTrackSelector(context, trackSelectionFactory).apply {
            val builder =
                buildUponParameters()
                    .setPreferredVideoMimeTypes(*PlayerConfig.PREFERRED_VIDEO_MIME_TYPES)
                    .setAllowVideoMixedMimeTypeAdaptiveness(false)
                    .setAllowMultipleAdaptiveSelections(true)
                    .setForceHighestSupportedBitrate(false)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .setViewportSizeToPhysicalDisplaySize(context, true)
                    .setMaxVideoSize(videoSizeCap.maxWidth, videoSizeCap.maxHeight)

            when (prefs.audioLanguage) {
                "original", "" -> {}

                else -> {
                    builder.setPreferredAudioLanguage(prefs.audioLanguage)
                }
            }

            setParameters(builder.build())
        }
    }

    fun createLoadControl(context: Context): DefaultLoadControl {
        val prefs = ensurePrefs(context)
        return LoadControlFactory.forVideo(
            context = context,
            minMs = prefs.minBufferMs,
            maxMs = prefs.maxBufferMs,
            playbackMs = prefs.bufferForPlaybackMs,
            rebufferMs = prefs.bufferRebufferMs,
        )
    }

    fun createRenderersFactory(
        context: Context,
        audioProcessors: Array<AudioProcessor> = emptyArray(),
        subtitleDelay: SubtitleDelay = SubtitleDelay(),
    ): DefaultRenderersFactory =
        CustomRenderersFactory(context, audioProcessors, subtitleDelay)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

    fun createPlayer(
        context: Context,
        trackSelector: DefaultTrackSelector,
        loadControl: DefaultLoadControl,
        renderersFactory: DefaultRenderersFactory,
        bandwidthMeter: BandwidthMeter,
        dataSourceFactory: DataSource.Factory?,
    ): ExoPlayer {
        val factory = dataSourceFactory ?: DefaultDataSource.Factory(context)
        val prefs = ensurePrefs(context)

        return ExoPlayer
            .Builder(context, renderersFactory)
            .experimentalSetDynamicSchedulingEnabled(PlayerConfig.ENABLE_DYNAMIC_SCHEDULING)
            .setTrackSelector(trackSelector)
            .setBandwidthMeter(bandwidthMeter)
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                shouldHandleAudioFocus(prefs.playDuringCalls),
            ).setHandleAudioBecomingNoisy(true)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .build()
            .also {
                it.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                it.setWakeMode(C.WAKE_MODE_LOCAL)
                // Renderer, decoder, surface and stall events under one tag, so a stall can be
                // timed from logcat instead of argued about (#1298).
                if (BuildConfig.DEBUG) it.addAnalyticsListener(EventLogger(EVENT_LOG_TAG))
                Log.d(TAG, "ExoPlayer instance created")
            }
    }
}
