package io.github.aedev.flow.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.engagement.SessionLikeState
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.account.AccountPlaylists
import io.github.aedev.flow.data.account.AccountPlaylistIds
import io.github.aedev.flow.data.account.toPlaylistInfo
import io.github.aedev.flow.data.model.PlaylistInfo
import io.github.aedev.flow.data.repository.YouTubePlaylistRepository
import io.github.aedev.flow.data.video.VideoDownloadManager
import io.github.aedev.flow.data.music.DownloadManager as MusicDownloadManager
import io.github.aedev.flow.data.local.SessionHistory
import io.github.aedev.flow.data.model.toVideo
import io.github.aedev.flow.data.playlist.sortedFor
import io.github.aedev.flow.data.shorts.ShortsContentFilter
import io.github.aedev.flow.ui.components.library.LIBRARY_SHELF_ITEM_LIMIT
import io.github.aedev.flow.ui.components.library.LibraryMediaItem
import io.github.aedev.flow.ui.components.library.LibrarySection
import io.github.aedev.flow.ui.components.library.toLibraryMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

internal data class LibraryCounts(
    val history: Int,
    val videoPlaylists: Int,
    val musicPlaylists: Int,
    val watchLater: Int,
    val likedVideos: Int,
    val likedMusic: Int,
    val downloadedVideos: Int,
    val downloadedTracks: Int,
) {
    val isEmpty: Boolean
        get() =
            history == 0 &&
                videoPlaylists == 0 &&
                musicPlaylists == 0 &&
                watchLater == 0 &&
                likedVideos == 0 &&
                likedMusic == 0 &&
                downloadedVideos == 0 &&
                downloadedTracks == 0
}

