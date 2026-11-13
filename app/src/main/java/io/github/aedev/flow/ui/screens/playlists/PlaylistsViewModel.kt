package io.github.aedev.flow.ui.screens.playlists

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.data.account.AccountPlaylists
import io.github.aedev.flow.data.account.AccountPlaylistIds
import io.github.aedev.flow.data.account.toPlaylistInfo
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.Playlist
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
        @ApplicationContext private val context: Context,
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
                        // Liked videos has its own section in Library; it is not a playlist
                        // card here either (the Library shelf hides it the same way).
                        it.copy(isLoading = false, playlists = playlists.filterNot { playlist -> playlist.id == AccountPlaylistIds.LIKED_VIDEOS }.map { playlist -> playlist.toPlaylistInfo() })
                    }
                }
            }
        }

        private val _isCreating = MutableStateFlow(false)
        val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

        fun createPlaylist(
            name: String,
            description: String,
            onDone: () -> Unit = {},
        ) {
            if (!YouTube.isSignedIn() || _isCreating.value) return
            _isCreating.value = true
            viewModelScope.launch {
                try {
                    val playlistId = runCatching { YouTube.createPlaylist(name) }.getOrNull()?.removePrefix("VL")
                    if (playlistId.isNullOrBlank()) {
                        Toast.makeText(context, context.getString(R.string.toast_failed_to_create_playlist), Toast.LENGTH_SHORT).show()
                    } else {
                        // The new playlist shows at once instead of after the
                        // next list re-read, which may not even carry it.
                        accountPlaylists.addEntry(
                            Playlist(
                                id = playlistId,
                                name = name,
                                thumbnailUrl = "",
                                videoCount = 0,
                                isLocal = false,
                            ),
                        )
                        accountPlaylists.refresh()
                    }
                } finally {
                    _isCreating.value = false
                    onDone()
                }
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
                // The tombstone goes first and the server call follows, so the
                // playlist leaves the list the moment Delete is tapped; a
                // refused delete puts it back instead of vanishing one that
                // still exists.
                accountPlaylists.removePlaylist(playlistId)
                if (YouTube.deletePlaylist(playlistId).isSuccess) {
                    accountPlaylists.refresh()
                } else {
                    accountPlaylists.unremovePlaylist(playlistId)
                    accountPlaylists.refresh()
                    Toast.makeText(context, context.getString(R.string.toast_failed_to_delete_playlist), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

data class PlaylistsUiState(
    val isLoading: Boolean = false,
    val playlists: List<PlaylistInfo> = emptyList(),
    val savedPlaylists: List<PlaylistInfo> = emptyList(),
)
