package io.github.shivytyahoo.glidetube.ui.screens.home

import io.github.shivytyahoo.glidetube.data.local.HomeFeedCacheRepository
import io.github.shivytyahoo.glidetube.data.music.MusicCache
import io.github.shivytyahoo.glidetube.innertube.models.YouTubeLocale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * YouTube translates titles into the request language and serves regional feeds per content
 * country, so the Home and Music caches hold the old locale's content after a switch and would
 * keep showing it until they expire (#1277). A country-only change is a switch too: the feeds
 * are regional even when the language stays the same.
 */
@Singleton
internal class HomeFeedLocaleReset
    @Inject
    constructor(
        private val storedFeed: HomeFeedCacheRepository,
    ) {
        @Volatile private var lastLocale: YouTubeLocale? = null

        suspend fun onContentLocale(locale: YouTubeLocale) {
            val previous = lastLocale
            lastLocale = locale
            if (!isLocaleSwitch(previous, locale)) return
            HomeFeedCache.clear()
            storedFeed.clearAll()
            MusicCache.clearTrendingCache()
            MusicCache.clearGenreCache()
        }
    }

/** The first locale of a process is where the caches came from, not a switch. */
internal fun isLocaleSwitch(
    previous: YouTubeLocale?,
    current: YouTubeLocale,
): Boolean = previous != null && previous != current
