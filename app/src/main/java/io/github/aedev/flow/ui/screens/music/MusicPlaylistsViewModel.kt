package io.github.aedev.flow.ui.screens.music

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.data.account.AccountPlaylists
import io.github.aedev.flow.data.account.toPlaylistInfo
import io.github.aedev.flow.data.local.entity.DownloadCollectionKind
import io.github.aedev.flow.data.model.Playlist
import io.github.aedev.flow.data.model.PlaylistInfo
import io.github.aedev.flow.data.music.model.toMusicTrack
import io.github.aedev.flow.data.video.BackgroundDownloadQueuer
import io.github.aedev.flow.data.video.downloader.collection.CollectionSpec
import io.github.aedev.flow.data.repository.YouTubePlaylistRepository
import io.github.aedev.flow.innertube.YouTube
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The music side of your playlists. Account-only: the list is your account's playlists, and
 * create / rename / delete go straight to the account.
 */
@HiltViewModel
class MusicPlaylistsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val accountPlaylists: AccountPlaylists,
        private val youTubePlaylists: YouTubePlaylistRepository,
        private val downloadQueuer: BackgroundDownloadQueuer,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(MusicPlaylistsUiState())
        val uiState: StateFlow<MusicPlaylistsUiState> = _uiState.asStateFlow()

        init {
            accountPlaylists.ensureLoaded()
            viewModelScope.launch {
                accountPlaylists.playlists.collect { playlists ->
                    _uiState.update {
                        it.copy(playlists = playlists.map { playlist -> playlist.toPlaylistInfo() }, isLoading = false)
                    }
                }
            }
        }

        fun downloadPlaylist(playlist: PlaylistInfo) {
            viewModelScope.launch {
                val videos = youTubePlaylists.complete(playlist.id)?.videos.orEmpty()
                if (videos.isEmpty()) {
                    Toast.makeText(context, context.getString(R.string.ui_playlist_empty), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                Toast
                    .makeText(
                        context,
                        context.getString(R.string.toast_starting_playlist_download, playlist.name),
                        Toast.LENGTH_SHORT,
                    ).show()
                downloadQueuer.queueCollectionSongs(
                    spec =
                        CollectionSpec(
                            id = playlist.id,
                            kind = DownloadCollectionKind.MUSIC_PLAYLIST,
                            title = playlist.name,
                            thumbnailUrl = playlist.thumbnailUrl.takeIf { it.isNotBlank() },
                        ),
                    tracks = videos.map { it.toMusicTrack() },
                    complete = true,
                )
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

        fun deletePlaylist(playlistId: String) {
            viewModelScope.launch {
                // The tombstone goes first and the server call follows, so the
                // playlist leaves the list the moment Delete is tapped; a
                // refused delete puts it back instead of vanishing one that
                // still exists, and the toast only fires when it really went.
                accountPlaylists.removePlaylist(playlistId)
                if (YouTube.deletePlaylist(playlistId).isSuccess) {
                    accountPlaylists.refresh()
                    Toast.makeText(context, context.getString(R.string.toast_playlist_deleted), Toast.LENGTH_SHORT).show()
                } else {
                    accountPlaylists.unremovePlaylist(playlistId)
                    accountPlaylists.refresh()
                    Toast.makeText(context, context.getString(R.string.toast_failed_to_delete_playlist), Toast.LENGTH_SHORT).show()
                }
            }
        }

        fun renamePlaylist(
            playlistId: String,
            newName: String,
        ) {
            viewModelScope.launch {
                YouTube.renamePlaylist(playlistId, newName)
                accountPlaylists.refresh()
                Toast.makeText(context, context.getString(R.string.toast_playlist_renamed), Toast.LENGTH_SHORT).show()
            }
        }

    }

data class MusicPlaylistsUiState(
    val playlists: List<PlaylistInfo> = emptyList(),
    val savedPlaylists: List<PlaylistInfo> = emptyList(),
    val isLoading: Boolean = false,
)
