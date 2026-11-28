package io.github.shivytyahoo.glidetube.player.error

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.player.stream.PlaybackBlock
import io.github.shivytyahoo.glidetube.player.stream.PlaybackBlockedException
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class VideoErrorMapperTest {
    private val context =
        mockk<Context> {
            every { getString(any()) } answers { "string:${firstArg<Int>()}" }
        }

    @Test
    fun `a bot or network wall never names the network`() {
        // Walls no longer surface: the extractor maps them to no cause, so the mapper
        // must never produce the blocking message for them either. They fall through
        // to the generic blocked mapping.
        val botWall = VideoErrorMapper.from(context, PlaybackBlockedException(PlaybackBlock.BOT_WALL))
        val networkWall = VideoErrorMapper.from(context, PlaybackBlockedException(PlaybackBlock.NETWORK_WALL))

        assertThat(botWall.message).isEqualTo("string:${R.string.error_login_required}")
        assertThat(networkWall.message).isEqualTo("string:${R.string.error_login_required}")
    }

    @Test
    fun `a sign-in wall says an account is needed`() {
        val error = VideoErrorMapper.from(context, PlaybackBlockedException(PlaybackBlock.LOGIN_REQUIRED))

        assertThat(error.message).isEqualTo("string:${R.string.error_login_required}")
        assertThat(error.hint).isEqualTo("string:${R.string.error_login_required_hint}")
    }

    @Test
    fun `a failure with no cause stays generic`() {
        assertThat(VideoErrorMapper.from(context, null).message).isEqualTo("string:${R.string.error_generic}")
    }
}
