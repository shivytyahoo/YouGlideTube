package io.github.aedev.flow.ui.screens.playlists

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.data.account.AccountPlaylistIds
import io.github.aedev.flow.data.account.AccountPlaylists
import io.github.aedev.flow.data.account.toPlaylistInfo
import io.github.aedev.flow.data.engagement.LikedMediaUseCase
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.WatchLaterCleanup
import io.github.aedev.flow.data.model.PlaylistInfo
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.YouTubeMusicService
import io.github.aedev.flow.data.repository.RemotePlaylistPage
import io.github.aedev.flow.data.local.dao.DownloadCollectionSummary
import io.github.aedev.flow.data.local.entity.DownloadCollectionKind
import io.github.aedev.flow.data.repository.YouTubePlaylistRepository
import io.github.aedev.flow.data.video.BackgroundDownloadQueuer
import io.github.aedev.flow.data.video.DownloadBatch
import io.github.aedev.flow.data.video.downloader.collection.CollectionSpec
import io.github.aedev.flow.data.video.downloader.collection.DownloadedCollections
import io.github.aedev.flow.data.repository.walkPlaylistPages
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.ui.components.library.PlaylistSortOrder
import io.github.aedev.flow.ui.components.library.sortedForPlaylist
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionUndo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val SHARING_TIMEOUT_MS = 5_000L

/**
 * One playlist page, account-only: Watch later is the account's WL, Liked videos the account's
 * LL, and every other playlist is read from (and written to) the account. Nothing is stored
 * locally; "your" playlists are the ones on the account's playlist list.
 */
