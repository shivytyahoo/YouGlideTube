package io.github.aedev.flow.ui.screens.playlists

import io.github.aedev.flow.R
import io.github.aedev.flow.data.account.AccountPlaylists
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.repository.YouTubePlaylistRepository
import io.github.aedev.flow.innertube.YouTube

/** Adds every video to an account playlist, one account write per video. */
internal suspend fun mergeVideosIntoPlaylist(
    youTubePlaylists: YouTubePlaylistRepository,
    videos: List<Video>,
    targetPlaylistId: String,
    targetName: String = "",
): PlaylistUiMessage {
    var added = 0
    for (video in videos) {
        if (youTubePlaylists.addVideo(targetPlaylistId, video.id)) added++
    }
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
    videos: List<Video>,
    name: String,
): PlaylistUiMessage {
    if (!YouTube.isSignedIn()) return PlaylistUiMessage(stringRes = R.string.toast_failed_to_merge_playlist)
    val playlistId = runCatching { YouTube.createPlaylist(name) }.getOrNull()?.removePrefix("VL")
    if (playlistId.isNullOrBlank()) return PlaylistUiMessage(stringRes = R.string.toast_failed_to_merge_playlist)
    var added = 0
    for (video in videos) {
        if (YouTube.addToPlaylist(playlistId, video.id).isSuccess) added++
    }
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
