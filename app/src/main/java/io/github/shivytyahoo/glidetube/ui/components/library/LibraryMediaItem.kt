package io.github.shivytyahoo.glidetube.ui.components.library

import io.github.shivytyahoo.glidetube.data.local.LikedVideoInfo
import io.github.shivytyahoo.glidetube.data.local.VideoHistoryEntry
import io.github.shivytyahoo.glidetube.data.model.Video
import io.github.shivytyahoo.glidetube.data.model.toMusicTrack
import io.github.shivytyahoo.glidetube.data.model.toVideo
import io.github.shivytyahoo.glidetube.data.music.DownloadedTrack
import io.github.shivytyahoo.glidetube.data.music.model.MusicTrack
import io.github.shivytyahoo.glidetube.data.video.DownloadedVideo

internal const val LIBRARY_SHELF_ITEM_LIMIT = 20

internal sealed interface LibraryMediaItem {
    val key: String

    data class VideoItem(
        val video: Video,
    ) : LibraryMediaItem {
        override val key: String = "video:${video.id}"
    }

    data class MusicItem(
        val track: MusicTrack,
    ) : LibraryMediaItem {
        override val key: String = "music:${track.videoId}"
    }

    data class DownloadedVideoItem(
        val download: DownloadedVideo,
    ) : LibraryMediaItem {
        override val key: String = "downloaded-video:${download.video.id}"
    }

    data class DownloadedMusicItem(
        val download: DownloadedTrack,
    ) : LibraryMediaItem {
        override val key: String = "downloaded-music:${download.track.videoId}"
    }
}

internal fun VideoHistoryEntry.toLibraryMediaItem(): LibraryMediaItem =
    if (isMusic) {
        LibraryMediaItem.MusicItem(toMusicTrack())
    } else {
        LibraryMediaItem.VideoItem(toVideo())
    }

internal fun LikedVideoInfo.toLibraryMediaItem(): LibraryMediaItem =
    if (isMusic) {
        LibraryMediaItem.MusicItem(toMusicTrack())
    } else {
        LibraryMediaItem.VideoItem(toVideo())
    }
