package io.github.aedev.flow.ui.components.shared

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.account.AccountPlaylistIds
import io.github.aedev.flow.data.account.AccountPlaylists
import io.github.aedev.flow.data.account.toPlaylistInfo
import io.github.aedev.flow.data.model.Playlist
import io.github.aedev.flow.data.model.PlaylistInfo
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.repository.YouTubePlaylistRepository
import io.github.aedev.flow.innertube.YouTube
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val SHARING_TIMEOUT_MS = 5_000L

/**
 * The save sheet's state: the account's own playlists plus its Watch Later, and which of
 * them already hold the video. Everything is account-backed — signed out there is nothing
 * to save to, and the sheet shows its empty state.
 */
@HiltViewModel
class SaveVideoViewModel
    @Inject
    constructor(
        private val accountPlaylists: AccountPlaylists,
        private val youTubePlaylistRepository: YouTubePlaylistRepository,
    ) : ViewModel() {
        private val _savedIds = MutableStateFlow<Set<String>>(emptySet())
        val savedIds: StateFlow<Set<String>> = _savedIds.asStateFlow()

        private val _membershipLoaded = MutableStateFlow(false)
        val membershipLoaded: StateFlow<Boolean> = _membershipLoaded.asStateFlow()

        private val _isCreating = MutableStateFlow(false)
        val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

        // Taps that landed before the membership load finished, by playlist id to the
        // saved state the tap asked for. The load must not overwrite them, or a quick
        // tap looks like there is no way to add the video at all.
        private val pendingToggles = mutableMapOf<String, Boolean>()

        // Collections with an edit still in flight. A second tap meanwhile would race
        // it against a stale membership snapshot — report 15: duplicate adds, then
        // 400/409 on remove with a setVideoId the server had already moved past.
        private val togglingIds = mutableSetOf<String>()

        private var loadedVideoId: String? = null

        val playlists: StateFlow<List<PlaylistInfo>> =
            accountPlaylists.playlists
                .map { list -> list.map { it.toPlaylistInfo() } }
                .distinctUntilChanged()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(stopTimeoutMillis = SHARING_TIMEOUT_MS),
                    emptyList(),
                )

        private val _watchLaterVideos = MutableStateFlow<List<Video>>(emptyList())
        val watchLaterVideos: StateFlow<List<Video>> = _watchLaterVideos.asStateFlow()

        init {
            accountPlaylists.ensureLoaded()
            viewModelScope.launch {
                if (YouTube.isSignedIn()) {
                    _watchLaterVideos.value =
                        youTubePlaylistRepository.firstPage(AccountPlaylistIds.WATCH_LATER)?.videos.orEmpty()
                }
            }
        }

        fun loadMembership(videoId: String) {
            if (loadedVideoId == videoId) return
            loadedVideoId = videoId
            _membershipLoaded.value = false
            viewModelScope.launch {
                if (!YouTube.isSignedIn()) {
                    _savedIds.value = emptySet()
                    _membershipLoaded.value = true
                    return@launch
                }
                // A playlist created from Save in an earlier session only shows up if
                // the account list is fresh — the sheet re-fetches it on every open.
                accountPlaylists.refresh()
                val ids = mutableSetOf<String>()
                if (youTubePlaylistRepository.complete(AccountPlaylistIds.WATCH_LATER)?.videos?.any { it.id == videoId } == true) {
                    ids += AccountPlaylistIds.WATCH_LATER
                }
                for (playlist in accountPlaylists.playlists.value) {
                    // Liked videos / Liked music are not save targets (see SaveVideoSheet),
                    // so their membership is never shown and the fetch is skipped.
                    if (playlist.id == AccountPlaylistIds.LIKED_VIDEOS ||
                        playlist.id == AccountPlaylistIds.LIKED_MUSIC
                    ) {
                        continue
                    }
                    val page = youTubePlaylistRepository.complete(playlist.id) ?: continue
                    if (page.videos.any { it.id == videoId }) ids += playlist.id
                }
                for ((id, wantSaved) in pendingToggles) {
                    if (wantSaved) ids += id else ids -= id
                }
                pendingToggles.clear()
                _savedIds.value = ids
                _membershipLoaded.value = true
            }
        }

        fun toggle(
            video: Video,
            collectionId: String,
        ) {
            if (!YouTube.isSignedIn()) return
            if (!togglingIds.add(collectionId)) return
            val wasSaved = collectionId in _savedIds.value
            _savedIds.update { if (wasSaved) it - collectionId else it + collectionId }
            if (!_membershipLoaded.value) pendingToggles[collectionId] = !wasSaved
            viewModelScope.launch {
                try {
                    val applied =
                        if (wasSaved) {
                            youTubePlaylistRepository.removeVideo(collectionId, video.id)
                        } else {
                            youTubePlaylistRepository.addVideo(collectionId, video.id)
                        }
                    if (!applied) {
                        pendingToggles.remove(collectionId)
                        _savedIds.update { ids -> if (wasSaved) ids + collectionId else ids - collectionId }
                    } else if (collectionId == AccountPlaylistIds.WATCH_LATER) {
                        _watchLaterVideos.value =
                            youTubePlaylistRepository.firstPage(AccountPlaylistIds.WATCH_LATER)?.videos.orEmpty()
                    }
                } finally {
                    togglingIds.remove(collectionId)
                }
            }
        }

        fun createAndAdd(
            video: Video,
            name: String,
            description: String,
            onDone: () -> Unit = {},
        ) {
            if (!YouTube.isSignedIn() || _isCreating.value) return
            _isCreating.value = true
            viewModelScope.launch {
                try {
                    val playlistId = runCatching { YouTube.createPlaylist(name) }.getOrNull()?.removePrefix("VL")
                    if (playlistId.isNullOrBlank()) return@launch
                    accountPlaylists.addEntry(
                        Playlist(
                            id = playlistId,
                            name = name,
                            thumbnailUrl = "",
                            videoCount = 0,
                            isLocal = false,
                        ),
                    )
                    youTubePlaylistRepository.addVideo(playlistId, video.id)
                    accountPlaylists.invalidate()
                    accountPlaylists.refresh()
                    _savedIds.update { it + playlistId }
                } finally {
                    _isCreating.value = false
                    onDone()
                }
            }
        }
    }
