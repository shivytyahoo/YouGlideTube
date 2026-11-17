package io.github.shivytyahoo.glidetube.ui.screens.music.collection

import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.data.model.PlaylistInfo
import io.github.shivytyahoo.glidetube.data.music.model.MusicTrack
import io.github.shivytyahoo.glidetube.data.repository.YouTubePlaylistRepository
import io.github.shivytyahoo.glidetube.innertube.YouTube

/** Creates a playlist on the account; null when the account refused or is signed out. */
internal suspend fun createMusicPlaylist(
    name: String,
    description: String,
): PlaylistInfo? {
    if (!YouTube.isSignedIn()) return null
    val playlistId = runCatching { YouTube.createPlaylist(name) }.getOrNull()?.removePrefix("VL")
    if (playlistId.isNullOrBlank()) return null
    return PlaylistInfo(
        id = playlistId,
        name = name,
        description = description,
        videoCount = 0,
        thumbnailUrl = "",
        isPrivate = false,
        createdAt = System.currentTimeMillis(),
    )
}

/** Copies [tracks] onto an account playlist in one account write. */
internal suspend fun copyTracksToPlaylist(
    youTubePlaylists: YouTubePlaylistRepository,
    target: PlaylistInfo,
    tracks: List<MusicTrack>,
): CollectionMessage? {
    if (tracks.isEmpty()) return null
    val added = youTubePlaylists.addVideos(target.id, tracks.map { it.videoId }).size
    return if (added > 0) {
        CollectionMessage(
            pluralRes = R.plurals.merge_playlist_success,
            count = added,
            args = listOf(added, target.name),
        )
    } else {
        CollectionMessage(stringRes = R.string.toast_failed_to_merge_playlist)
    }
}
