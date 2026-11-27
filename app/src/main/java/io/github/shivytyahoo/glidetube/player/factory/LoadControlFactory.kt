package io.github.shivytyahoo.glidetube.player.factory

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator
import io.github.shivytyahoo.glidetube.data.local.BufferDurations
import io.github.shivytyahoo.glidetube.platform.DeviceFormFactor
import io.github.shivytyahoo.glidetube.platform.DeviceFormFactorDetector
import io.github.shivytyahoo.glidetube.player.config.PlayerConfig

/**
 * The app's three `DefaultLoadControl` profiles — video, Shorts and music — in one place.
 *
 * `setBufferDurationsMs` asserts `max >= min`, `min >= playback` and `min >= rebuffer`, and it throws
 * while the player is being constructed rather than falling back, which is how #788 became a
 * bootloop. Routing every profile through [build] enforces that invariant once instead of trusting
 * three independently-maintained sets of numbers to keep satisfying it.
 */
@UnstableApi
object LoadControlFactory {
    private const val TAG = "LoadControlFactory"
    private const val SKIP_TAIL_START_MS = 250
    private const val SKIP_TAIL_SPARE_MS = 3_000

    /**
     * Main video player: stored user preferences, re-capped against what this device's heap can
     * afford. The caps are applied at read time because a combination saved as valid on one device
     * can exceed the budget on another.
     *
     * @param longBuffer "Buffer further ahead": a much longer duration window and byte budget, so
     *   8K bitrates (~45–100 Mbps) are not bound by the 96 MB default — about 8–17 s of video —
     *   before the duration window. Ignored on low-memory devices, where the heap cannot afford
     *   it. The duration window, not the byte budget, is what binds at lower qualities: the
     *   loader still stops at the video's end.
     */
    fun forVideo(
        context: Context,
        minMs: Int,
        maxMs: Int,
        playbackMs: Int,
        rebufferMs: Int,
        longBuffer: Boolean = false,
    ): DefaultLoadControl {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryClassMb = activityManager?.memoryClass ?: 256
        val isLowMemoryDevice = activityManager?.isLowRamDevice == true || memoryClassMb <= 256
        val isConstrainedHeap = isLowMemoryDevice || memoryClassMb <= 384
        val isTv = DeviceFormFactorDetector.detect(context) == DeviceFormFactor.TV
        val longBufferActive = longBuffer && !isLowMemoryDevice

        val maxSafeMinBufferMs =
            if (isLowMemoryDevice) {
                PlayerConfig.LOW_MEMORY_MAX_SAFE_MAIN_MIN_BUFFER_MS
            } else {
                PlayerConfig.MAX_SAFE_MAIN_MIN_BUFFER_MS
            }
        val maxSafeBufferMs =
            when {
                longBufferActive -> PlayerConfig.LONG_BUFFER_MAX_MS
                isLowMemoryDevice -> PlayerConfig.LOW_MEMORY_MAX_SAFE_MAIN_BUFFER_MS
                else -> PlayerConfig.MAX_SAFE_MAIN_BUFFER_MS
            }
        // The long buffer's 400 MB budget is a wish, not a promise: on a device whose heap is
        // smaller than three times the budget, a full buffer is an OOM, so the long-buffer
        // budget is capped at a third of the heap. The normal 144 MB budget is untouched.
        val heapThirdBytes =
            minOf(Runtime.getRuntime().maxMemory() / 3, Int.MAX_VALUE.toLong()).toInt()
        val targetBufferBytes =
            when {
                longBufferActive && isTv -> minOf(PlayerConfig.LONG_BUFFER_TARGET_BUFFER_BYTES_TV, heapThirdBytes)
                longBufferActive -> minOf(PlayerConfig.LONG_BUFFER_TARGET_BUFFER_BYTES, heapThirdBytes)
                isLowMemoryDevice -> PlayerConfig.LOW_MEMORY_MAIN_TARGET_BUFFER_BYTES
                isConstrainedHeap -> PlayerConfig.MID_MEMORY_MAIN_TARGET_BUFFER_BYTES
                else -> PlayerConfig.MAIN_TARGET_BUFFER_BYTES
            }
        val backBufferMs =
            if (isConstrainedHeap) {
                PlayerConfig.LOW_MEMORY_BACK_BUFFER_DURATION_MS
            } else {
                PlayerConfig.BACK_BUFFER_DURATION_MS
            }

        val buffers =
            BufferDurations.sanitize(
                minMs = minMs,
                // Raising the cap alone would change nothing — the stored max is what binds —
                // so the long buffer also raises the requested window to the cap.
                maxMs = if (longBufferActive) maxOf(maxMs, PlayerConfig.LONG_BUFFER_MAX_MS) else maxMs,
                playbackMs = playbackMs,
                rebufferMs = rebufferMs,
                maxSafeMinMs = maxSafeMinBufferMs,
                maxSafeMaxMs = maxSafeBufferMs,
            )

        Log.d(
            TAG,
            "Buffer config: min=${buffers.minMs}ms, max=${buffers.maxMs}ms, playback=${buffers.playbackMs}ms, " +
                "rebuffer=${buffers.rebufferMs}ms, target=${targetBufferBytes / 1024 / 1024}MB, " +
                "back=${backBufferMs}ms, heap=${memoryClassMb}MB, longBuffer=$longBufferActive",
        )

        return build(
            minMs = buffers.minMs,
            maxMs = buffers.maxMs,
            playbackMs = buffers.playbackMs,
            rebufferMs = buffers.rebufferMs,
            backBufferMs = backBufferMs,
            retainBackBufferFromKeyframe = true,
            targetBufferBytes = targetBufferBytes,
        )
    }