@HiltViewModel
class LibraryViewModel
    @Inject
    constructor(
        accountPlaylists: AccountPlaylists,
        youTubePlaylistRepository: YouTubePlaylistRepository,
        sessionLikes: SessionLikeState,
        sessionHistory: SessionHistory,
        shortsContentFilter: ShortsContentFilter,
        playerPreferences: PlayerPreferences,
        videoDownloadManager: VideoDownloadManager,
        musicDownloadManager: MusicDownloadManager,
    ) : ViewModel() {
        private val sharing = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L)

        private fun <T> Flow<T>.shared(): StateFlow<T?> {
            val upstream: Flow<T?> = this
            return upstream
                .distinctUntilChanged()
                .flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, sharing, null)
        }

        // Liked shelves are the account's LL/LM lists when signed in (loaded once below);
        // signed out they show what this session liked.
        private val accountLikes = MutableStateFlow<List<io.github.aedev.flow.data.local.LikedVideoInfo>?>(null)

        // Signed in, session likes (newest first, live) head the shelf and the server
        // list fills the rest: the server list is loaded once and never learns of a
        // like made in this session, so without the merge the newest likes never show.
        private val mergedAccountLikes: StateFlow<List<io.github.aedev.flow.data.local.LikedVideoInfo>?> =
            combine(
                accountLikes,
                sessionLikes.likedInfos(),
                sessionLikes.dislikedIds(),
            ) { server, session, disliked ->
                if (server == null) {
                    null
                } else {
                    val sessionIds = session.map { it.videoId }.toSet()
                    session + server.filterNot { it.videoId in sessionIds || it.videoId in disliked }
                }
            }.shared()

        private val allLikes: StateFlow<List<io.github.aedev.flow.data.local.LikedVideoInfo>?> =
            if (io.github.aedev.flow.innertube.YouTube.isSignedIn()) {
                mergedAccountLikes
            } else {
                sessionLikes
                    .likedInfos()
                    .distinctUntilChanged()
                    .flowOn(Dispatchers.Default)
                    .stateIn(viewModelScope, sharing, null)
            }
        private val playlistOrder = playerPreferences.playlistListOrder

        // Library playlists are the account's own (FEplaylists) — there is no local store.
        private val allVideoPlaylists =
            combine(accountPlaylists.playlists, playlistOrder) { playlists, order ->
                playlists.map { it.toPlaylistInfo() }.sortedFor(order)
            }.shared()

        // The account list is one list; the music shelf split was a local-store artifact.
        private val allMusicPlaylists: StateFlow<List<PlaylistInfo>?> =
            MutableStateFlow(emptyList())

        // Watch Later is the account's WL playlist, read through the remote playlist cache.
        private val allWatchLater = MutableStateFlow<List<io.github.aedev.flow.data.model.Video>?>(null)

        init {
            accountPlaylists.ensureLoaded()
            if (io.github.aedev.flow.innertube.YouTube.isSignedIn()) {
                viewModelScope.launch {
                    val likedVideos =
                        youTubePlaylistRepository.complete(io.github.aedev.flow.data.account.AccountPlaylistIds.LIKED_VIDEOS)?.videos.orEmpty()
                    val likedMusic =
                        youTubePlaylistRepository.complete(io.github.aedev.flow.data.account.AccountPlaylistIds.LIKED_MUSIC)?.videos.orEmpty()
                    // LM is the account's liked list, not a content filter: a Short
                    // or live stream liked in this app lands there too. Only genuine
                    // songs stay music; the rest are liked videos.
                    val musicVideos = likedMusic.filterNot { it.isShort || it.isLive }
                    val routedVideos = likedMusic.filter { it.isShort || it.isLive }
                    val videoInfos =
                        (likedVideos + routedVideos).distinctBy { it.id }.map {
                            io.github.aedev.flow.data.local.LikedVideoInfo(
                                videoId = it.id,
                                title = it.title,
                                thumbnail = it.thumbnailUrl,
                                channelName = it.channelName,
                                channelId = it.channelId.takeIf(String::isNotBlank),
                                durationSeconds = it.duration,
                            )
                        }
                    accountLikes.value =
                        videoInfos +
                            musicVideos.map {
                                io.github.aedev.flow.data.local.LikedVideoInfo(
                                    videoId = it.id,
                                    title = it.title,
                                    thumbnail = it.thumbnailUrl,
                                    channelName = it.channelName,
                                    channelId = it.channelId.takeIf(String::isNotBlank),
                                    durationSeconds = it.duration,
                                    isMusic = true,
                                )
                            }
                }
            }
            viewModelScope.launch {
                allWatchLater.value =
                    if (io.github.aedev.flow.innertube.YouTube.isSignedIn()) {
                        youTubePlaylistRepository.firstPage(AccountPlaylistIds.WATCH_LATER)?.videos.orEmpty()
                    } else {
                        emptyList()
                    }
            }
        }

        // Signed in, the shelf is the account's own history (server); signed out it is this
        // session's plays. The server list loads once when the Library first asks for it.
        private val serverHistory = MutableStateFlow<List<io.github.aedev.flow.data.model.Video>?>(null)

        init {
            if (io.github.aedev.flow.innertube.YouTube.isSignedIn()) {
                viewModelScope.launch {
                    val page = io.github.aedev.flow.innertube.YouTube.serverHistory().getOrNull()
                    serverHistory.value =
                        if (page == null) {
                            emptyList()
                        } else {
                            (page.videos + page.shorts).distinctBy { it.id }
                        }
                }
            }
        }

        internal val history =
            combine(
                sessionHistory.getRecentLibraryHistory(LIBRARY_SHELF_ITEM_LIMIT),
                serverHistory,
            ) { sessionEntries, server ->
                if (io.github.aedev.flow.innertube.YouTube.isSignedIn() && server != null) {
                    // Session plays head the shelf: the server list never learns of a
                    // watch made in this app, so without the merge the newest plays
                    // never show and the shelf looks unsorted.
                    val sessionItems = sessionEntries.map { it.toLibraryMediaItem() }
                    val seen = sessionItems.map { it.mediaId() }.toSet()
                    (sessionItems + server.map { LibraryMediaItem.VideoItem(it) }.filterNot { it.mediaId() in seen })
                        .take(LIBRARY_SHELF_ITEM_LIMIT)
                } else {
                    sessionEntries.map { it.toLibraryMediaItem() }
                }
            }.shared()

        private val historyCount: Flow<Int> =
            combine(
                sessionHistory.getLibraryHistoryCount(),
                sessionHistory.getAllHistory(),
                serverHistory,
            ) { sessionCount, allLocal, server ->
                if (io.github.aedev.flow.innertube.YouTube.isSignedIn() && server != null) {
                    val localIds = allLocal.filter { !it.isShort && !it.isLocal }.map { it.videoId }.toSet()
                    localIds.size + server.map { it.id }.toSet().minus(localIds).size
                } else {
                    sessionCount
                }
            }

        internal val likedVideos =
            allLikes
                .map { liked ->
                    liked
                        ?.asSequence()
                        ?.filterNot { it.isMusic }
                        ?.take(LIBRARY_SHELF_ITEM_LIMIT)
                        ?.map { it.toVideo() }
                        ?.toList()
                }.shared()

        internal val likedMusic =
            allLikes
                .map { liked ->
                    liked
                        ?.asSequence()
                        ?.filter { it.isMusic }
                        ?.take(LIBRARY_SHELF_ITEM_LIMIT)
                        ?.map { it.toLibraryMediaItem() }
                        ?.toList()
                }.shared()

        internal val playlists = allVideoPlaylists.map { it?.take(LIBRARY_SHELF_ITEM_LIMIT) }.shared()

        internal val musicPlaylists = allMusicPlaylists.map { it?.take(LIBRARY_SHELF_ITEM_LIMIT) }.shared()

        internal val watchLater = allWatchLater.map { it?.take(LIBRARY_SHELF_ITEM_LIMIT) }.shared()

        private val allDownloads =
            combine(
                videoDownloadManager.downloadedVideos,
                musicDownloadManager.downloadedTracks,
            ) { videos, tracks -> videos to tracks }.shared()

        internal val downloads =
            allDownloads
                .map { downloaded ->
                    downloaded?.let { (videos, tracks) ->
                        buildList<LibraryMediaItem> {
                            videos.forEach { add(LibraryMediaItem.DownloadedVideoItem(it)) }
                            tracks.forEach { add(LibraryMediaItem.DownloadedMusicItem(it)) }
                        }.sortedByDescending { item -> item.downloadedAt }
                            .take(LIBRARY_SHELF_ITEM_LIMIT)
                    }
                }.shared()

        internal val shortsEnabled =
            shortsContentFilter.enabled
                .stateIn(viewModelScope, sharing, true)

        internal val separatePlaylistKinds =
            playerPreferences.separatePlaylistKinds
                .stateIn(viewModelScope, sharing, false)

        /** Null until the stored choice is read, so a hidden section never shows for a frame. */
        internal val hiddenSections: StateFlow<Set<LibrarySection>?> =
            playerPreferences.hiddenLibrarySections
                .map(LibrarySection::fromStored)
                .stateIn(viewModelScope, sharing, null)

        internal val shelfPreviewsEnabled =
            playerPreferences.libraryShelfPreviewsEnabled
                .distinctUntilChanged()
                .stateIn(viewModelScope, sharing, true)

        /**
         * Counts come off the same shared sources the shelves read, so the compact layout costs one
         * extra `COUNT(*)` on watch history and nothing else.
         */
        internal val counts: StateFlow<LibraryCounts?> =
            combine(
                historyCount,
                combine(allVideoPlaylists, allMusicPlaylists) { video, music ->
                    if (video == null || music == null) null else video.size to music.size
                },
                allWatchLater,
                allLikes,
                allDownloads,
            ) { historyCount, playlistCount, watchLater, liked, downloaded ->
                if (downloaded == null) {
                    null
                } else

                if (playlistCount == null || watchLater == null || liked == null) {
                    null
                } else {
                    LibraryCounts(
                        history = historyCount,
                        videoPlaylists = playlistCount.first,
                        musicPlaylists = playlistCount.second,
                        watchLater = watchLater.size,
                        likedVideos = liked.count { !it.isMusic },
                        likedMusic = liked.count { it.isMusic },
                        downloadedVideos = downloaded.first.size,
                        downloadedTracks = downloaded.second.size,
                    )
                }
            }.distinctUntilChanged()
                .flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, sharing, null)

        /** Queued, running and paused downloads; null until Room first answers. */
        internal val activeDownloadCount: StateFlow<Int?> =
            videoDownloadManager.activeDownloads
                .map { it.size }
                .distinctUntilChanged()
                .flowOn(Dispatchers.IO)
                .stateIn(viewModelScope, sharing, null)

        internal val isLibraryEmpty =
            combine(counts, activeDownloadCount, ::libraryIsEmpty)
                .distinctUntilChanged()
                .stateIn(viewModelScope, sharing, false)
    }

/**
 * Empty only once both counts are known: a download still in progress is library content too, and
 * deciding before Room answers would flash the empty state.
 */
private fun LibraryMediaItem.mediaId(): String =
    when (this) {
        is LibraryMediaItem.VideoItem -> video.id
        is LibraryMediaItem.MusicItem -> track.videoId
        is LibraryMediaItem.DownloadedVideoItem -> download.video.id
        is LibraryMediaItem.DownloadedMusicItem -> download.track.videoId
    }

internal fun libraryIsEmpty(
    counts: LibraryCounts?,
    activeDownloads: Int?,
): Boolean = counts != null && activeDownloads == 0 && counts.isEmpty

private val LibraryMediaItem.downloadedAt: Long
    get() =
        when (this) {
            is LibraryMediaItem.DownloadedVideoItem -> download.downloadedAt
            is LibraryMediaItem.DownloadedMusicItem -> download.downloadedAt
            else -> 0L
        }

