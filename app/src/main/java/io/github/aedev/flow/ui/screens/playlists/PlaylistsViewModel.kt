package io.github.aedev.flow.ui.screens.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.account.AccountPlaylists
import io.github.aedev.flow.data.account.toPlaylistInfo
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.PlaylistInfo
import io.github.aedev.flow.data.playlist.PlaylistListOrder
import io.github.aedev.flow.innertube.YouTube
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Your playlists page, account-only: the list is the account's playlists and creating or
 * deleting goes straight to the account. There is no local or imported list anymore.
 */
@HiltViewModel
class PlaylistsViewModel
    @Inject
    constructor(
        private val accountPlaylists: AccountPlaylists,
        private val preferences: PlayerPreferences,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(PlaylistsUiState(isLoading = true))
        val uiState: StateFlow<PlaylistsUiState> = _uiState.asStateFlow()

        private val sharing = SharingStarted.WhileSubscribed(5_000)

        val listOrder: StateFlow<PlaylistListOrder> =
            preferences.playlistListOrder.stateIn(viewModelScope, sharing, PlaylistListOrder.NEWEST)

        val compactLayout: StateFlow<Boolean> = preferences.playlistsCompactLayout.stateIn(viewModelScope, sharing, false)

        /** Null until read, so the page never flashes Videos before switching to a remembered Music. */
        val showMusic: StateFlow<Boolean?> = preferences.playlistsShowMusic.stateIn(viewModelScope, sharing, null)

        init {
            accountPlaylists.ensureLoaded()
            viewModelScope.launch {
                accountPlaylists.playlists.collect { playlists ->
                    _uiState.update {
                        it.copy(isLoading = false, playlists = playlists.map { playlist -> playlist.toPlaylistInfo() })
                    }
                }
            }
        }

        fun createPlaylist(
            name: String,
            description: String,
        ) {
            if (!YouTube.isSignedIn()) return
            viewModelScope.launch {
                runCatching { YouTube.createPlaylist(name) }
                accountPlaylists.refresh()
            }
        }

        fun setListOrder(order: PlaylistListOrder) {
            viewModelScope.launch { preferences.setPlaylistListOrder(order) }
        }

        fun setCompactLayout(compact: Boolean) {
            viewModelScope.launch { preferences.setPlaylistsCompactLayout(compact) }
        }

        fun setShowMusic(showMusic: Boolean) {
            viewModelScope.launch { preferences.setPlaylistsShowMusic(showMusic) }
        }

        fun reorderPlaylists(orderedIds: List<String>) {
            // Account playlists keep the account's order; there is no reorder API here.
        }

        fun deletePlaylist(playlistId: String) {
            viewModelScope.launch {
                YouTube.deletePlaylist(playlistId)
                accountPlaylists.refresh()
            }
        }
    }

data class PlaylistsUiState(
    val isLoading: Boolean = false,
    val playlists: List<PlaylistInfo> = emptyList(),
    val savedPlaylists: List<PlaylistInfo> = emptyList(),
)