@HiltViewModel
class PlaylistDetailViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val playlistRepository: YouTubePlaylistRepository,
        private val accountPlaylists: AccountPlaylists,
        private val playerPreferences: PlayerPreferences,
        private val watchLaterCleanup: WatchLaterCleanup,
        private val likedMedia: LikedMediaUseCase,
        private val downloadQueuer: BackgroundDownloadQueuer,
        private val collections: DownloadedCollections,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        val playlistId: String = checkNotNull(savedStateHandle["playlistId"])
        private val sharing = SharingStarted.WhileSubscribed(stopTimeoutMillis = SHARING_TIMEOUT_MS)

        private val _uiState = MutableStateFlow(PlaylistDetailUiState())
        val uiState: StateFlow<PlaylistDetailUiState> = _uiState.asStateFlow()

        private val _messages = Channel<PlaylistUiMessage>(Channel.BUFFERED)
        val messages: Flow<PlaylistUiMessage> = _messages.receiveAsFlow()

        val sortOrder: StateFlow<PlaylistSortOrder> =
            combine(
                playerPreferences.playlistSortOrder(playlistId),
                _uiState.map { it.isLocalPlaylist to it.isLikes }.distinctUntilChanged(),
            ) { stored, (isLocal, isLikes) ->
                PlaylistSortOrder
                    .fromStorageValue(stored)
                    .takeIf { it in PlaylistSortOrder.availableFor(isLocal, isLikes) } ?: PlaylistSortOrder.defaultFor(isLikes)
            }.stateIn(viewModelScope, sharing, PlaylistSortOrder.MANUAL)

        val sortedVideos: StateFlow<List<Video>> =
            combine(_uiState.map { it.videos }, sortOrder) { videos, order ->
                videos.sortedForPlaylist(order)
            }.flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, sharing, emptyList())

        /** Your account playlists, for "Add all to playlist". */
        val userCreatedPlaylists: StateFlow<List<PlaylistInfo>> =
            accountPlaylists.playlists
                .map { list -> list.map { it.toPlaylistInfo() } }
                .stateIn(viewModelScope, sharing, emptyList())

        /** This playlist as a downloaded collection, or null when it was never downloaded as one. */
        val downloadedCollection: StateFlow<DownloadCollectionSummary?> =
            collections
                .observe(
                    playlistId,
                ).stateIn(viewModelScope, sharing, null)

        /** The running or just-finished "Download all" for this playlist. */
        val downloadBatch: StateFlow<DownloadBatch?> =
            downloadQueuer.batches
                .map { it[playlistId] }
                .stateIn(viewModelScope, sharing, null)

        init {
            accountPlaylists.ensureLoaded()
            loadPlaylist()
            viewModelScope.launch {
                downloadQueuer.batches
                    .map { it[playlistId] }
                    .filterNotNull()
                    .filter { it.isFinished }
                    .collect { batch ->
                        _messages.send(
                            if (batch.queued > 0) {
                                PlaylistUiMessage(stringRes = R.string.playlist_downloads_queued, args = listOf(batch.queued, batch.total))
                            } else {
                                PlaylistUiMessage(stringRes = R.string.playlist_download_queue_empty)
                            },
                        )
                        downloadQueuer.clearBatch(playlistId)
                    }
            }
        }

        fun downloadPlaylist() {
            viewModelScope.launch {
                val state = _uiState.first { !it.isLoading && !it.isLoadingMore }
                val videos = state.videos
                if (videos.isEmpty()) {
                    _messages.send(PlaylistUiMessage(stringRes = R.string.ui_playlist_empty))
                    return@launch
                }
                _messages.send(
                    PlaylistUiMessage(pluralRes = R.plurals.ui_downloading_videos, count = videos.size, args = listOf(videos.size)),
                )
                val isLocal = state.isLocalPlaylist || state.isWatchLater || state.isLikes
                val spec =
                    CollectionSpec(
                        id = playlistId,
                        kind = if (isLocal) DownloadCollectionKind.LOCAL_PLAYLIST else DownloadCollectionKind.VIDEO_PLAYLIST,
                        title = state.playlistName,
                        author = state.ownerName,
                        thumbnailUrl = state.thumbnailUrl.ifBlank { videos.first().thumbnailUrl },
                    )
                downloadQueuer.queueCollection(spec, videos, complete = isLocal || playlistRepository.cachedComplete(playlistId) != null)
            }
        }

        fun setSortOrder(order: PlaylistSortOrder) {
            viewModelScope.launch {
                playerPreferences.setPlaylistSortOrder(playlistId, order.storageValue)
            }
        }

        fun retry() {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            loadPlaylist()
        }

        /** Saves this playlist to the account's library. */
        fun saveToLibrary() {
            viewModelScope.launch {
                if (YouTube.isSignedIn()) YouTube.likePlaylist(playlistId, true)
                _uiState.update { it.copy(isLocalPlaylist = true, isSaved = true) }
                val saved = if (_uiState.value.isFromMusic) R.string.toast_saved_playlist_to_music_library else R.string.ui_playlist_saved_to_library
                _messages.send(PlaylistUiMessage(stringRes = saved))
            }
        }

        /** Takes this playlist out of the account's library. */
        fun unsaveFromLibrary() {
            viewModelScope.launch {
                if (YouTube.isSignedIn()) YouTube.likePlaylist(playlistId, false)
                _uiState.update { it.copy(isLocalPlaylist = false, isSaved = false) }
                _messages.send(PlaylistUiMessage(stringRes = R.string.ui_playlist_removed_from_library))
            }
        }

        fun removeVideo(videoId: String) = removeVideos(setOf(videoId))

        fun removeVideos(videoIds: Set<String>) {
            if (videoIds.isEmpty()) return
            if (_uiState.value.isLikes) {
                unlikeVideos(videoIds)
                return
            }
            viewModelScope.launch {
                val removed = _uiState.value.videos.filter { it.id in videoIds }
                if (removed.isEmpty()) return@launch
                removed.forEach { playlistRepository.removeVideo(playlistId, it.id) }
                _uiState.update { state -> state.copy(videos = state.videos.filterNot { it.id in videoIds }) }
                _messages.send(
                    PlaylistUiMessage(
                        pluralRes = R.plurals.playlist_videos_removed,
                        count = removed.size,
                        args = listOf(removed.size),
                        undo = QuickActionUndo.PlaylistRemoval(playlistId = playlistId, videos = removed),
                    ),
                )
            }
        }

        private fun unlikeVideos(videoIds: Set<String>) {
            viewModelScope.launch {
                val infos =
                    _uiState.value.videos
                        .filter { it.id in videoIds }
                        .map {
                            io.github.aedev.flow.data.local.LikedVideoInfo(
                                videoId = it.id,
                                title = it.title,
                                thumbnail = it.thumbnailUrl,
                                channelName = it.channelName,
                                channelId = it.channelId.takeIf(String::isNotBlank),
                                durationSeconds = it.duration,
                                isMusic = it.isMusic,
                            )
                        }
                val removed = likedMedia.unlike(infos)
                if (removed.isEmpty()) return@launch
                _uiState.update { state -> state.copy(videos = state.videos.filterNot { it.id in videoIds }) }
                _messages.send(
                    PlaylistUiMessage(
                        pluralRes = R.plurals.liked_videos_removed,
                        count = removed.size,
                        args = listOf(removed.size),
                        undo = QuickActionUndo.Unlike(removed),
                    ),
                )
            }
        }

        fun reorderVideos(orderedVideoIds: List<String>) {
            // Account playlists keep the account's order; there is no reorder API here.
        }

        fun updatePlaylist(
            name: String,
            description: String,
        ) {
            viewModelScope.launch {
                // The account API renames only; the description stays as the account has it.
                YouTube.renamePlaylist(playlistId, name)
                accountPlaylists.refresh()
                _uiState.update { it.copy(playlistName = name) }
            }
        }

        fun deletePlaylist() {
            viewModelScope.launch {
                YouTube.deletePlaylist(playlistId)
                accountPlaylists.refresh()
            }
        }

        fun mergeIntoPlaylist(targetPlaylistId: String) {
            viewModelScope.launch {
                val targetName = userCreatedPlaylists.value.firstOrNull { it.id == targetPlaylistId }?.name.orEmpty()
                _messages.send(mergeVideosIntoPlaylist(playlistRepository, _uiState.value.videos, targetPlaylistId, targetName))
            }
        }

        /** Makes an account playlist and copies every video here onto it. */
        fun createAndMerge(
            name: String,
            description: String,
        ) {
            viewModelScope.launch {
                _messages.send(createVideoPlaylistAndMerge(_uiState.value.videos, name))
                accountPlaylists.refresh()
            }
        }

        private fun loadPlaylist() {
            viewModelScope.launch {
                when (playlistId) {
                    AccountPlaylistIds.WATCH_LATER -> loadSpecial(isWatchLater = true)
                    AccountPlaylistIds.LIKED_VIDEOS -> loadSpecial(isWatchLater = false)
                    else -> loadRemotePlaylist()
                }
            }
        }

        /** Watch later and Liked videos: the account's own lists, with their built-in names. */
        private suspend fun loadSpecial(isWatchLater: Boolean) {
            _uiState.update {
                it.copy(
                    playlistName =
                        context.getString(
                            if (isWatchLater) R.string.watch_later else R.string.liked_videos_playlist,
                        ),
                    description = "",
                    isPrivate = true,
                    isLocalPlaylist = true,
                    isSaved = false,
                    isWatchLater = isWatchLater,
                    isLikes = !isWatchLater,
                    isLoading = true,
                    errorMessage = null,
                )
            }
            val videos = playlistRepository.complete(playlistId)?.videos
            if (videos == null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = if (YouTube.isSignedIn()) context.getString(R.string.playlist_load_failed) else null,
                    )
                }
                return
            }
            _uiState.update {
                it.copy(
                    videos = videos,
                    thumbnailUrl = videos.firstOrNull()?.thumbnailUrl.orEmpty(),
                    isLoading = false,
                )
            }
            if (isWatchLater) sweepWatched(videos)
        }

        private suspend fun sweepWatched(videos: List<Video>) {
            val removed = watchLaterCleanup.sweep(videos)
            if (removed.isEmpty()) return
            _uiState.update { state -> state.copy(videos = state.videos.filterNot { video -> removed.any { it.id == video.id } }) }
            _messages.send(
                PlaylistUiMessage(
                    pluralRes = R.plurals.watch_later_watched_removed,
                    count = removed.size,
                    args = listOf(removed.size),
                    undo = QuickActionUndo.PlaylistRemoval(playlistId = playlistId, videos = removed),
                ),
            )
        }

        private suspend fun loadRemotePlaylist() {
            val page = playlistRepository.cachedComplete(playlistId) ?: playlistRepository.firstPage(playlistId)
            if (page != null) {
                val inLibrary = accountPlaylists.playlists.value.any { it.id == playlistId }
                _uiState.update {
                    it.copy(
                        playlistName = page.title,
                        ownerName = page.ownerName,
                        isCourse = page.isCourse,
                        description = page.description,
                        isPrivate = false,
                        videos = page.videos,
                        thumbnailUrl = page.thumbnailUrl,
                        isLocalPlaylist = inLibrary,
                        isSaved = inLibrary,
                        isWatchLater = false,
                        isLoading = false,
                        isLoadingMore = page.continuation != null,
                        hidesUnavailable = page.continuation == null && page.hidesUnavailable(),
                        errorMessage = null,
                    )
                }
                page.continuation?.let { loadRemainingPages(page, it) }
                return
            }

            val musicDetails = runCatching { YouTubeMusicService.fetchPlaylistDetails(playlistId) }.getOrNull()
            if (musicDetails != null) {
                _uiState.update {
                    it.copy(
                        playlistName = musicDetails.title,
                        ownerName = musicDetails.author.takeIf(String::isNotBlank),
                        description = musicDetails.description.orEmpty(),
                        isPrivate = false,
                        videos = musicDetails.tracks.map { track -> track.toPlaylistVideo() },
                        thumbnailUrl = musicDetails.thumbnailUrl,
                        isLocalPlaylist = false,
                        isSaved = false,
                        isFromMusic = true,
                        isWatchLater = false,
                        isLoading = false,
                        errorMessage = null,
                    )
                }
                return
            }

            collections.offline(playlistId)?.let { offline ->
                _uiState.update {
                    it.copy(
                        playlistName = offline.collection.title,
                        ownerName = offline.collection.author.takeIf(String::isNotBlank),
                        videos = offline.videos,
                        thumbnailUrl = offline.collection.thumbnailUrl,
                        isLoading = false,
                        errorMessage = null,
                    )
                }
                return
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    errorMessage = context.getString(R.string.playlist_load_failed),
                )
            }
        }

        /** Appends the pages after the first as they arrive, so the list is usable while it grows. */
        private suspend fun loadRemainingPages(
            first: RemotePlaylistPage,
            firstToken: String,
        ) {
            val walk =
                walkPlaylistPages(
                    firstVideos = first.videos,
                    firstToken = firstToken,
                    maxPages = YouTubePlaylistRepository.PAGE_LIMIT,
                    nextPage = { playlistRepository.nextPage(playlistId, it) },
                    onPage = { videos -> _uiState.update { it.copy(videos = videos) } },
                )
            _uiState.update {
                it.copy(isLoadingMore = false, hidesUnavailable = walk.finished && first.hidesUnavailable(walk.videos.size))
            }
            if (walk.finished) playlistRepository.rememberComplete(playlistId, first.copy(videos = walk.videos))
        }
    }

private fun io.github.aedev.flow.data.music.model.MusicTrack.toPlaylistVideo(): Video =
    Video(
        id = videoId,
        title = title,
        channelName = artist,
        channelId = channelId,
        thumbnailUrl = thumbnailUrl,
        duration = duration,
        viewCount = views,
        uploadDate = "",
        isMusic = true,
    )
