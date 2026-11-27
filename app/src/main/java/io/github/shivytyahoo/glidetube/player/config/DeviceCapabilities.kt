package io.github.shivytyahoo.glidetube.player.config

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log

/**
 * One-shot device capability probes the player reads at startup: whether this device is weak
 * (a small heap — the same thresholds the size cap and the load control already use), whether
 * any decoder plays 8K, and how tall each codec family decodes. Every probe enumerates the
 * codec list, so each result is cached for the process; the first call happens during player
 * initialisation, never on a frame.
 */
object DeviceCapabilities {
    private const val TAG = "DeviceCapabilities"

    /**
     * The frame rate the size probes ask for: 8K60 claims are rarer than 8K30 ones, and Media3
     * still refuses a track its own decoder check rejects.
     */
    private const val PROBE_FRAME_RATE = 30

    private val EIGHT_K_MIME_TYPES =
        arrayOf(
            MediaFormat.MIMETYPE_VIDEO_AV1,
            MediaFormat.MIMETYPE_VIDEO_VP9,
            MediaFormat.MIMETYPE_VIDEO_AVC,
        )

    /** A small heap or a low-RAM device: the same line the size cap draws. */
    fun isWeakDevice(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryClassMb = activityManager?.memoryClass ?: 256
        return activityManager?.isLowRamDevice == true || memoryClassMb <= 256
    }

    @Volatile
    private var cachedSupports8k: Boolean? = null

    /** True when any decoder on this device plays 8K at the probe frame rate. */
    fun supports8kDecode(): Boolean {
        cachedSupports8k?.let { return it }
        val supported =
            runCatching {
                EIGHT_K_MIME_TYPES.any { mime ->
                    supportsSize(mime, PlayerConfig.MAX_VIDEO_WIDTH_8K, PlayerConfig.MAX_VIDEO_HEIGHT_8K, PROBE_FRAME_RATE)
                }
            }.getOrDefault(false)
        cachedSupports8k = supported
        Log.d(TAG, "8K decode supported=$supported")
        return supported
    }

    private val maxHeightCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** The tallest standard height this device decodes for [mimeType], or 0 when it decodes none of them. */
    fun maxDecodableHeight(mimeType: String): Int =
        maxHeightCache.getOrPut(mimeType) {
            runCatching {
                intArrayOf(4320, 2160, 1440, 1080, 720, 480).firstOrNull { height ->
                    supportsSize(mimeType, height * 16 / 9, height, PROBE_FRAME_RATE)
                } ?: 0
            }.getOrDefault(0)
        }

    private fun supportsSize(
        mimeType: String,
        width: Int,
        height: Int,
        frameRate: Int,
    ): Boolean {
        val codecs =
            runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }.getOrDefault(emptyArray())
        return codecs.any { info ->
            !info.isEncoder &&
                info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) } &&
                runCatching {
                    info.getCapabilitiesForType(mimeType).videoCapabilities
                        ?.areSizeAndRateSupported(width, height, frameRate.toDouble()) == true
                }.getOrDefault(false)
        }
    }
}
