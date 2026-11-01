package io.github.aedev.flow.ui.screens.music

import android.content.Context
import android.widget.Toast
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.LikedVideoInfo
import io.github.aedev.flow.data.engagement.SessionLikeState
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.music.DownloadManager
import io.github.aedev.flow.data.music.MusicSessionLibrary
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.scrobble.Scrobbler
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the player can do to a track besides playing it: like, hide its artist, queue and download. */
internal class MusicPlayerTrackActions(
    private val context: Context,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<MusicPlayerUiState>,
    private val musicSessionLibrary: MusicSessionLibrary,
    private val downloadManager: DownloadManager,
    private val sessionLikes: SessionLikeState,
    private val sessionExclusions: io.github.aedev.flow.data.engagement.SessionExclusions,
    private val playerPreferences: PlayerPreferences,
    private val scrobbler: Scrobbler,
) {
    fun toggleLike() {
        val currentTrack = uiState.value.currentTrack ?: return
        if (LocalMediaIds.isLocal(currentTrack.videoId)) return

        // The like state decides, not the favorites list: a song liked on another device or from
        // the video player is liked without being a favorite, and flipping the list missed it.
        val like = !uiState.value.isLiked
        uiState.update { it.copy(isLiked = like) }
        scrobbler.onLikeChanged(currentTrack, like)
        scope.launch(PerformanceDispatcher.diskIO) {
            if (like) {
                musicSessionLibrary.addToFavorites(currentTrack)
                if (playerPreferences.autoDownloadLikedMusic.first()) downloadManager.downloadTrack(currentTrack)
                sessionLikes.like(
                    LikedVideoInfo(
                        videoId = currentTrack.videoId,
                        title = currentTrack.title,
                        thumbnail = currentTrack.thumbnailUrl,
                        channelName = currentTrack.artist,
                        isMusic = true,
                        channelId = currentTrack.channelId.takeIf(String::isNotBlank),
                        durationSeconds = currentTrack.duration,
                    ),
                )
            } else {
                musicSessionLibrary.removeFromFavorites(currentTrack.videoId)
                sessionLikes.removeLikeState(currentTrack.videoId)
            }
        }
    }

    fun downloadTrack(track: MusicTrack? = null) {
        val trackToDownload = track ?: uiState.value.currentTrack ?: return

        if (uiState.value.downloadedTrackIds.contains(trackToDownload.videoId)) {
            scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                Toast.makeText(context, context.getString(R.string.already_downloaded_toast), Toast.LENGTH_SHORT).show()
            }
            return
        }

        scope.launch {
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                Toast.makeText(context, context.getString(R.string.download_started_toast), Toast.LENGTH_SHORT).show()
            }

            try {
                downloadManager.downloadTrack(trackToDownload)
            } catch (e: Exception) {
                android.util.Log.e("MusicDownload", "Download start exception", e)
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    val msg = context.getString(R.string.download_error_toast, e.message ?: "")
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * "Not interested": soft-suppresses the track's artist for two weeks. A
     * second one while still suppressed escalates to a permanent block —
     * mirrored from the desktop two-layer feedback system.
     */
    fun notInterested(track: MusicTrack) {
        val primary = track.artists.firstOrNull()
        scope.launch(PerformanceDispatcher.diskIO) {
            val channelId = primary?.id ?: track.channelId.takeIf { it.isNotBlank() }
            if (channelId != null) sessionExclusions.suppressChannel(channelId)
        }
    }

    /** "Don't recommend {artist}": an immediate permanent hard block, reversible in settings. */
    fun dontRecommendArtist(track: MusicTrack) {
        val primary = track.artists.firstOrNull()
        scope.launch(PerformanceDispatcher.diskIO) {
            val channelId = primary?.id ?: track.channelId.takeIf { it.isNotBlank() }
            if (channelId != null) sessionExclusions.blockChannel(channelId, primary?.name ?: track.artist)
        }
    }

    fun playNext(track: MusicTrack) {
        EnhancedMusicPlayerManager.playNext(track)
        EnhancedMusicPlayerManager.removeAutomixItem(track.videoId)
        Toast.makeText(context, context.getString(R.string.play_next_toast), Toast.LENGTH_SHORT).show()
    }

    fun addToQueue(track: MusicTrack) {
        EnhancedMusicPlayerManager.addToQueue(track)
        EnhancedMusicPlayerManager.removeAutomixItem(track.videoId)
        Toast.makeText(context, context.getString(R.string.added_to_queue_toast), Toast.LENGTH_SHORT).show()
    }

    /** Queues [tracks] right after the current song, in the order given, with one confirmation. */
    fun playNext(tracks: List<MusicTrack>) {
        if (tracks.isEmpty()) return
        tracks.asReversed().forEach { track ->
            EnhancedMusicPlayerManager.playNext(track)
            EnhancedMusicPlayerManager.removeAutomixItem(track.videoId)
        }
        Toast.makeText(context, context.getString(R.string.play_next_toast), Toast.LENGTH_SHORT).show()
    }

    fun addToQueue(tracks: List<MusicTrack>) {
        if (tracks.isEmpty()) return
        tracks.forEach { track ->
            EnhancedMusicPlayerManager.addToQueue(track)
            EnhancedMusicPlayerManager.removeAutomixItem(track.videoId)
        }
        Toast.makeText(context, context.getString(R.string.added_to_queue_toast), Toast.LENGTH_SHORT).show()
    }

}