    /**
     * Shorts pool: a deliberately small window so a swipe starts the next clip without a visible
     * wait. These are not user-tunable and stay below [BufferDurations]' product floors on purpose.
     */
    fun forShorts(): DefaultLoadControl =
        build(
            minMs = PlayerConfig.SHORTS_MIN_BUFFER_MS,
            maxMs = PlayerConfig.SHORTS_MAX_BUFFER_MS,
            playbackMs = PlayerConfig.SHORTS_BUFFER_FOR_PLAYBACK_MS,
            rebufferMs = PlayerConfig.SHORTS_BUFFER_FOR_REBUFFER_MS,
            backBufferMs = PlayerConfig.SHORTS_BACK_BUFFER_MS,
            retainBackBufferFromKeyframe = true,
            targetBufferBytes = PlayerConfig.SHORTS_TARGET_BUFFER_BYTES,
        )

    /**
     * Music service: audio-only, so a long window costs little memory and a low playback threshold
     * gets the first note out quickly. No back buffer, and no byte cap — the duration window is the
     * only budget it needs.
     */
    fun forMusic(): DefaultLoadControl =
        build(
            minMs = PlayerConfig.MUSIC_MIN_BUFFER_MS,
            maxMs = PlayerConfig.MUSIC_MAX_BUFFER_MS,
            playbackMs = PlayerConfig.MUSIC_BUFFER_FOR_PLAYBACK_MS,
            rebufferMs = PlayerConfig.MUSIC_BUFFER_FOR_REBUFFER_MS,
            backBufferMs = 0,
            retainBackBufferFromKeyframe = false,
            targetBufferBytes = C.LENGTH_UNSET,
        )

    /** A skipped song's tail plays for moments, so it starts on little audio and loads no more than it will play. */
    fun forSkipTail(overlapMs: Long): DefaultLoadControl {
        val keepMs = overlapMs.toInt() + SKIP_TAIL_SPARE_MS
        return build(
            minMs = keepMs,
            maxMs = keepMs,
            playbackMs = SKIP_TAIL_START_MS,
            rebufferMs = PlayerConfig.MUSIC_BUFFER_FOR_REBUFFER_MS,
            backBufferMs = 0,
            retainBackBufferFromKeyframe = false,
            targetBufferBytes = C.LENGTH_UNSET,
        )
    }

    /**
     * @param targetBufferBytes `C.LENGTH_UNSET` lets `DefaultLoadControl` derive the cap from the
     *   duration window, which is what a profile wants when time, not memory, is its budget.
     */
    private fun build(
        minMs: Int,
        maxMs: Int,
        playbackMs: Int,
        rebufferMs: Int,
        backBufferMs: Int,
        retainBackBufferFromKeyframe: Boolean,
        targetBufferBytes: Int,
    ): DefaultLoadControl {
        // Widen rather than throw: a profile that violates the contract should cost buffer, not a crash loop.
        val resolvedMin = maxOf(minMs, playbackMs, rebufferMs)
        val resolvedMax = maxOf(maxMs, resolvedMin)

        return DefaultLoadControl
            .Builder()
            .setAllocator(DefaultAllocator(true, PlayerConfig.ALLOCATOR_BUFFER_SIZE))
            .setBufferDurationsMs(resolvedMin, resolvedMax, playbackMs, rebufferMs)
            .setBackBuffer(backBufferMs, retainBackBufferFromKeyframe)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(targetBufferBytes)
            .build()
    }
}
