package io.github.shivytyahoo.glidetube.ui.screens.home

import com.google.common.truth.Truth.assertThat
import io.github.shivytyahoo.glidetube.innertube.models.YouTubeLocale
import org.junit.Test

class HomeFeedLocaleResetTest {
    @Test
    fun `the locale a process starts with keeps the caches`() {
        assertThat(isLocaleSwitch(previous = null, current = YouTubeLocale(gl = "US", hl = "ru"))).isFalse()
    }

    @Test
    fun `the same locale again keeps the caches`() {
        assertThat(
            isLocaleSwitch(
                previous = YouTubeLocale(gl = "US", hl = "ru"),
                current = YouTubeLocale(gl = "US", hl = "ru"),
            ),
        ).isFalse()
    }

    @Test
    fun `a new language drops the caches (#1277)`() {
        assertThat(
            isLocaleSwitch(
                previous = YouTubeLocale(gl = "US", hl = "en"),
                current = YouTubeLocale(gl = "US", hl = "ru"),
            ),
        ).isTrue()
    }

    @Test
    fun `a new country drops the caches`() {
        assertThat(
            isLocaleSwitch(
                previous = YouTubeLocale(gl = "US", hl = "en"),
                current = YouTubeLocale(gl = "IN", hl = "en"),
            ),
        ).isTrue()
    }
}
