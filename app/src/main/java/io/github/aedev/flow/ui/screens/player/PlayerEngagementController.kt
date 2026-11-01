package io.github.aedev.flow.ui.screens.player

import io.github.aedev.flow.utils.ShivLog

import io.github.aedev.flow.data.engagement.VideoEngagementUseCase
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Subscription, notification and like state for the video the player screen is on: the writes the
 * user's taps make, and the one collector that mirrors the stored state back.
 *
 * Only one channel/video pair is observed at a time — asking again restarts the collector
 * (a reopen must re-read the set's current answer, not wait for it to change), and any
 * other pair cancels the collector rather than adding a second one.
 *
 * The learning signal each write carries comes from [richVideoFor], so a like recorded from a card
 * with nothing but a title still reaches the engine with the tags and description the screen holds.
 */
internal class PlayerEngagementController(
    private val engagement: VideoEngagementUseCase,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<VideoPlayerUiState>,
    private val richVideoFor: (String) -> Video?,
) {
    private var job: Job? = null
    private var channelId: String? = null
    private var videoId: String? = null

    fun toggleSubscription(
        channelId: String,
        channelName: String,
        channelThumbnail: String,
    ) {
        scope.launch {
            engagement.toggleSubscription(channelId, channelName, channelThumbnail) { isSubscribed ->
                state.value = state.value.copy(isSubscribed = isSubscribed)
            }
        }
    }

    fun setNotificationEnabled(
        channelId: String,
        enabled: Boolean,
    ) {
        scope.launch {
            engagement.setNotificationEnabled(channelId, enabled)
            state.value = state.value.copy(isNotificationsEnabled = enabled)
        }
    }

    fun like(
        videoId: String,
        title: String,
        thumbnail: String,
        channelName: String,
        channelId: String,
    ) {
        // Snapshot the pre-tap state NOW: the use case writes the session like state
        // before invoking the callback, and the observe() collector mirrors that write
        // back into likeState — so reading state.value inside the callback (or after
        // the awaited remove) can already see the NEW state and the delta math becomes
        // a no-op (+0 on like, -0 on unlike): the icon toggles, the count freezes.
        // The tap-time value is the true pre-action state.
        val wasLiked = state.value.likeState == "LIKED"
        val wasDisliked = state.value.likeState == "DISLIKED"
        scope.launch {
            val liked =
                Video(
                    id = videoId,
                    title = title,
                    channelName = channelName,
                    channelId = channelId,
                    thumbnailUrl = thumbnail,
                    duration = 0,
                    viewCount = 0,
                    uploadDate = "",
                )
            engagement.like(video = liked, signalVideo = richVideoFor(videoId) ?: liked) {
                val current = state.value
                val delta = current.likeCountDelta + if (wasLiked) 0 else 1
                val dislikeDelta = current.dislikeCountDelta - if (wasDisliked) 1 else 0
                ShivLog.i("videoLikeDelta id=${videoId.take(8)} action=like wasLiked=$wasLiked delta=$delta dislikeDelta=$dislikeDelta")
                state.value =
                    current.copy(
                        likeState = "LIKED",
                        likeCountDelta = delta,
                        dislikeCountDelta = dislikeDelta,
                    )
            }
        }
    }

    fun dislike(videoId: String) {
        val wasLiked = state.value.likeState == "LIKED"
        val wasDisliked = state.value.likeState == "DISLIKED"
        scope.launch {
            engagement.dislike(videoId, signalVideo = richVideoFor(videoId)) {
                val current = state.value
                val delta = current.likeCountDelta - if (wasLiked) 1 else 0
                val dislikeDelta = current.dislikeCountDelta + if (wasDisliked) 0 else 1
                ShivLog.i("videoLikeDelta id=${videoId.take(8)} action=dislike wasLiked=$wasLiked delta=$delta dislikeDelta=$dislikeDelta")
                state.value =
                    current.copy(
                        likeState = "DISLIKED",
                        likeCountDelta = delta,
                        dislikeCountDelta = dislikeDelta,
                    )
            }
        }
    }

    fun removeLike(videoId: String) {
        val wasLiked = state.value.likeState == "LIKED"
        val wasDisliked = state.value.likeState == "DISLIKED"
        scope.launch {
            engagement.removeLike(videoId)
            val current = state.value
            val delta = current.likeCountDelta - if (wasLiked) 1 else 0
            val dislikeDelta = current.dislikeCountDelta - if (wasDisliked) 1 else 0
            ShivLog.i("videoLikeDelta id=${videoId.take(8)} action=unlike wasLiked=$wasLiked delta=$delta dislikeDelta=$dislikeDelta")
            state.value =
                current.copy(
                    likeState = null,
                    likeCountDelta = delta,
                    dislikeCountDelta = dislikeDelta,
                )
        }
    }

    fun observe(
        channelId: String,
        videoId: String,
    ) {
        // The same-pair early return is gone: on a reopened video it left the display
        // holding whatever the load had reset it to, because the still-running collector
        // only speaks when the set changes — and the set does not change on a reopen.
        // Restarting re-reads the set's current answer immediately. (Device evidence,
        // 2026-10-23: the set and the toggle were both right while the button showed
        // Subscribe, and a trusting second tap unsubscribed for real.)
        ShivLog.i("subObserve id=${channelId.take(8)} video=${videoId.take(8)}",
        )
        job?.cancel()
        this.channelId = channelId
        this.videoId = videoId
        job =
            scope.launch {
                engagement.engagement(videoId = videoId, channelId = channelId).collect { engaged ->
                    ShivLog.i("subState id=${channelId.take(8)} value=${engaged.isSubscribed}",
                    )
                    state.update {
                        it.copy(
                            isSubscribed = engaged.isSubscribed,
                            isNotificationsEnabled = engaged.isNotificationEnabled,
                            likeState = engaged.likeState,
                        )
                    }
                }
            }
    }
}
