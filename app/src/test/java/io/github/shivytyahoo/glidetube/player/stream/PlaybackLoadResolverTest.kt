package io.github.shivytyahoo.glidetube.player.stream

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackLoadResolverTest {
    @Test
    fun `a bot or network wall never produces a blocking error`() {
        // Walls are retried silently: the resolver must not raise the exception that
        // used to show the blocking screen for them.
        assertThat(failureCauseForBlock(PlaybackBlock.BOT_WALL)).isNull()
        assertThat(failureCauseForBlock(PlaybackBlock.NETWORK_WALL)).isNull()
    }

    @Test
    fun `a sign-in wall still produces a blocking error`() {
        val cause = failureCauseForBlock(PlaybackBlock.LOGIN_REQUIRED)

        assertThat(cause).isInstanceOf(PlaybackBlockedException::class.java)
        assertThat((cause as PlaybackBlockedException).block).isEqualTo(PlaybackBlock.LOGIN_REQUIRED)
    }

    @Test
    fun `no block means no cause`() {
        assertThat(failureCauseForBlock(null)).isNull()
    }
}
