package io.github.aedev.flow.ui.screens.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.account.AccountPlaylists
import io.github.aedev.flow.data.account.toPlaylistInfo
import io.github.aedev.flow.data.model.PlaylistInfo
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.repository.YouTubePlaylistRepository
import io.github.aedev.flow.innertube.YouTube
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val SHARING_TIMEOUT_MS = 5_000L

/**
 * Which of your playlists hold one song, and adding it to or removing it from them. Account-only:
 * the list is the account's playlists and membership is read from the account's playlist pages.
 */
@HiltViewModel
class SaveSongViewModel
    @Inject
    constructor(
        private val accountPlaylists: AccountPlaylists,
        private val youTubePlaylists: YouTubePlaylistRepository,
    ) : ViewModel() {
        val playlists: StateFlow<List<PlaylistInfo>> =
            accountPlaylists.playlists
                .map { list -> list.map { it.toPlaylistInfo() } }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SHARING_TIMEOUT_MS), emptyList())

        private val _savedIds = MutableStateFlow<Set<String>>(emptySet())
        val savedIds: StateFlow<Set<String>> = _savedIds.asStateFlow()

        private val membershipLoaded = MutableStateFlow(false)

        init {
            accountPlaylists.ensureLoaded()
        }

        fun loadMembership(videoId: String) {
            membershipLoaded.value = false
            _savedIds.value = emptySet()
            if (!YouTube.isSignedIn()) {
                membershipLoaded.value = true
                return
            }
            viewModelScope.launch {
                val memberOf = mutableSetOf<String>()
                for (playlist in accountPlaylists.playlists.value) {
                    val videos = youTubePlaylists.complete(playlist.id)?.videos.orEmpty()
                    if (videos.any { it.id == videoId }) memberOf += playlist.id
                }
                _savedIds.value = memberOf
                membershipLoaded.value = true
            }
        }

        fun toggle(
            track: MusicTrack,
            playlistId: String,
        ) {
            if (!membershipLoaded.value) return
            val wasSaved = playlistId in _savedIds.value
            _savedIds.update { if (wasSaved) it - playlistId else it + playlistId }
            viewModelScope.launch {
                val ok =
                    if (wasSaved) {
                        youTubePlaylists.removeVideo(playlistId, track.videoId)
                    } else {
                        youTubePlaylists.addVideo(playlistId, track.videoId)
                    }
                if (!ok) _savedIds.update { ids -> if (wasSaved) ids + playlistId else ids - playlistId }
            }
        }

        fun createAndAdd(
            track: MusicTrack,
            name: String,
            description: String,
        ) {
            if (!YouTube.isSignedIn()) return
            viewModelScope.launch {
                val playlistId = runCatching { YouTube.createPlaylist(name) }.getOrNull()?.removePrefix("VL")
                if (playlistId.isNullOrBlank()) return@launch
                youTubePlaylists.addVideo(playlistId, track.videoId)
                accountPlaylists.refresh()
                _savedIds.update { it + playlistId }
            }
        }
    }
