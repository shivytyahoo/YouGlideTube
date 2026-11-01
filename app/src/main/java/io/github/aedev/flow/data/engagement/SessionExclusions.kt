package io.github.aedev.flow.data.engagement

import io.github.aedev.flow.data.model.Video
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Not interested" marks and blocked channels for this session. They used to live inside the
 * recommendation engine's persisted brain; that store is gone, so the marks behave exactly as
 * before while the app runs and reset on a fresh launch. [snapshot] is the single read every
 * feed filter goes through.
 */
@Singleton
class SessionExclusions
    @Inject
    constructor() {
        private val suppressedVideoIds = MutableStateFlow<Set<String>>(emptySet())
        private val suppressedChannelIds = MutableStateFlow<Set<String>>(emptySet())
        private val blockedChannels = MutableStateFlow<Map<String, String>>(emptyMap())

        /** Blocked channel id to the name the viewer knows it by, for undo affordances. */
        val blockedNames: StateFlow<Map<String, String>> = blockedChannels

        /** Every channel hidden this session, blocked or suppressed, as one set of ids. */
        val hiddenChannelIds: Flow<Set<String>> =
            combine(blockedChannels, suppressedChannelIds) { blocked, suppressed -> blocked.keys + suppressed }

        fun snapshot(): FeedExclusions =
            FeedExclusions(
                suppressedVideoIds = suppressedVideoIds.value,
                blockedChannelIds = blockedChannels.value.keys,
                suppressedChannelIds = suppressedChannelIds.value,
            )

        /** A not-interested mark: the video goes, and its channel is suppressed in recommendations. */
        fun markNotInterested(video: Video) {
            suppressedVideoIds.value = suppressedVideoIds.value + video.id
            if (video.channelId.isNotBlank()) {
                suppressedChannelIds.value = suppressedChannelIds.value + video.channelId
            }
        }

        fun undoNotInterested(video: Video) {
            suppressedVideoIds.value = suppressedVideoIds.value - video.id
            if (video.channelId.isNotBlank()) {
                suppressedChannelIds.value = suppressedChannelIds.value - video.channelId
            }
        }

        fun blockChannel(
            channelId: String,
            channelName: String,
        ) {
            if (channelId.isBlank()) return
            blockedChannels.value = blockedChannels.value + (channelId to channelName)
        }

        fun unblockChannel(channelId: String) {
            blockedChannels.value = blockedChannels.value - channelId
        }

        fun isChannelBlocked(channelId: String): Boolean = channelId in blockedChannels.value

        /** Suppresses a channel in recommendations without a full block (a disliked artist). */
        fun suppressChannel(channelId: String) {
            if (channelId.isNotBlank()) {
                suppressedChannelIds.value = suppressedChannelIds.value + channelId
            }
        }
    }

/** Hilt entry point for the session exclusions store, for non-injected collaborators. */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface SessionExclusionsEntryPoint {
    fun sessionExclusions(): SessionExclusions
}
