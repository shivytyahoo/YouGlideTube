package io.github.shivytyahoo.glidetube.player.quality

import io.github.shivytyahoo.glidetube.player.config.PlayerConfig

/**
 * The sustained throughput of completed transfers, as a guard against the bandwidth meter's
 * burst-inflated estimate: the first chunk of a fetch arrives in a burst, so a single sample
 * can claim a hundred times the link's real speed. The median of the last few samples is
 * immune to that — one burst sample cannot move it — while still following a genuinely faster
 * or slower link within a few transfers. Null until enough samples exist, so an early pick
 * never trusts a half-formed window.
 */
internal class SustainedBandwidth {
    private val samples = ArrayDeque<Long>()

    /** One completed transfer: [bytesTransferred] bytes in [elapsedMs] milliseconds. */
    @Synchronized
    fun recordSample(
        bytesTransferred: Long,
        elapsedMs: Long,
    ) {
        if (bytesTransferred <= 0 || elapsedMs <= 0) return
        val throughputBps = bytesTransferred * 8L * 1_000L / elapsedMs
        samples.addLast(throughputBps)
        while (samples.size > PlayerConfig.SUSTAINED_BANDWIDTH_WINDOW_SAMPLES) samples.removeFirst()
    }

    /** Median throughput of the recent samples in bits per second, or null when too few. */
    @get:Synchronized
    val sustainedBps: Long?
        get() {
            if (samples.size < PlayerConfig.SUSTAINED_BANDWIDTH_MIN_SAMPLES) return null
            val sorted = samples.sorted()
            return sorted[sorted.size / 2]
        }
}
