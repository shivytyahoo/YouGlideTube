package io.github.shivytyahoo.glidetube.ui

import io.github.shivytyahoo.glidetube.ui.components.layout.navigation.GlideTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationDestinationsTest {
    @Test
    fun tabRootsMapToTheirTab() {
        GlideTab.entries.filter { it != GlideTab.Shorts }.forEach { tab ->
            assertEquals(tab, flowTabForDestination(tab.route, shortsSourceArg = null))
        }
    }

    @Test
    fun onlyTheShortsFeedIsTheShortsTab() {
        assertEquals(GlideTab.Shorts, flowTabForDestination(SHORTS_ROUTE_PATTERN, shortsSourceArg = null))
        assertEquals(GlideTab.Shorts, flowTabForDestination(SHORTS_ROUTE_PATTERN, shortsSourceArg = "feed"))
        assertNull(flowTabForDestination(SHORTS_ROUTE_PATTERN, shortsSourceArg = "feed:dQw4w9WgXcQ"))
        assertNull(flowTabForDestination(SHORTS_ROUTE_PATTERN, shortsSourceArg = "saved:"))
    }

    @Test
    fun aVideoOpenedFromShortsPlaysOverTheFirstOtherTab() {
        assertEquals("music", shortsExitRoute(listOf(GlideTab.Shorts, GlideTab.Music, GlideTab.Library)))
        assertEquals("home", shortsExitRoute(listOf(GlideTab.Home, GlideTab.Shorts)))
        assertEquals("home", shortsExitRoute(listOf(GlideTab.Shorts)))
        assertEquals("home", shortsExitRoute(emptyList()))
    }

    @Test
    fun detailScreensAreNotTabRoots() {
        listOf("settings", "settings/content", "playlists", "playlist/{playlistId}", "onboarding", null).forEach { route ->
            assertNull(flowTabForDestination(route, shortsSourceArg = null))
        }
    }

    @Test
    fun routesFromOutsideTheGraphResolveToTheirTab() {
        assertEquals(GlideTab.Search, flowTabForRoute("search"))
        assertEquals(GlideTab.Music, flowTabForRoute("music"))
        listOf("downloads", "history", "musicRecognize", "musicPlayer/abc", "settings").forEach { route ->
            assertNull(flowTabForRoute(route))
        }
    }

    @Test
    fun searchIsTheOnlyTabWithoutTheBar() {
        GlideTab.entries.forEach { tab -> assertEquals(tab != GlideTab.Search, tab.showsNavigationBar()) }
        assertFalse((null as GlideTab?).showsNavigationBar())
        assertTrue(GlideTab.Home.showsNavigationBar())
    }

    @Test
    fun onlyChannelIdsAreBrowsedDirectly() {
        assertEquals("UCXuqSBlHAE6Xw-yeJA0Tunw", youtubeChannelBrowseId("UCXuqSBlHAE6Xw-yeJA0Tunw"))
        assertEquals("UCXuqSBlHAE6Xw-yeJA0Tunw", youtubeChannelBrowseId("https://m.youtube.com/channel/UCXuqSBlHAE6Xw-yeJA0Tunw?si=abc"))
        assertNull(youtubeChannelBrowseId("@LinusTechTips"))
        assertNull(youtubeChannelBrowseId("https://www.youtube.com/@LinusTechTips"))
        assertNull(youtubeChannelBrowseId("https://www.youtube.com/c/LinusTechTips"))
        assertNull(youtubeChannelBrowseId(" "))
    }

    @Test
    fun channelHandlesUseHandleUrls() {
        assertEquals("https://www.youtube.com/@flow", youtubeChannelUrl("@flow"))
        assertEquals("https://www.youtube.com/@flow", youtubeChannelUrl("flow"))
        assertEquals(
            "https://www.youtube.com/channel/UC123",
            youtubeChannelUrl("UC123"),
        )
    }

    @Test
    fun malformedHandleChannelUrlsAreRepaired() {
        assertEquals(
            "https://www.youtube.com/@flow",
            youtubeChannelUrl("https://youtube.com/channel/@flow"),
        )
        assertEquals(
            "https://www.youtube.com/@flow",
            youtubeChannelUrl("https://m.youtube.com/@flow/videos"),
        )
    }

    @Test
    fun channelRoutesEncodeCanonicalUrls() {
        assertEquals(
            "channel?url=https%3A%2F%2Fwww.youtube.com%2F%40flow",
            youtubeChannelRoute("@flow"),
        )
    }
}
