package io.github.aedev.flow.notification

import android.content.Context
import android.util.Log
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val TAG = "SubscriptionCheck"

/** Seen video ids kept across checks, so a video that drops off the feed page and comes back later is never "fresh" again. The feed only ever surfaces recent uploads, so 2000 ids is weeks of history. */
private const val MAX_SEEN_VIDEO_IDS = 2000

/** Baseline for the first check after the recency rule lands: only uploads from the last 48 hours count as new. Later checks use the actual last-check time. */
private const val RECENT_UPLOAD_WINDOW_MS = 48L * 3_600_000L

/**
 * Parses YouTube's relative publish text ("2 hours ago", "3 days ago", "Streamed 1 week ago")
 * into an approximate epoch; anything unrecognised falls back to now.
 */
internal fun parseRelativePublishedTime(
    text: String,
    nowMs: Long,
): Long {
    val trimmed = text.trim().removePrefix("Streamed ").trim()
    if (trimmed.isEmpty() || trimmed.equals("just now", ignoreCase = true)) return nowMs
    val match =
        Regex("""(\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago""", RegexOption.IGNORE_CASE)
            .find(trimmed) ?: return nowMs
    val amount = match.groupValues[1].toLongOrNull() ?: return nowMs
    val unitMs =
        when (match.groupValues[2].lowercase()) {
            "second" -> 1_000L
            "minute" -> 60_000L
            "hour" -> 3_600_000L
            "day" -> 86_400_000L
            "week" -> 7 * 86_400_000L
            "month" -> 30 * 86_400_000L
            "year" -> 365 * 86_400_000L
            else -> return nowMs
        }
    return (nowMs - amount * unitMs).coerceAtLeast(0L)
}

/**
 * The one subscriptions check, shared by the background worker and the manual
 * refresh on the Notifications section: it runs the subscriptions feed,
 * appends videos never seen before to the inbox, and saves the seen set.
 * Posting the system notifications stays with the caller — a manual refresh
 * must not buzz the phone — and the first non-empty page only seeds the seen
 * set, so neither caller can spam the whole backlog.
 */
suspend fun collectFreshSubscriptionVideos(context: Context): List<NotificationHelper.NewVideoEntry> =
    withContext(Dispatchers.IO) {
        val preferences = PlayerPreferences(context)
        if (!YouTube.isSignedIn()) {
            Log.d(TAG, "Signed out, skipping check")
            return@withContext emptyList()
        }
        val page = YouTube.serverSubscriptions().getOrNull()
        if (page == null) {
            Log.d(TAG, "Subscriptions feed failed, next cycle retries")
            return@withContext emptyList()
        }
        val notifyShorts = preferences.notifShortsEnabled.first()
        val currentVideos = page.videos + if (notifyShorts) page.shorts else emptyList()
        val currentIds = currentVideos.map { it.id }.toSet()
        if (currentIds.isEmpty()) {
            Log.d(TAG, "Subscriptions feed empty, leaving state untouched")
            return@withContext emptyList()
        }
        val seen = preferences.subscriptionSeenVideoIds.first()
        val seeded = preferences.subscriptionCheckSeeded.first()
        if (!seeded) {
            preferences.setSubscriptionCheckState(currentIds, seeded = true)
            Log.d(TAG, "Seeded seen set with ${currentIds.size} videos, not notifying")
            return@withContext emptyList()
        }
        // The seen set only grows: replacing it with the current page (as before) let a video
        // that dropped off the feed and came back look fresh again and re-notify.
        val now = System.currentTimeMillis()
        // Only videos published since the last successful check count as new: the feed also
        // surfaces older uploads, and those are not notifications. An unparseable publish
        // time falls back to now in the parser, so it stays fresh — only a provably old
        // video is filtered out.
        val lastCheck =
            preferences.lastSubscriptionCheckMs.first().takeIf { it > 0 } ?: (now - RECENT_UPLOAD_WINDOW_MS)
        val freshVideos =
            currentVideos
                .filter { it.id !in seen }
                .map { video -> video to parseRelativePublishedTime(video.uploadDate, now) }
                .filter { (_, publishedAt) -> publishedAt >= lastCheck }
        if (freshVideos.isNotEmpty()) {
            preferences.addInboxNotifications(
                freshVideos.map { (video, publishedAt) ->
                    InboxNotification(
                        videoId = video.id,
                        channelName = video.channelName,
                        videoTitle = video.title,
                        thumbnailUrl = video.thumbnailUrl.takeIf { url -> url.isNotBlank() },
                        receivedAt = now,
                        publishedAt = publishedAt,
                    )
                },
            )
        }
        val fresh =
            freshVideos
                .map { (video, _) ->
                    NotificationHelper.NewVideoEntry(
                        channelName = video.channelName,
                        videoTitle = video.title,
                        videoId = video.id,
                        thumbnailUrl = video.thumbnailUrl.takeIf { url -> url.isNotBlank() },
                    )
                }
        preferences.setSubscriptionCheckState((currentIds + seen).take(MAX_SEEN_VIDEO_IDS).toSet(), seeded = true)
        fresh
    }
