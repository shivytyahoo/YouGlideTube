package io.github.shivytyahoo.glidetube.service

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import io.github.shivytyahoo.glidetube.player.RestoredMusicQueue
import io.github.shivytyahoo.glidetube.player.musicAudioUri
import io.github.shivytyahoo.glidetube.player.musicMediaItem

/**
 * The saved music queue as Media3 playback resumption wants it, so the system media controls and a
 * headset's play button bring music back after YouGlideTube was closed or killed. The items are the ones the
 * app itself builds, and the song starts where it stopped.
 */
@OptIn(UnstableApi::class)
internal fun musicResumptionItems(restored: RestoredMusicQueue): MediaSession.MediaItemsWithStartPosition {
    val items = restored.queue.map { track -> musicMediaItem(track, musicAudioUri(track.videoId)) }
    val trackIndex = restored.track?.let { track -> restored.queue.indexOfFirst { it.videoId == track.videoId } } ?: -1
    return MediaSession.MediaItemsWithStartPosition(
        items,
        if (trackIndex >= 0) trackIndex else restored.index,
        restored.resumePositionMs,
    )
}
