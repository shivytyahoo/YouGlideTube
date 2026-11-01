package io.github.aedev.flow.ui.screens.widgets

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.account.AccountPlaylists
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.repository.YouTubePlaylistRepository
import javax.inject.Inject

/** What a widget needs to start playing a playlist: its name and whether its tracks are songs. */
data class WidgetPlaylistInfo(
    val name: String,
    val isMusic: Boolean,
)

/** Reads what a widget asked to play, from the account's playlist pages. It loads nothing until asked. */
@HiltViewModel
class WidgetPlaybackViewModel
    @Inject
    constructor(
        private val accountPlaylists: AccountPlaylists,
        private val youTubePlaylists: YouTubePlaylistRepository,
    ) : ViewModel() {
        suspend fun playlist(id: String): Pair<WidgetPlaylistInfo?, List<Video>> {
            val videos = youTubePlaylists.complete(id)?.videos.orEmpty()
            if (videos.isEmpty()) return null to emptyList()
            accountPlaylists.ensureLoaded()
            val name =
                accountPlaylists.playlists.value
                    .firstOrNull { it.id == id }
                    ?.name
                    .orEmpty()
            return WidgetPlaylistInfo(name = name, isMusic = videos.all { it.isMusic }) to videos
        }
    }
