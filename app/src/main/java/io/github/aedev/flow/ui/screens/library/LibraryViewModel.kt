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
import io.github.aedev.flow.data.local.AccountHistoryCapture
import io.github.aedev.flow.data.local.SessionHistory
import io.github.aedev.flow.data.model.toVideo
import io.github.aedev.flow.data.playlist.sortedFor
import io.github.aedev.flow.data.shorts.ShortsContentFilter
import io.github.aedev.flow.ui.components.library.LIBRARY_SHELF_ITEM_LIMIT
import io.github.aedev.flow.ui.components.library.LibraryMediaItem
import io.github.aedev.flow.ui.components.library.LibrarySection
import io.github.aedev.flow.ui.components.library.toLibraryMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
        private val accountPlaylists: AccountPlaylists,
        private val youTubePlaylistRepository: YouTubePlaylistRepository,
        sessionLikes: SessionLikeState,
        sessionHistory: SessionHistory,
        accountHistoryCapture: AccountHistoryCapture,
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

        // Library playlists are the account's own (FEplaylist_aggregation) — there is no local store.
        // Liked videos is not a playlist card here: it has its own Liked section below, so the
        // aggregation tile for it would only duplicate it (2026-11-12). Watch Later stays —
        // it has no other home in the Library.
        private val allVideoPlaylists =
            combine(accountPlaylists.playlists, playlistOrder) { playlists, order ->
                playlists.filterNot { it.id == AccountPlaylistIds.LIKED_VIDEOS }.map { it.toPlaylistInfo() }.sortedFor(order)
            }.shared()

        // The account list is one list; the music shelf split was a local-store artifact.
        private val allMusicPlaylists: StateFlow<List<PlaylistInfo>?> =
            MutableStateFlow(emptyList())

        // Watch Later is the account's WL playlist, read through the remote playlist cache.
        private val allWatchLater = MutableStateFlow<List<io.github.aedev.flow.data.model.Video>?>(null)

        init {
            accountPlaylists.ensureLoaded()
            viewModelScope.launch { loadAccountLikes() }
            viewModelScope.launch { loadWatchLater() }
        }

        /**
         * Re-reads the account lists whenever the Library becomes visible: the
         * shelves are loaded once in init, so a playlist edit made on another
         * screen never reached them until the process died.
         */
        private val _isRefreshing = MutableStateFlow(false)
        val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

        internal fun refreshOnReturn(force: Boolean = false) {
            if (!force && refreshJob?.isActive == true) return
            if (force) refreshJob?.cancel()
            refreshJob =
                viewModelScope.launch {
                    _isRefreshing.value = force
                    try {
                        if (force) {
                            // The likes come from the remembered whole lists; a user refresh
                            // must re-walk them from the server, not re-read the cache.
                            youTubePlaylistRepository.invalidateComplete(AccountPlaylistIds.LIKED_VIDEOS)
                            youTubePlaylistRepository.invalidateComplete(AccountPlaylistIds.LIKED_MUSIC)
                        }
                        accountPlaylists.refresh()
                        loadWatchLater()
                        loadAccountLikes()
                        loadServerHistory()
                    } finally {
                        _isRefreshing.value = false
                    }
                }
        }

        private var refreshJob: Job? = null

        private suspend fun loadAccountLikes() {
            if (!io.github.aedev.flow.innertube.YouTube.isSignedIn()) return
            // A failed walk leaves the last good list in place: blanking the
            // shelf on a transient failure looks exactly like videos vanishing.
            val likedVideos =
                youTubePlaylistRepository.complete(io.github.aedev.flow.data.account.AccountPlaylistIds.LIKED_VIDEOS)?.videos ?: return
            val likedMusic =
                youTubePlaylistRepository.complete(io.github.aedev.flow.data.account.AccountPlaylistIds.LIKED_MUSIC)?.videos ?: return
            // LM is the account's liked list, not a content filter: a Short
            // or live stream liked in this app lands there too. Only genuine
            // songs stay music; the rest are liked videos.
            val musicVideos = likedMusic.filterNot { it.isShort || it.isLive }
            val routedVideos = likedMusic.filter { it.isShort || it.isLive }
            // One list's tile can arrive without its picture while the other list's tile for
            // the same video has it — a blank picture falls back to the other list, so the
            // Library shelves never show a blank tile for a video the account knows (2026-11-12).
            val llById = likedVideos.associateBy { it.id }
            val lmById = likedMusic.associateBy { it.id }
            val videoInfos =
                (likedVideos + routedVideos).distinctBy { it.id }.map {
                    io.github.aedev.flow.data.local.LikedVideoInfo(
                        videoId = it.id,
                        title = it.title,
                        thumbnail = it.thumbnailUrl.ifBlank { lmById[it.id]?.thumbnailUrl.orEmpty() },
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
                            thumbnail = it.thumbnailUrl.ifBlank { llById[it.id]?.thumbnailUrl.orEmpty() },
                            channelName = it.channelName,
                            channelId = it.channelId.takeIf(String::isNotBlank),
                            durationSeconds = it.duration,
                            isMusic = true,
                        )
                    }
        }

        private suspend fun loadWatchLater() {
            if (!io.github.aedev.flow.innertube.YouTube.isSignedIn()) {
                allWatchLater.value = emptyList()
                return
            }
            youTubePlaylistRepository.firstPage(AccountPlaylistIds.WATCH_LATER)?.videos?.let {
                allWatchLater.value = it
            }
        }

        // Signed in, the shelf is the account's own history (server); signed out it is this
        // session's plays. The server list loads once when the Library first asks for it.
        private val serverHistory = MutableStateFlow<List<io.github.aedev.flow.data.model.Video>?>(null)

        init {
            if (io.github.aedev.flow.innertube.YouTube.isSignedIn()) {
                viewModelScope.launch { loadServerHistory() }
            }
        }

        /**
         * The server list behind the Library's History shelf. It loads once at start and again
         * on every return to the Library: after a history clear the shelf kept showing the
         * pre-clear server videos while History > All was empty (2026-11-12), because nothing
         * ever re-read it. A failed reload keeps the last good list, like the likes reload.
         */
        private suspend fun loadServerHistory() {
            if (!io.github.aedev.flow.innertube.YouTube.isSignedIn()) return
            val page = io.github.aedev.flow.innertube.YouTube.serverHistory().getOrNull()
            if (page == null) {
                // First load with nothing yet: an empty list, so the shelf reads the server
                // branch; a later failure keeps the last good list instead of blanking it.
                if (serverHistory.value == null) serverHistory.value = emptyList()
                return
            }
            serverHistory.value = (page.videos + page.shorts).distinctBy { it.id }
        }

        internal val history =
            combine(
                sessionHistory.getRecentLibraryHistory(LIBRARY_SHELF_ITEM_LIMIT),
                serverHistory,
                accountHistoryCapture.entriesFlow,
            ) { sessionEntries, server, captured ->
                if (io.github.aedev.flow.innertube.YouTube.isSignedIn() && server != null) {
                    // Session plays head the shelf: the server list never learns of a
                    // watch made in this app, so without the merge the newest plays
                    // never show and the shelf looks unsorted.
                    val sessionItems = sessionEntries.map { it.toLibraryMediaItem() }
                    val seen = sessionItems.map { it.mediaId() }.toSet()
                    (sessionItems + server.map { LibraryMediaItem.VideoItem(it) }.filterNot { it.mediaId() in seen })
                        .take(LIBRARY_SHELF_ITEM_LIMIT)
                } else {
                    // Signed out, the shelf is the session's plays plus the last
                    // account snapshot, session rows first, no repeats.
                    val sessionItems = sessionEntries.map { it.toLibraryMediaItem() }
                    val seen = sessionItems.map { it.mediaId() }.toSet()
                    (sessionItems + captured.map { it.toLibraryMediaItem() }.filterNot { it.mediaId() in seen })
                        .take(LIBRARY_SHELF_ITEM_LIMIT)
                }
            }.shared()

        private val historyCount: Flow<Int> =
            combine(
                sessionHistory.getLibraryHistoryCount(),
                sessionHistory.getAllHistory(),
                serverHistory,
                accountHistoryCapture.entriesFlow,
            ) { sessionCount, allLocal, server, captured ->
                if (io.github.aedev.flow.innertube.YouTube.isSignedIn() && server != null) {
                    val localIds = allLocal.filter { !it.isShort && !it.isLocal }.map { it.videoId }.toSet()
                    localIds.size + server.map { it.id }.toSet().minus(localIds).size
                } else {
                    val localIds = allLocal.filter { !it.isShort && !it.isLocal }.map { it.videoId }.toSet()
                    sessionCount + captured.filter { !it.isShort }.map { it.videoId }.filterNot { it in localIds }.size
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

