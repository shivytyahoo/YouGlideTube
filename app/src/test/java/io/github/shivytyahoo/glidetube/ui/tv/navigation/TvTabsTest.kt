package io.github.shivytyahoo.glidetube.ui.tv.navigation

import com.google.common.truth.Truth.assertThat
import io.github.shivytyahoo.glidetube.ui.components.layout.navigation.GlideTab
import io.github.shivytyahoo.glidetube.ui.components.layout.navigation.NavigationVisibility
import org.junit.Test

class TvTabsTest {
    private val allOn = NavigationVisibility(home = true, shorts = true, music = true, search = true, categories = true)

    @Test
    fun `follows the user's order and leaves Shorts and Explore off`() {
        val order = listOf(GlideTab.Library, GlideTab.Shorts, GlideTab.Music, GlideTab.Explore, GlideTab.Home).map(GlideTab::id)

        val tabs = tvNavigation(order, defaultTabId = GlideTab.Home.id, visibility = allOn).tabs

        assertThat(tabs.take(3)).containsExactly(GlideTab.Library, GlideTab.Music, GlideTab.Home).inOrder()
        assertThat(tabs).containsNoneOf(GlideTab.Shorts, GlideTab.Explore)
    }

    @Test
    fun `always shows Search, which TV has nowhere else to put`() {
        val tabs = tvNavigation(emptyList(), GlideTab.Home.id, NavigationVisibility(search = false)).tabs

        assertThat(tabs).contains(GlideTab.Search)
    }

    @Test
    fun `hides the tabs the user hid`() {
        val tabs = tvNavigation(emptyList(), GlideTab.Home.id, NavigationVisibility(home = false, music = false)).tabs

        assertThat(tabs).containsNoneOf(GlideTab.Home, GlideTab.Music)
    }

    @Test
    fun `opens on the user's default tab, or the first tab when TV leaves it off`() {
        assertThat(tvNavigation(emptyList(), GlideTab.Library.id, allOn).startTab).isEqualTo(GlideTab.Library)

        val fromShorts = tvNavigation(emptyList(), GlideTab.Shorts.id, allOn)
        assertThat(fromShorts.startTab).isEqualTo(fromShorts.tabs.first())
    }

    @Test
    fun `roots are the tabs plus Settings`() {
        val navigation = tvNavigation(emptyList(), GlideTab.Home.id, allOn)

        assertThat(navigation.rootRoutes).containsExactlyElementsIn(navigation.tabs.map(GlideTab::route) + TV_SETTINGS_ROUTE)
    }
}
