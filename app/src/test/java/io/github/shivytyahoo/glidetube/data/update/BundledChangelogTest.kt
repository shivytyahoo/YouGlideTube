package io.github.shivytyahoo.glidetube.data.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BundledChangelogTest {
    @Test
    fun `header lines and blanks are dropped, bullets are stripped`() {
        val parsed =
            parseBundledChangelog(
                """
                VERSION: 1.1.2
                DATE: 2026-11-24
                • Faster playback
                • Fixed videos failing to load

                • Home feed without sign-in
                """.trimIndent(),
            )
        assertThat(parsed).containsExactly(
            "Faster playback",
            "Fixed videos failing to load",
            "Home feed without sign-in",
        )
    }

    @Test
    fun `a changelog with no changes parses to nothing`() {
        assertThat(parseBundledChangelog("VERSION: 1.1.2\nDATE: 2026-11-24\n")).isEmpty()
    }
}
