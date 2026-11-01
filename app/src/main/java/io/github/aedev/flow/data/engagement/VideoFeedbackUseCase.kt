package io.github.aedev.flow.data.engagement

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.account.AccountPlaylistIds
import io.github.aedev.flow.data.repository.YouTubePlaylistRepository
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.data.local.SessionHistory
import io.github.aedev.flow.data.local.WatchLaterCleanup
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import javax.inject.Inject

/** A channel a block applied to, named as the viewer knows it. */
data class BlockedChannel(
    val channelId: String,
    val channelName: String,
)

/**
 * What a video's menu tells the library and the session exclusions: Watch later, not interested,
 * more like this, watched, and hiding a channel. Each call does the writes and the learning signal
 * that belong together, and says nothing to the viewer; the caller decides how to confirm it.
 */
class VideoFeedbackUseCase
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repository: YouTubeRepository,
        private val youTubePlaylistRepository: YouTubePlaylistRepository,
        private val watchLaterCleanup: WatchLaterCleanup,
        private val viewHistory: SessionHistory,
        private val sessionExclusions: SessionExclusions,
    ) {
        /** Adds [video] to the account's Watch Later, or takes it out; returns whether it is saved afterwards. */
        suspend fun toggleWatchLater(video: Video): Boolean {
            if (!YouTube.isSignedIn()) return false
            val inWatchLater =
                youTubePlaylistRepository.complete(AccountPlaylistIds.WATCH_LATER)?.videos?.any { it.id == video.id } == true
            if (inWatchLater) {
                youTubePlaylistRepository.removeVideo(AccountPlaylistIds.WATCH_LATER, video.id)
                return false
            }
            youTubePlaylistRepository.addVideo(AccountPlaylistIds.WATCH_LATER, video.id)
            return true
        }

        suspend fun setWatchLater(
            video: Video,
            saved: Boolean,
        ) {
            if (!YouTube.isSignedIn()) return
            if (saved) {
                youTubePlaylistRepository.addVideo(AccountPlaylistIds.WATCH_LATER, video.id)
            } else {
                youTubePlaylistRepository.removeVideo(AccountPlaylistIds.WATCH_LATER, video.id)
            }
        }

        /**
         * Hides [video]'s channel from every feed. A live or premiere entry can carry a handle instead
         * of a channel id, so that case asks the watch page for the real id first.
         */
        suspend fun blockChannel(video: Video): BlockedChannel {
            val metadata = if (video.channelId.startsWith("UC")) null else repository.getLiveWatchMetadata(video.id)
            val channelId = metadata?.channelId.orEmpty().ifBlank { video.channelId }
            check(channelId.isNotBlank()) { "No channel id for ${video.id}" }
            val channelName = metadata?.channelName.orEmpty().ifBlank { video.channelName }
            sessionExclusions.blockChannel(channelId, channelName)
            FeedInvalidationBus.emit(FeedInvalidationBus.Event.ChannelBlocked(channelId, video.id))
            return BlockedChannel(channelId, channelName)
        }

        suspend fun unblockChannel(channelId: String) {
            sessionExclusions.unblockChannel(channelId)
        }

        suspend fun markNotInterested(video: Video) {
            sessionExclusions.markNotInterested(video)
            FeedInvalidationBus.emit(FeedInvalidationBus.Event.NotInterested(video.id, video.channelId))
        }

        suspend fun markInterested(video: Video) {
            sessionExclusions.undoNotInterested(video)
        }

        /** Records [video] as watched to the end, so every card shows it full. */
        suspend fun markWatched(video: Video) {
            val durationMs = if (video.duration > 0) video.duration * 1000L else 1000L
            viewHistory.savePlaybackPosition(
                videoId = video.id,
                position = durationMs,
                duration = durationMs,
                title = video.title,
                thumbnailUrl = video.thumbnailUrl.ifEmpty { ThumbnailUrlResolver.buildHighQualityYoutubeThumbnail(video.id) },
                channelName = video.channelName,
                channelId = video.channelId,
                isMusic = false,
                isShort = video.isShort,
            )
            FeedInvalidationBus.emit(FeedInvalidationBus.Event.MarkedWatched(video.id))
            watchLaterCleanup.onFinished(video.id)
        }

        /**
         * Clears the progress that makes [video] read as watched. The history row stays, and the
         * recommendation signal from the earlier watched interaction is left as it was.
         */
        suspend fun markUnwatched(video: Video) {
            viewHistory.clearProgress(video.id)
        }
    }
