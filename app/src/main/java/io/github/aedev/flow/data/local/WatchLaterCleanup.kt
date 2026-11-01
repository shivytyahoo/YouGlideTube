package io.github.aedev.flow.data.local

import io.github.aedev.flow.data.model.Video
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Takes watched videos out of Watch later when the viewer turned that on (#1060). A video leaves
 * as its progress crosses the watched threshold, and opening Watch later sweeps out any that were
 * watched elsewhere, with an Undo.
 */
@Singleton
class WatchLaterCleanup
    @Inject
    constructor(
        private val playlists: io.github.aedev.flow.data.repository.YouTubePlaylistRepository,
        private val preferences: PlayerPreferences,
        private val viewHistory: SessionHistory,
    ) {
        private companion object {
            const val WATCH_LATER_ID = io.github.aedev.flow.data.account.AccountPlaylistIds.WATCH_LATER
        }

        suspend fun onProgress(
            videoId: String,
            positionMs: Long,
            durationMs: Long,
        ) {
            if (!preferences.removeWatchedFromWatchLater.first()) return
            if (!preferences.watchedThreshold.first().isWatched(positionMs, durationMs)) return
            if (isInWatchLater(videoId)) playlists.removeVideo(WATCH_LATER_ID, videoId)
        }

        /** The viewer marked [videoId] as watched, or it played to its end. */
        suspend fun onFinished(videoId: String) {
            if (!preferences.removeWatchedFromWatchLater.first()) return
            if (isInWatchLater(videoId)) playlists.removeVideo(WATCH_LATER_ID, videoId)
        }

        /** Removes the watched ones among [videos] and returns them, so Undo can put them back. */
        suspend fun sweep(videos: List<Video>): List<Video> {
            if (videos.isEmpty() || !preferences.removeWatchedFromWatchLater.first()) return emptyList()
            val watched = watchedIds(videos, viewHistory.getVideoHistoryFlow().first(), preferences.watchedThreshold.first())
            if (watched.isEmpty()) return emptyList()
            return videos.filter { it.id in watched }.filter { video ->
                playlists.removeVideo(WATCH_LATER_ID, video.id)
            }
        }

        private suspend fun isInWatchLater(videoId: String): Boolean =
            io.github.aedev.flow.innertube.YouTube.isSignedIn() &&
                playlists.complete(WATCH_LATER_ID)?.videos?.any { it.id == videoId } == true
    }

internal fun watchedIds(
    videos: List<Video>,
    history: List<VideoHistoryEntry>,
    threshold: WatchedThreshold,
): List<String> {
    val progress = history.associateBy { it.videoId }
    return videos.mapNotNull { video ->
        video.id.takeIf { progress[it]?.let { entry -> threshold.isWatched(entry.position, entry.duration) } == true }
    }
}
