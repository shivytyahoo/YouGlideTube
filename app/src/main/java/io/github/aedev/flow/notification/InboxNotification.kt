package io.github.aedev.flow.notification

import kotlinx.serialization.Serializable

/**
 * One arrived subscription notification, kept for the in-app Notifications
 * section. System notifications vanish once dismissed, so this is the only
 * record of what arrived — the worker appends here at the same time it posts
 * the system notification, and a manual refresh on the section appends too.
 */
@Serializable
data class InboxNotification(
    val videoId: String,
    val channelName: String,
    val videoTitle: String,
    val thumbnailUrl: String? = null,
    val receivedAt: Long,
    val read: Boolean = false,
    /** Approximate publish time, parsed from the feed's relative "3 days ago"; 0 when unknown. */
    val publishedAt: Long = 0L,
) {
    /** Newest-first ordering key: the publish time when known, else the arrival time. */
    val effectivePublishedAt: Long get() = if (publishedAt > 0) publishedAt else receivedAt
}
