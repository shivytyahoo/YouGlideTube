package io.github.shivytyahoo.glidetube.player.quality

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SustainedBandwidthTest {
    private val tracker = SustainedBandwidth()

    private fun sample(
        mbps: Long,
        elapsedMs: Long = 1_000L,
    ) {
        tracker.recordSample(mbps * 1_000_000L / 8L * elapsedMs / 1_000L, elapsedMs)
    }

    @Test
    fun `no sustained value until enough samples exist`() {
        sample(2)
        sample(2)

        assertThat(tracker.sustainedBps).isNull()
    }

    @Test
    fun `a burst sample cannot move the median`() {
        sample(172)
        sample(2)
        sample(2)
        sample(2)

        assertThat(tracker.sustainedBps).isEqualTo(2_000_000L)
    }

    @Test
    fun `the window only keeps recent samples`() {
        sample(2)
        sample(2)
        sample(2)
        sample(20)
        sample(20)
        sample(20)
        sample(20)
        sample(20)

        assertThat(tracker.sustainedBps).isEqualTo(20_000_000L)
    }

    @Test
    fun `degenerate samples are ignored`() {
        tracker.recordSample(0, 1_000L)
        tracker.recordSample(1_000L, 0L)
        sample(2)
        sample(2)
        sample(2)

        assertThat(tracker.sustainedBps).isEqualTo(2_000_000L)
    }
}
