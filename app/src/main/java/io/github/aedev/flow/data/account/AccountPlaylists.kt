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
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Well-known account playlist ids, addressed directly by the server. */
object AccountPlaylistIds {
    const val WATCH_LATER = "WL"
    const val LIKED_VIDEOS = "LL"
    const val LIKED_MUSIC = "LM"
}

/**
 * The account's own playlists (FEplaylist_aggregation), loaded once and shared — the playlists page,
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

        // Ids deleted in this process: a merge must never resurrect them, and the
        // server cannot be asked whether a missing playlist was deleted or merely
        // left out of a truncated aggregation response.
        private val removedIds = ConcurrentHashMap.newKeySet<String>()

        // Ids the user unsaved in this process: the merge must not carry them
        // forward once the server stops listing them — but a fresh response still
        // wins, so an own playlist the server keeps listing stays listed.
        private val unsavedIds = ConcurrentHashMap.newKeySet<String>()

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
            // The server's aggregation is not stable: the same request returns 23
            // playlists one session and 3 the next, with no continuation, and it
            // omits a playlist just emptied. Replacing the list would drop
            // playlists the account still has, so the fresh response only updates
            // what it carries and the rest is kept — except ids the user unsaved,
            // which the fresh response may still carry for an own playlist.
            _playlists.value =
                (all + _playlists.value.filterNot { it.id in unsavedIds })
                    .distinctBy { it.id }
                    .filterNot { it.id in removedIds }
        }

        /** Drops a playlist the user deleted, so a later merge cannot bring it back. */
        fun removePlaylist(id: String) {
            removedIds += id
            _playlists.value = _playlists.value.filterNot { it.id == id }
        }

        /** Drops an unsaved playlist from the list until the server (or a re-save) brings it back. */
        fun markUnsaved(id: String) {
            unsavedIds += id
            _playlists.value = _playlists.value.filterNot { it.id == id }
        }

        /** Clears an unsave, so a re-saved playlist is carried forward again. */
        fun markSaved(id: String) {
            unsavedIds -= id
        }

        /**
         * Patches one entry's count and thumbnail in place: the server omits an
         * emptied playlist from the aggregation, so a refresh alone would keep
         * the old count on its Library card.
         */
        fun updateEntry(id: String, videoCount: Int, thumbnailUrl: String) {
            _playlists.value =
                _playlists.value.map { playlist ->
                    if (playlist.id == id) {
                        playlist.copy(videoCount = videoCount, thumbnailUrl = thumbnailUrl)
                    } else {
                        playlist
                    }
                }
        }

        /** Inserts a playlist the server just created, so it shows at once instead of after the next list re-read. */
        fun addEntry(playlist: Playlist) {
            removedIds -= playlist.id
            unsavedIds -= playlist.id
            _playlists.value = (listOf(playlist) + _playlists.value).distinctBy { it.id }
        }

        /** Undoes a delete the server refused, so a later merge can bring the playlist back. */
        fun unremovePlaylist(id: String) {
            removedIds -= id
        }

        /** Shifts one entry's count by a confirmed add or remove, never below zero. */
        fun bumpEntry(id: String, delta: Int) {
            if (delta == 0) return
            _playlists.value =
                _playlists.value.map { playlist ->
                    if (playlist.id == id) {
                        playlist.copy(videoCount = (playlist.videoCount + delta).coerceAtLeast(0))
                    } else {
                        playlist
                    }
                }
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
