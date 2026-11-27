package io.github.shivytyahoo.glidetube.player.sabr

import com.google.common.truth.Truth.assertThat
import io.github.shivytyahoo.glidetube.player.sabr.core.SabrIndex
import io.github.shivytyahoo.glidetube.player.sabr.core.SabrSessionState
import io.github.shivytyahoo.glidetube.player.sabr.proto.FormatBufferedRange
import io.github.shivytyahoo.glidetube.player.sabr.proto.FormatId
import org.junit.Test

class SabrSessionRestartTest {
    @Test
    fun `restart moves the playhead back and clears ranges and consumed media`() {
        val state = SabrSessionState()
        state.playheadPositionMs = 60_000L
        state.addBufferedRange(
            true,
            FormatBufferedRange(FormatId(140, 0), startTimeMs = 60_000L, durationMs = 2_000L, startSequence = 10, endSequence = 10),
        )
        assertThat(state.markSegmentConsumed(140, 10, false)).isTrue()
        assertThat(state.markSegmentConsumed(140, 10, false)).isFalse()
        state.storeFormatIndex(140, SabrIndex(longArrayOf(0L, 1000L), longArrayOf(0L, 2000L)))

        state.resetForRestart(40_000L)

        assertThat(state.playheadPositionMs).isEqualTo(40_000L)
        assertThat(state.audioBufferedRanges).isEmpty()
        assertThat(state.markSegmentConsumed(140, 10, false)).isTrue()
        assertThat(state.formatIndexes[140]).isNotNull()
    }
}
