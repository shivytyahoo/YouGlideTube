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
)
