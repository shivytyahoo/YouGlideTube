package io.github.shivytyahoo.glidetube.ui.components.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.data.local.LikedVideoInfo
import io.github.shivytyahoo.glidetube.data.model.toMusicTrack
import io.github.shivytyahoo.glidetube.data.model.toVideo
import io.github.shivytyahoo.glidetube.data.music.model.MusicTrack
import io.github.shivytyahoo.glidetube.ui.components.library.LibraryMediaListRow
import io.github.shivytyahoo.glidetube.ui.components.shared.MediaKind
import io.github.shivytyahoo.glidetube.ui.components.shared.MediaRowAction

@Composable
internal fun LikedRow(
    like: LikedVideoInfo,
    musicQueue: List<MusicTrack>,
    onVideoClick: (MusicTrack) -> Unit,
    onMusicClick: (MusicTrack, List<MusicTrack>) -> Unit,
    onUnlike: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = remember(like) { like.toMusicTrack() }
    val unlikeLabel = stringResource(R.string.unlike)

    LibraryMediaListRow(
        track = track,
        video = remember(like) { like.toVideo() },
        isMusic = like.isMusic,
        title = like.title,
        onVideoClick = { onVideoClick(track) },
        onMusicClick = { onMusicClick(track, musicQueue) },
        removeLabel = unlikeLabel,
        onRemove = onUnlike,
        modifier = modifier,
        subtitle = like.channelName.takeIf { it.isNotBlank() },
        thumbnailUrl = like.thumbnail,
    ) {
        MediaRowAction(
            icon = Icons.Filled.ThumbUp,
            contentDescription = unlikeLabel,
            onClick = onUnlike,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

internal fun MediaKind.emptyTitleRes(): Int =
    when (this) {
        MediaKind.Videos -> R.string.empty_liked_videos
        MediaKind.Music -> R.string.empty_liked_music
    }

internal fun MediaKind.emptyBodyRes(): Int =
    when (this) {
        MediaKind.Videos -> R.string.empty_liked_body
        MediaKind.Music -> R.string.empty_liked_music_body
    }
