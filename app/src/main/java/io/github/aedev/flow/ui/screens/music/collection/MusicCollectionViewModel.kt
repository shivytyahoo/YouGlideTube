package io.github.aedev.flow.ui.screens.music.collection

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.data.engagement.LikedMediaUseCase
import io.github.aedev.flow.data.engagement.SessionLikeState
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.account.AccountPlaylistIds
import io.github.aedev.flow.data.account.AccountPlaylists
import io.github.aedev.flow.data.account.toPlaylistInfo
import io.github.aedev.flow.data.model.Playlist
import io.github.aedev.flow.data.model.PlaylistInfo
import io.github.aedev.flow.data.music.YouTubeMusicService
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.music.model.PlaylistDetails
import io.github.aedev.flow.data.repository.YouTubePlaylistRepository
import io.github.aedev.flow.data.video.BackgroundDownloadQueuer
import io.github.aedev.flow.data.video.downloader.collection.DownloadedCollections
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.data.recommendation.music.DailyMixStore
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionUndo
import io.github.aedev.flow.ui.screens.music.MusicViewModel
import io.github.aedev.flow.utils.PerformanceDispatcher
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import io.github.aedev.flow.data.music.MusicSessionLibrary as MusicLibrary

private const val REMOTE_TIMEOUT_MS = 12_000L
private const val TAG = "MusicCollectionVM"
private const val MAX_PAGES = 60
private const val SHARING_TIMEOUT_MS = 5_000L

/**
 * One album or playlist page. Each page has its own instance, keyed by the route's id, so Back
 * returns to a page as it was left, a slow load can never land on another page, and nothing here
 * keeps the music home loading behind it.
 */
