package io.github.aedev.flow.service.auto

import io.github.aedev.flow.data.account.AccountPlaylists
import io.github.aedev.flow.data.account.toPlaylistInfo
import io.github.aedev.flow.data.repository.YouTubePlaylistRepository
import io.github.aedev.flow.data.model.PlaylistInfo
import io.github.aedev.flow.data.model.toMusicTrack
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.music.model.audioMusicOnly
import io.github.aedev.flow.data.music.model.toMusicTrack
import io.github.aedev.flow.data.newmusic.InnertubeMusicService
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import io.github.aedev.flow.data.music.DownloadManager
import io.github.aedev.flow.data.music.MusicSessionLibrary as MusicHistoryRepository

/**
 * What the car lists, read from what the app already stores. Browsing makes no network call; only a
 * search does, once per query within [SEARCH_TTL_MS].
 */
@Singleton
class AutoLibrary
    @Inject
    constructor(
        private val musicHistory: MusicHistoryRepository,
        private val accountPlaylists: AccountPlaylists,
        private val youTubePlaylists: YouTubePlaylistRepository,
        private val downloads: DownloadManager,
    ) {
        private data class CachedSearch(
            val at: Long,
            val tracks: List<MusicTrack>,
        )

        private val searchLock = Mutex()
        private val searches = LinkedHashMap<String, CachedSearch>()

        suspend fun playlists(): List<PlaylistInfo> {
            accountPlaylists.ensureLoaded()
            return accountPlaylists.playlists.value.map { it.toPlaylistInfo() }
        }

        /** The songs under [parentId], or null when it is not a list of songs. */
        suspend fun tracks(parentId: String): List<MusicTrack>? {
            AutoBrowseTree.playlistIdOf(parentId)?.let { id ->
                return youTubePlaylists.complete(id)?.videos.orEmpty().map { it.toMusicTrack() }
            }
            AutoBrowseTree.searchQueryOf(parentId)?.let { return search(it) }
            return when (parentId) {
                AutoBrowseTree.ON_REPEAT -> musicHistory.onRepeatTracks(MIX_SIZE)
                AutoBrowseTree.RIGHT_NOW -> emptyList()
                AutoBrowseTree.REDISCOVER -> emptyList()
                AutoBrowseTree.LIKED -> musicHistory.favorites.first()
                AutoBrowseTree.DOWNLOADS -> downloads.downloadedTracks.first().map { it.track }
                AutoBrowseTree.RECENT -> musicHistory.history.first()
                AutoBrowseTree.QUEUE -> EnhancedMusicPlayerManager.queue.value
                else -> null
            }
        }

        /** Songs for [query], from the cache when the same words were searched a moment ago. */
        suspend fun search(query: String): List<MusicTrack> {
            val key = query.trim().lowercase()
            if (key.isEmpty()) return emptyList()
            return searchLock.withLock {
                val now = System.currentTimeMillis()
                searches[key]?.takeIf { now - it.at < SEARCH_TTL_MS }?.let { return@withLock it.tracks }
                val tracks = InnertubeMusicService.searchMusic(query.trim())
                searches[key] = CachedSearch(now, tracks)
                while (searches.size > MAX_CACHED_SEARCHES) searches.remove(searches.keys.first())
                tracks
            }
        }

        private companion object {
            const val MIX_SIZE = 30
            const val SEARCH_TTL_MS = 10 * 60 * 1000L
            const val MAX_CACHED_SEARCHES = 8
        }
    }
