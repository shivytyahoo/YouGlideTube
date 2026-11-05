package io.github.aedev.flow.notification

import android.content.Context
import android.util.Log
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val TAG = "SubscriptionCheck"

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
        val fresh =
            currentVideos
                .filter { it.id !in seen }
                .map {
                    NotificationHelper.NewVideoEntry(
                        channelName = it.channelName,
                        videoTitle = it.title,
                        videoId = it.id,
                        thumbnailUrl = it.thumbnailUrl.takeIf { url -> url.isNotBlank() },
                    )
                }
        if (fresh.isNotEmpty()) {
            val now = System.currentTimeMillis()
            preferences.addInboxNotifications(
                fresh.map {
                    InboxNotification(
                        videoId = it.videoId,
                        channelName = it.channelName,
                        videoTitle = it.videoTitle,
                        thumbnailUrl = it.thumbnailUrl,
                        receivedAt = now,
                    )
                },
            )
        }
        preferences.setSubscriptionCheckState(currentIds, seeded = true)
        fresh
    }
