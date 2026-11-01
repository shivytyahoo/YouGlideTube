package io.github.aedev.flow.data.engagement

import android.util.Log
import io.github.aedev.flow.data.account.AccountSubscriptions
import io.github.aedev.flow.data.local.LikedVideoInfo
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.engagement.InteractionType
import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.innertube.YouTube
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

private const val TAG = "VideoEngagement"

/** Everything a surface shows about the viewer's relationship with one video and its channel. */
data class VideoEngagement(
    val isSubscribed: Boolean = false,
    val isNotificationEnabled: Boolean = false,
    val likeState: String? = null,
)

/**
 * Subscribe, notification and like/dislike for one video, with the learning signals each action
 * owes the recommendation engine.
 *
 * The player, Shorts and the quick-actions sheet all performed the same writes followed by the
 * same [VideoEngagementSignals] calls; this is that sequence, once. It holds no state of its own —
 * every read is a cold flow and every write is a suspend call — so it is unscoped and the caller's
 * own scope owns any collection.
 *
 * Mutators take an `onApplied` callback rather than returning: the local write must be reflected in
 * the UI immediately, and the signals that follow it include a network channel-tag fetch, so a
 * caller that updated its state on return would leave the button stale for the length of that
 * fetch.
 */
class VideoEngagementUseCase
    @Inject
    constructor(
        private val sessionLikes: SessionLikeState,
        private val signals: VideoEngagementSignals,
        private val accountSubscriptions: AccountSubscriptions,
    ) {
        fun subscriptionState(channelId: String): Flow<Boolean> =
            if (YouTube.isSignedIn()) {
                // Signed in, the account is the truth, and it lives in ONE place: the guide's
                // complete membership list. This used to be a header guess plus a feed sample
                // plus a process cache that froze failed lookups — and the surfaces disagreed.
                accountSubscriptions.isSubscribed(channelId)
            } else {
                // Signed out there is no subscription state: subscriptions are account-only.
                flowOf(false)
            }

        fun notificationState(channelId: String): Flow<Boolean> = flowOf(false)

        fun likeState(videoId: String): Flow<String?> = sessionLikes.likeState(videoId)

        /**
         * The three concerns as one flow, so a screen showing all of them holds one collector per
         * concern for as long as it is on the same video rather than re-reading them per action.
         */
        fun engagement(
            videoId: String,
            channelId: String,
        ): Flow<VideoEngagement> =
            combine(
                subscriptionState(channelId),
                notificationState(channelId),
                likeState(videoId),
            ) { isSubscribed, isNotificationEnabled, likeState ->
                VideoEngagement(
                    isSubscribed = isSubscribed,
                    isNotificationEnabled = isNotificationEnabled,
                    likeState = likeState,
                )
            }

        /** Flips the stored subscription, reporting the state it landed on. */
        suspend fun toggleSubscription(
            channelId: String,
            channelName: String,
            channelThumbnail: String,
            onApplied: (Boolean) -> Unit = {},
        ) {
            if (!YouTube.isSignedIn()) return
            accountSubscriptions.awaitLoaded()
            val isSubscribed = channelId in accountSubscriptions.ids.value
            applySubscription(
                channelId = channelId,
                channelName = channelName,
                channelThumbnail = channelThumbnail,
                subscribed = !isSubscribed,
                onApplied = onApplied,
            )
        }

        /**
         * Writes [subscribed] for a caller that already knows which way the toggle is going — the
         * quick-actions sheet decides from its own cache and resolves the channel avatar first.
         */
        suspend fun applySubscription(
            channelId: String,
            channelName: String,
            channelThumbnail: String,
            subscribed: Boolean,
            onApplied: (Boolean) -> Unit = {},
        ) {
            if (!YouTube.isSignedIn()) return
            // The real account action, through the one shared list.
            accountSubscriptions.setSubscribed(
                Channel(
                    id = channelId,
                    name = channelName,
                    thumbnailUrl = channelThumbnail,
                    subscriberCount = 0L,
                ),
                subscribed,
            )
            onApplied(subscribed)
            runCatching {
                signals.channelSubscriptionChanged(channelId, channelName, subscribed)
            }.onFailure { Log.w(TAG, "Failed to record subscription signal", it) }
            if (subscribed) {
                runCatching { signals.channelTagsLearned(channelId) }
            }
        }

        suspend fun setNotificationEnabled(
            channelId: String,
            enabled: Boolean,
        ) {
            // Notification preference was a local-store field; subscriptions are account-only now
            // and the account API surface here carries no per-channel notification level.
        }

        /**
         * Likes [video]. [signalVideo] is the item the engine learns from — the richest version of
         * the video the caller holds — and a null one means the action carries no learning signal.
         */
        suspend fun like(
            video: Video,
            signalVideo: Video? = null,
            onApplied: () -> Unit = {},
        ) {
            sessionLikes.like(
                LikedVideoInfo(
                    videoId = video.id,
                    title = video.title,
                    thumbnail = video.thumbnailUrl,
                    channelName = video.channelName,
                    isMusic = video.isMusic,
                    channelId = video.channelId.takeIf(String::isNotBlank),
                    durationSeconds = video.duration,
                ),
            )
            onApplied()
            if (signalVideo == null) return
            try {
                signals.videoInteraction(signalVideo, InteractionType.LIKED)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record like signal", e)
            }
        }

        suspend fun dislike(
            videoId: String,
            signalVideo: Video? = null,
            onApplied: () -> Unit = {},
        ) {
            sessionLikes.dislike(videoId)
            onApplied()
            if (signalVideo == null) return
            try {
                signals.videoInteraction(signalVideo, InteractionType.DISLIKED)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record dislike", e)
            }
        }

        suspend fun removeLike(videoId: String) {
            sessionLikes.removeLikeState(videoId)
        }
    }
