package io.github.aedev.flow.data.engagement

import io.github.aedev.flow.data.model.Video

/**
 * What the viewer asked not to see, snapshotted for the surfaces that filter a feed:
 * Subscriptions, the subscription Shorts queue, search, the player's related list and
 * autoplay, and the Home feed restored from cache.
 *
 * Two strengths, because not every surface should hide the same things: [hides] is what the
 * viewer said outright, a video marked not interested or a blocked channel, and applies
 * everywhere, Subscriptions included. [hidesFromRecommendations] adds the suppressed
 * channels a not-interested mark implies, and applies only where the app chose the video
 * rather than the viewer.
 */
class FeedExclusions(
    val suppressedVideoIds: Set<String> = emptySet(),
    val blockedChannelIds: Set<String> = emptySet(),
    val suppressedChannelIds: Set<String> = emptySet(),
    private val blockedText: (title: String, channelName: String) -> Boolean = { _, _ -> false },
) {
    val isEmpty: Boolean
        get() = suppressedVideoIds.isEmpty() && blockedChannelIds.isEmpty() && suppressedChannelIds.isEmpty()

    fun hides(video: Video): Boolean = video.id in suppressedVideoIds || hidesChannel(video.channelId)

    fun hidesChannel(channelId: String): Boolean = channelId.isNotBlank() && channelId in blockedChannelIds

    fun hidesFromRecommendations(video: Video): Boolean =
        hides(video) ||
            (video.channelId.isNotBlank() && video.channelId in suppressedChannelIds) ||
            blockedText(video.title, video.channelName)

    /** The same exclusions, also hiding every video from [channelIds]. */
    fun hidingChannels(channelIds: Set<String>): FeedExclusions =
        if (channelIds.isEmpty()) {
            this
        } else {
            FeedExclusions(suppressedVideoIds, blockedChannelIds + channelIds, suppressedChannelIds, blockedText)
        }

    companion object {
        val NONE = FeedExclusions()
    }
}
