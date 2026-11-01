package io.github.aedev.flow.data.music

import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.music.model.toMusicTrack
import io.github.aedev.flow.data.repository.YouTubePlaylistRepository
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.utils.completed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The music library's session state: listening history and favourite tracks.
 *
 * History is deliberately memory-only — it powers the "recently played" shelves for this
 * session and is never persisted. Favourites are the account's liked music: the set is
 * seeded once from the account's LM playlist, and every toggle is written through to the
 * account like API, so a favourite here is a real account like, not a local copy.
 */
@Singleton
class MusicSessionLibrary
    @Inject
    constructor(
        private val playlistRepository: YouTubePlaylistRepository,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val _history = MutableStateFlow<List<MusicTrack>>(emptyList())
        val history: StateFlow<List<MusicTrack>> = _history.asStateFlow()

        private val _favorites = MutableStateFlow<List<MusicTrack>>(emptyList())
        val favorites: StateFlow<List<MusicTrack>> = _favorites.asStateFlow()

        @Volatile
        private var favoritesLoaded = false

        private val _savedCollections = MutableStateFlow<Set<String>>(emptySet())
        val savedCollections: StateFlow<Set<String>> = _savedCollections.asStateFlow()

        @Volatile
        private var savedCollectionsLoaded = false

        /**
         * The session's most-played shelf stand-in: what this session actually played, newest
         * first. (Play counts lived in the removed music brain; the session history is the
         * honest source that remains.)
         */
        fun onRepeatTracks(limit: Int): List<MusicTrack> = history.value.take(limit)

        fun recordHistory(track: MusicTrack) {
            _history.update { list ->
                (listOf(track) + list.filterNot { it.videoId == track.videoId }).take(MAX_HISTORY)
            }
        }

        /** Seeds the favourites set from the account's liked-music playlist, once per process. */
        /** Clears the account-seeded favorites and saved collections so they re-seed for the new account. */
        fun resetAccountData() {
            _favorites.value = emptyList()
            _savedCollections.value = emptySet()
            favoritesLoaded = false
            savedCollectionsLoaded = false
        }

        fun ensureFavoritesLoaded() {
            if (favoritesLoaded || !YouTube.isSignedIn()) return
            favoritesLoaded = true
            scope.launch {
                val page = runCatching { playlistRepository.complete(LIKED_MUSIC_PLAYLIST_ID) }.getOrNull() ?: return@launch
                val tracks = page.videos.map { it.toMusicTrack() }
                _favorites.update { current -> if (current.isEmpty()) tracks else current }
            }
        }

        /** Seeds the saved-collections set from the account's YouTube Music library, once per process. */
        fun ensureSavedCollectionsLoaded() {
            if (savedCollectionsLoaded || !YouTube.isSignedIn()) return
            savedCollectionsLoaded = true
            scope.launch {
                val page = runCatching { YouTube.library("FEmusic_library_landing").completed().getOrThrow() }.getOrNull() ?: return@launch
                val ids =
                    page.items
                        .filterIsInstance<io.github.aedev.flow.innertube.models.PlaylistItem>()
                        .map { it.id }
                        .toSet()
                _savedCollections.update { current -> if (current.isEmpty()) ids else current }
            }
        }

        /** Saves or unsaves a collection in the account library (a playlist like on the account). */
        fun setCollectionSaved(
            collectionId: String,
            saved: Boolean,
        ) {
            _savedCollections.update { if (saved) it + collectionId else it - collectionId }
            if (YouTube.isSignedIn()) {
                scope.launch { runCatching { YouTube.likePlaylist(collectionId, saved) } }
            }
        }

        fun addToFavorites(track: MusicTrack) {
            _favorites.update { list -> if (list.any { it.videoId == track.videoId }) list else listOf(track) + list }
            if (YouTube.isSignedIn()) {
                scope.launch { runCatching { YouTube.likeVideo(track.videoId, true) } }
            }
        }

        fun removeFromFavorites(videoId: String) {
            _favorites.update { list -> list.filterNot { it.videoId == videoId } }
            if (YouTube.isSignedIn()) {
                scope.launch { runCatching { YouTube.likeVideo(videoId, false) } }
            }
        }

        private companion object {
            const val MAX_HISTORY = 100
            const val LIKED_MUSIC_PLAYLIST_ID = "LM"
        }
    }
