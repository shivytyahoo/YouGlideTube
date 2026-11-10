package io.github.aedev.flow.ui.screens.playlists

import io.github.aedev.flow.R
import io.github.aedev.flow.data.account.AccountPlaylists
import io.github.aedev.flow.data.model.Playlist
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.repository.YouTubePlaylistRepository
import io.github.aedev.flow.innertube.YouTube

/** Adds every video to an account playlist in one account write. */
internal suspend fun mergeVideosIntoPlaylist(
    youTubePlaylists: YouTubePlaylistRepository,
    videos: List<Video>,
    targetPlaylistId: String,
    targetName: String = "",
): PlaylistUiMessage {
    val added = youTubePlaylists.addVideos(targetPlaylistId, videos.map { it.id }).size
    return if (added > 0) {
        PlaylistUiMessage(
            pluralRes = R.plurals.merge_playlist_success,
            count = added,
            args = listOf(added, targetName),
        )
    } else {
        PlaylistUiMessage(stringRes = R.string.toast_failed_to_merge_playlist)
    }
}

/** Creates an account playlist and copies every video onto it. */
internal suspend fun createVideoPlaylistAndMerge(
    accountPlaylists: AccountPlaylists,
    videos: List<Video>,
    name: String,
): PlaylistUiMessage {
    if (!YouTube.isSignedIn()) return PlaylistUiMessage(stringRes = R.string.toast_failed_to_merge_playlist)
    val playlistId = runCatching { YouTube.createPlaylist(name) }.getOrNull()?.removePrefix("VL")
    if (playlistId.isNullOrBlank()) return PlaylistUiMessage(stringRes = R.string.toast_failed_to_merge_playlist)
    accountPlaylists.addEntry(
        Playlist(
            id = playlistId,
            name = name,
            thumbnailUrl = "",
            videoCount = 0,
            isLocal = false,
        ),
    )
    val added = YouTube.addVideosToPlaylist(playlistId, videos.map { it.id }).size
    return if (added > 0) {
        PlaylistUiMessage(
            pluralRes = R.plurals.merge_playlist_success,
            count = added,
            args = listOf(added, name),
        )
    } else {
        PlaylistUiMessage(stringRes = R.string.toast_failed_to_merge_playlist)
    }
}
