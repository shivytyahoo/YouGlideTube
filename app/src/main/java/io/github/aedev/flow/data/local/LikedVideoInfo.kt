package io.github.aedev.flow.data.local

import kotlinx.serialization.Serializable

@Serializable
data class LikedVideoInfo(
    val videoId: String,
    val title: String,
    val thumbnail: String,
    val channelName: String,
    val likedAt: Long = System.currentTimeMillis(),
    val isMusic: Boolean = false,
    // Nullable and zero by default: Gson backups written before these existed leave them unset.
    val channelId: String? = null,
    val durationSeconds: Int = 0,
) {
    /** This like, with the channel and length [stored] already knew where this one lacks them. */
    fun keepingDetailsOf(stored: LikedVideoInfo): LikedVideoInfo =
        copy(
            channelId = channelId?.takeIf(String::isNotBlank) ?: stored.channelId,
            durationSeconds = durationSeconds.takeIf { it > 0 } ?: stored.durationSeconds,
        )
}
