package io.github.aedev.flow.ui.components.shared

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.account.AccountPlaylistIds
import io.github.aedev.flow.data.account.AccountPlaylists
import io.github.aedev.flow.data.account.toPlaylistInfo
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
                val ids = mutableSetOf<String>()
                if (youTubePlaylistRepository.complete(AccountPlaylistIds.WATCH_LATER)?.videos?.any { it.id == videoId } == true) {
                    ids += AccountPlaylistIds.WATCH_LATER
                }
                for (playlist in accountPlaylists.playlists.value) {
                    val page = youTubePlaylistRepository.complete(playlist.id) ?: continue
                    if (page.videos.any { it.id == videoId }) ids += playlist.id
                }
                _savedIds.value = ids
                _membershipLoaded.value = true
            }
        }

        fun toggle(
            video: Video,
            collectionId: String,
        ) {
            if (!_membershipLoaded.value || !YouTube.isSignedIn()) return
            val wasSaved = collectionId in _savedIds.value
            _savedIds.update { if (wasSaved) it - collectionId else it + collectionId }
            viewModelScope.launch {
                val applied =
                    if (wasSaved) {
                        youTubePlaylistRepository.removeVideo(collectionId, video.id)
                    } else {
                        youTubePlaylistRepository.addVideo(collectionId, video.id)
                    }
                if (!applied) {
                    _savedIds.update { ids -> if (wasSaved) ids + collectionId else ids - collectionId }
                } else if (collectionId == AccountPlaylistIds.WATCH_LATER) {
                    _watchLaterVideos.value =
                        youTubePlaylistRepository.firstPage(AccountPlaylistIds.WATCH_LATER)?.videos.orEmpty()
                }
            }
        }

        fun createAndAdd(
            video: Video,
            name: String,
            description: String,
        ) {
            if (!YouTube.isSignedIn()) return
            viewModelScope.launch {
                val playlistId = runCatching { YouTube.createPlaylist(name) }.getOrNull()?.removePrefix("VL")
                if (playlistId.isNullOrBlank()) return@launch
                youTubePlaylistRepository.addVideo(playlistId, video.id)
                accountPlaylists.invalidate()
                accountPlaylists.refresh()
                _savedIds.update { it + playlistId }
            }
        }
    }