@HiltViewModel
class MusicCollectionViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        savedStateHandle: SavedStateHandle,
        private val accountPlaylists: AccountPlaylists,
        private val youTubePlaylists: YouTubePlaylistRepository,
        private val dailyMixes: DailyMixStore,
        private val likes: SessionLikeState,
        private val musicLibrary: MusicLibrary,
        private val likedMedia: LikedMediaUseCase,
        private val preferences: PlayerPreferences,
        private val queuer: BackgroundDownloadQueuer,
        private val collections: DownloadedCollections,
    ) : ViewModel() {
        val collectionId: String = checkNotNull(savedStateHandle[MUSIC_COLLECTION_ARG])

        private val _state = MutableStateFlow(MusicCollectionUiState())
        val state: StateFlow<MusicCollectionUiState> = _state.asStateFlow()

        private val _isCreating = MutableStateFlow(false)
        val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

        private val _messages = Channel<CollectionMessage>(Channel.BUFFERED)

        /** Confirmations for the snackbar. */
        val messages: Flow<CollectionMessage> = _messages.receiveAsFlow()

        internal val downloads = MusicCollectionDownloads(viewModelScope, collectionId, queuer, collections, _messages::send)

        /** Downloads every song here, all pages of them, or just [songs]; it carries on after the page closes. */
        fun download(songs: List<MusicTrack>? = null) {
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                val details = if (songs == null) loadAll() else null
                val count = songs?.size ?: details?.tracks?.size ?: 0
                if (count == 0) return@launch
                _messages.send(CollectionMessage(pluralRes = R.plurals.songs_downloading, count = count, args = listOf(count)))
                if (songs != null) downloads.downloadSongs(songs) else details?.let { downloads.downloadAll(_state.value.kind, it) }
            }
        }

        /** Your music playlists, for "Add all to playlist". */
        val mergeTargets: StateFlow<List<PlaylistInfo>> =
            accountPlaylists.playlists
                .map { list -> list.map { it.toPlaylistInfo() }.filterNot { it.id == collectionId } }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SHARING_TIMEOUT_MS), emptyList())

        val songSearch = MusicSongSearch(viewModelScope)

        /** The order the songs are shown in, remembered for this collection. */
        val sortOrder: StateFlow<MusicSortOrder> =
            preferences
                .playlistSortOrder(collectionId)
                .map { MusicSortOrder.fromStorage(it) ?: MusicSortOrder.COLLECTION }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SHARING_TIMEOUT_MS), MusicSortOrder.COLLECTION)

        fun setSortOrder(order: MusicSortOrder) {
            viewModelScope.launch { preferences.setPlaylistSortOrder(collectionId, order.storageValue) }
        }

        private var loadJob: Job? = null
        private var moreJob: Job? = null

        // Videos with a remove still running. The guard is checked on the calling
        // (main) thread and cleared on the worker thread, so the set is synchronized.
        private val removingIds = Collections.synchronizedSet(mutableSetOf<String>())
        private var graphRecorded = false


        internal val suggestions = MusicCollectionSuggestions(viewModelScope)

        fun requestSuggestions() = suggestions.requestOnce(trackIds())

        fun refreshSuggestions() = suggestions.refresh(trackIds())

        private fun trackIds(): List<String> =
            _state.value.details
                ?.tracks
                ?.map { it.videoId }
                .orEmpty()

        init {
            load()
        }

        fun retry() {
            _state.update { it.copy(isLoading = true, failed = false) }
            load()
        }

        /** The next page of a YouTube playlist; a failure keeps the token so the next call retries. */
        fun loadMore() {
            val details = _state.value.details ?: return
            val token = details.continuation ?: return
            if (moreJob?.isActive == true) return
            moreJob =
                viewModelScope.launch(PerformanceDispatcher.networkIO) {
                    _state.update { it.copy(isLoadingMore = true, moreFailed = false) }
                    val result = YouTubeMusicService.fetchPlaylistContinuation(details.id, token)
                    _state.update { state ->
                        val current = state.details ?: return@update state
                        when {
                            result == null -> state.copy(isLoadingMore = false, moreFailed = true)
                            result.first.isEmpty() && result.second == null -> {
                                // The page came back empty with no next page: nothing more
                                // exists behind this token (report 16: the 4th item is a video
                                // the music continuation drops). Keeping the token would
                                // leave a Retry button that can never succeed.
                                state.copy(details = current.copy(continuation = null), isLoadingMore = false)
                            }
                            else -> state.copy(details = current.appending(result.first, result.second), isLoadingMore = false)
                        }
                    }
                }
        }

        /** Every page of a YouTube playlist, for saving, downloading or queueing the whole of it. */
        suspend fun loadAll(): PlaylistDetails? {
            moreJob?.join()
            var details = _state.value.details ?: return null
            var pages = 0
            while (details.continuation != null && pages < MAX_PAGES) {
                val result = YouTubeMusicService.fetchPlaylistContinuation(details.id, details.continuation.orEmpty()) ?: break
                val (tracks, next) = result
                if (tracks.isEmpty() && next == null) break
                details = details.appending(tracks, next)
                pages++
            }
            _state.update { it.copy(details = details) }
            return details
        }

        /** Saves the whole collection to the library, or takes a saved one out. Your own are never saved. */
        fun toggleSaved() {
            val state = _state.value
            if (state.isOwn || state.kind == MusicCollectionKind.DAILY_MIX || state.kind == MusicCollectionKind.LIKED) return
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                // Saving lives on the account now: the collection is saved to the
                // account's library, not copied onto the device.
                val save = !state.isSaved
                musicLibrary.setCollectionSaved(collectionId, save)
                _state.update {
                    it.copy(isSaved = save, kind = if (save) MusicCollectionKind.SAVED else remoteKind(collectionId))
                }
                _messages.send(
                    CollectionMessage(
                        stringRes =
                            if (save) {
                                R.string.toast_saved_playlist_to_music_library
                            } else {
                                R.string.toast_removed_playlist_from_library
                            },
                    ),
                )
            }
        }

        /** Takes songs out of your playlist, or unlikes them from Liked music, with an Undo. */
        fun removeTracks(videoIds: Set<String>) {
            if (videoIds.isEmpty()) return
            val kind = _state.value.kind
            // A second tap while a video's remove is still running would race it against
            // a stale page (report 16: one tap, then 200, two 400s and noSetVideoId).
            val fresh = videoIds.filter { removingIds.add(it) }
            if (fresh.isEmpty()) return
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                // Rows whose remove never confirmed (the server refused, or the call
                // was cut short by leaving the page — report 17: the third diwali
                // remove was cancelled mid-flight) come back in the finally below, so
                // the list never lies about what the server still holds.
                val succeededOwnIds = mutableSetOf<String>()
                var removedOwn: List<MusicTrack> = emptyList()
                var beforeOwn: PlaylistDetails? = null
                try {
                    val message =
                        when (kind) {
                            MusicCollectionKind.OWN -> {
                                removedOwn =
                                    _state.value.details
                                        ?.tracks
                                        ?.filter { it.videoId in fresh }
                                        .orEmpty()
                                if (removedOwn.isEmpty()) return@launch
                                // Rows vanish at once: the server round trip takes ~1s, and a
                                // row that stays visible that long gets tapped again.
                                beforeOwn = _state.value.details
                                _state.update { state ->
                                    state.details?.let { details ->
                                        state.copy(details = details.copy(tracks = details.tracks.filterNot { it.videoId in fresh }))
                                    } ?: state
                                }
                                // One request for the whole selection: one call per track
                                // let the server refuse the later edits, so only the
                                // first of a multi-select ever left.
                                succeededOwnIds += youTubePlaylists.removeVideos(collectionId, removedOwn.map { it.videoId }.toSet())
                                val succeeded = removedOwn.filter { it.videoId in succeededOwnIds }
                                if (succeeded.isEmpty()) return@launch
                                // The Library card keeps the old count and thumbnail until the
                                // account list is re-read (report 17: diwali showed "3 videos"
                                // outside after all three were removed inside).
                                accountPlaylists.refresh()
                                CollectionMessage(
                                    pluralRes = R.plurals.songs_removed_from_playlist,
                                    count = succeeded.size,
                                    args = listOf(succeeded.size),
                                    undo =
                                        QuickActionUndo.PlaylistRemoval(
                                            playlistId = collectionId,
                                            videos = succeeded.map { it.toStoredVideo() },
                                        ),
                                )
                            }

                            MusicCollectionKind.LIKED -> {
                                val removed =
                                    likedMedia.unlike(
                                        _state.value.details
                                            ?.tracks
                                            ?.filter { it.videoId in fresh }
                                        ?.map {
                                            io.github.aedev.flow.data.local.LikedVideoInfo(
                                                videoId = it.videoId,
                                                title = it.title,
                                                thumbnail = it.thumbnailUrl,
                                                channelName = it.artist,
                                                channelId = it.channelId.takeIf(String::isNotBlank),
                                                durationSeconds = it.duration,
                                                isMusic = true,
                                            )
                                        }
                                        .orEmpty(),
                                )
                            if (removed.isEmpty()) return@launch
                            CollectionMessage(
                                pluralRes = R.plurals.songs_unliked,
                                count = removed.size,
                                args = listOf(removed.size),
                                undo = QuickActionUndo.Unlike(removed),
                            )
                        }

                        else -> {
                            return@launch
                        }
                    }
                    _messages.send(message)
                } finally {
                    removingIds.removeAll(fresh)
                    val unconfirmed = removedOwn.filter { it.videoId !in succeededOwnIds }
                    if (unconfirmed.isNotEmpty()) {
                        val unconfirmedIds = unconfirmed.map { it.videoId }.toSet()
                        _state.update { state ->
                            val details = state.details ?: return@update state
                            val restored = details.tracks.toMutableList()
                            beforeOwn?.tracks?.forEachIndexed { index, track ->
                                if (track.videoId in unconfirmedIds && restored.none { it.videoId == track.videoId }) {
                                    restored.add(index.coerceAtMost(restored.size), track)
                                }
                            }
                            state.copy(details = details.copy(tracks = restored))
                        }
                    }
                }
            }
        }

        fun updateDetails(
            name: String,
            description: String,
        ) {
            if (!_state.value.isOwn) return
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                // The account API renames only; descriptions stay as the account has them.
                YouTube.renamePlaylist(collectionId, name)
                accountPlaylists.refresh()
            }
        }

        /** Deletes your playlist; the page closes itself once it is gone. */
        fun delete() {
            if (!_state.value.isOwn) return
            _state.update { it.copy(isDeleted = true) }
            loadJob?.cancel()
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                // The tombstone goes first and the server call follows, so the
                // playlist leaves the list at once; a refused delete puts it
                // back instead of vanishing a playlist that still exists.
                accountPlaylists.removePlaylist(collectionId)
                if (YouTube.deletePlaylist(collectionId).isSuccess) {
                    accountPlaylists.refresh()
                    _messages.send(CollectionMessage(stringRes = R.string.toast_playlist_deleted))
                } else {
                    accountPlaylists.unremovePlaylist(collectionId)
                    accountPlaylists.refresh()
                    _state.update { it.copy(isDeleted = false) }
                    _messages.send(CollectionMessage(stringRes = R.string.toast_failed_to_delete_playlist))
                }
            }
        }

        /** A Daily Mix changes as you listen; this keeps today's songs as a playlist of your own. */
        fun saveAsPlaylist() {
            val details = _state.value.details ?: return
            if (_state.value.kind != MusicCollectionKind.DAILY_MIX || details.tracks.isEmpty()) return
            if (_isCreating.value) return
            _isCreating.value = true
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                try {
                    val target = createMusicPlaylist(details.title, details.description.orEmpty())
                    if (target != null) {
                        accountPlaylists.addEntry(
                            Playlist(
                                id = target.id,
                                name = target.name,
                                thumbnailUrl = target.thumbnailUrl,
                                videoCount = target.videoCount,
                                description = target.description,
                                isLocal = false,
                            ),
                        )
                    }
                    val saved = target != null && copyTracksToPlaylist(youTubePlaylists, target, details.tracks) != null
                    if (target != null) accountPlaylists.refresh()
                    _messages.send(
                        CollectionMessage(
                            stringRes =
                                if (saved) {
                                    R.string.daily_mix_saved_as_playlist
                                } else {
                                    R.string.toast_failed_to_save_playlist
                                },
                            args = if (saved) listOf(details.title) else emptyList(),
                        ),
                    )
                } finally {
                    _isCreating.value = false
                }
            }
        }

        fun reorder(orderedVideoIds: List<String>) {
            // Account playlists have no reorder API here; the order is the account's own.
        }

        /** Adds a song found in the catalogue to your playlist; the list shows it as the database does. */
        fun addTrack(track: MusicTrack) {
            if (!_state.value.isOwn) return
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                val added = YouTube.addToPlaylist(collectionId, track.videoId).isSuccess
                if (added) suggestions.drop(track.videoId)
                _messages.send(
                    CollectionMessage(stringRes = if (added) R.string.toast_added_to_playlist else R.string.toast_failed_to_add_track),
                )
            }
        }

        /** Adds [songs] to another of your playlists; null means every song here, all pages of it. */
        fun addTo(
            target: PlaylistInfo,
            songs: List<MusicTrack>? = null,
        ) {
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                val tracks = songs ?: loadAll()?.tracks.orEmpty()
                copyTracksToPlaylist(youTubePlaylists, target, tracks)?.let { _messages.send(it) }
            }
        }

        /** Makes a music playlist and copies [songs] onto it; null means every song here. */
        fun createAndAdd(
            name: String,
            description: String,
            songs: List<MusicTrack>? = null,
            onDone: () -> Unit = {},
        ) {
            if (_isCreating.value) return
            _isCreating.value = true
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                try {
                    val target = createMusicPlaylist(name, description)
                    if (target == null) {
                        _messages.send(CollectionMessage(stringRes = R.string.toast_failed_to_merge_playlist))
                        return@launch
                    }
                    accountPlaylists.addEntry(
                        Playlist(
                            id = target.id,
                            name = target.name,
                            thumbnailUrl = target.thumbnailUrl,
                            videoCount = target.videoCount,
                            description = target.description,
                            isLocal = false,
                        ),
                    )
                    val tracks = songs ?: loadAll()?.tracks.orEmpty()
                    copyTracksToPlaylist(youTubePlaylists, target, tracks)?.let { _messages.send(it) }
                } finally {
                    _isCreating.value = false
                    withContext(Dispatchers.Main) { onDone() }
                }
            }
        }

        private fun load() {
            loadJob?.cancel()
            loadJob =
                viewModelScope.launch(PerformanceDispatcher.diskIO) {
                    when {
                        collectionId == AccountPlaylistIds.LIKED_MUSIC -> observeLiked()
                        collectionId.startsWith(MusicViewModel.DAILY_MIX_ID_PREFIX) -> loadDailyMix()
                        else -> loadStoredOrRemote()
                    }
                }
        }

        /** Liked music, live: newest like first, with the details the favorites store kept. */
        private suspend fun observeLiked() {
            // The page reads the session like state, which used to hold only likes made
            // in this process: a fresh open showed 0 songs while the Library shelf
            // showed the account's liked music. Seed the state from the account first,
            // and the favorites too, so the page and the shelf read the same LM list.
            // A seed that ran while one list was absent leaves blank pictures behind,
            // so the open also backfills them from whichever list has the picture.
            likes.ensureSeeded()
            likes.backfillMissingThumbnails()
            musicLibrary.ensureFavoritesLoaded()
            val title = context.getString(R.string.liked_music_playlist)
            combine(likes.likedMusicInfos(), musicLibrary.favorites) { liked, favorites ->
                val tracks = likedMusicTracks(liked, favorites)
                PlaylistDetails(
                    id = collectionId,
                    title = title,
                    thumbnailUrl = tracks.firstOrNull { it.thumbnailUrl.isNotBlank() }?.thumbnailUrl.orEmpty(),
                    author = "",
                    trackCount = tracks.size,
                    tracks = tracks,
                ) to liked.associate { it.videoId to it.likedAt }
            }.collect { (details, likedAt) ->
                _state.update { it.copy(kind = MusicCollectionKind.LIKED, details = details, addedAt = likedAt, isLoading = false) }
            }
        }

        /** Every collection page loads from YouTube now; your own account playlists get edit rights. */
        private suspend fun loadStoredOrRemote() {
            accountPlaylists.ensureLoaded()
            val remote = fetchRemote() ?: collections.offline(collectionId)?.toPlaylistDetails()
            if (remote == null) {
                _state.update { it.copy(isLoading = false, failed = true) }
                return
            }
            val isOwn = accountPlaylists.playlists.value.any { it.id == collectionId }
            val isSaved = collectionId in musicLibrary.savedCollections.value
            _state.update {
                it.copy(
                    kind = if (isOwn) MusicCollectionKind.OWN else remoteKind(collectionId),
                    details = remote,
                    isSaved = isSaved,
                    isLoading = false,
                    failed = false,
                )
            }
        }

        private fun loadDailyMix() {
            val index = collectionId.removePrefix(MusicViewModel.DAILY_MIX_ID_PREFIX).toIntOrNull()
            val section = index?.let { dailyMixes.mixes.value.getOrNull(it) }
            if (section == null || section.tracks.isEmpty()) {
                _state.update { it.copy(isLoading = false, failed = true) }
                return
            }
            val details =
                PlaylistDetails(
                    id = collectionId,
                    title = section.title,
                    thumbnailUrl = section.thumbnailUrl ?: section.tracks.first().thumbnailUrl,
                    author = "",
                    trackCount = section.tracks.size,
                    description = context.getString(R.string.daily_mix_page_description),
                    tracks = section.tracks,
                )
            _state.update { it.copy(kind = MusicCollectionKind.DAILY_MIX, details = details, isLoading = false) }
        }

        private suspend fun fetchRemote(): PlaylistDetails? =
            runCatching {
                withTimeoutOrNull(REMOTE_TIMEOUT_MS) { YouTubeMusicService.fetchPlaylistDetails(collectionId) }
            }.onFailure { Log.w(TAG, "Remote load failed for $collectionId", it) }
                .getOrNull()

    }

/** The route argument the page reads its id from. */
const val MUSIC_COLLECTION_ARG = "playlistId"
