package io.github.aedev.flow.data.account

import io.github.aedev.flow.data.model.Playlist
import io.github.aedev.flow.innertube.YouTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Well-known account playlist ids, addressed directly by the server. */
object AccountPlaylistIds {
    const val WATCH_LATER = "WL"
    const val LIKED_VIDEOS = "LL"
    const val LIKED_MUSIC = "LM"
}

/**
 * The account's own playlists (FEplaylists), loaded once and shared — the playlists page,
 * the library shelves and the save sheet all read this one list, so a create or delete made
 * anywhere is visible everywhere. Signed out it stays empty: there are no local playlists.
 */
@Singleton
class AccountPlaylists
    @Inject
    constructor() {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
        val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

        @Volatile
        private var loaded = false

        fun ensureLoaded() {
            // A TV-code (OAuth) session has no cookie at all, so gating on the
            // cookie left account playlists unloaded forever on that login —
            // the Music library reads this store, which is why it stayed empty
            // while the playlist pages themselves parsed. Signed-in is the gate.
            if (loaded || !YouTube.isSignedIn()) return
            loaded = true
            scope.launch { refresh() }
        }

        suspend fun refresh() {
            if (!YouTube.isSignedIn()) {
                _playlists.value = emptyList()
                return
            }
            val all = mutableListOf<Playlist>()
            var continuation: String? = null
            var pages = 0
            do {
                val page = YouTube.serverPlaylists(continuation).getOrNull() ?: break
                all += page.playlists
                continuation = page.continuation
                pages++
            } while (continuation != null && pages < MAX_PAGES)
            _playlists.value = all.distinctBy { it.id }
        }

        /** Drops the loaded flag so the next reader fetches the account's list again. */
        fun invalidate() {
            loaded = false
        }

        private companion object {
            const val MAX_PAGES = 5
        }
    }

/** The shelf shape the library and playlists pages render account playlists in. */
fun Playlist.toPlaylistInfo(): io.github.aedev.flow.data.model.PlaylistInfo =
    io.github.aedev.flow.data.model.PlaylistInfo(
        id = id,
        name = name,
        description = description,
        videoCount = videoCount,
        thumbnailUrl = thumbnailUrl,
        isPrivate = false,
        createdAt = 0L,
    )
