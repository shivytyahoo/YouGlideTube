package io.github.shivytyahoo.glidetube.ui.screens.subscriptions

import android.util.Log
import io.github.shivytyahoo.glidetube.data.model.Video
import io.github.shivytyahoo.glidetube.innertube.YouTube
import io.github.shivytyahoo.glidetube.utils.PerformanceDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

private const val TAG = "SubsChannelLengths"

/**
 * Lengths for a channel's videos, in bulk from its Videos tab: one request per channel instead
 * of one player request per length-less video. The tab is the cheaper source — it lists the
 * lengths outright — so the player endpoint stays the fallback for whatever the tab does not
 * answer. Null when the tab cannot be read at all.
 */
internal suspend fun fetchChannelVideoLengths(
    channelId: String,
    channelName: String,
    wantedVideoIds: Set<String>,
): Map<String, Int> {
    if (channelId.isBlank() || wantedVideoIds.isEmpty()) return emptyMap()
    return runCatching {
        val result = YouTube.channelVideos(channelId, channelName, "").getOrNull() ?: return emptyMap()
        result.videos
            .filter { it.id in wantedVideoIds && it.duration > 0 && !it.isLive && !it.isUpcoming }
            .associate { it.id to it.duration }
    }.onFailure { error ->
        Log.w(TAG, "Channel Videos tab lengths failed for $channelId: ${error.message}")
    }.getOrDefault(emptyMap())
}

/** Fills in durations from a bulk channel fetch, keeping every duration the feed already has. */
internal fun List<Video>.withChannelLengths(lengthsById: Map<String, Int>): List<Video> =
    map { video ->
        val length = lengthsById[video.id]
        if (video.duration > 0 || length == null || length <= 0) video else video.copy(duration = length)
    }

/**
 * Bulk lengths per channel, once per run: the Videos tab answers every on-screen video of a
 * channel in one request, so a channel is never fetched twice for the same feed. A failed fetch
 * is not retried until the retry window has passed.
 */
internal class SubscriptionChannelLengthsStore {
    private val cache = mutableMapOf<String, Map<String, Int>>()
    private val attemptedAt = mutableMapOf<String, Long>()

    suspend fun fetch(
        candidates: List<Video>,
        nowMillis: Long,
        retryAfterMillis: Long,
    ): Map<String, Int> {
        val byChannel = candidates.filter { it.channelId.isNotBlank() }.groupBy { it.channelId }
        if (byChannel.isEmpty()) return emptyMap()
        val fresh =
            byChannel.keys.filter { channelId ->
                cache[channelId] == null &&
                    (attemptedAt[channelId]?.let { nowMillis - it >= retryAfterMillis } ?: true)
            }
        fresh.chunked(CHANNEL_LENGTHS_CONCURRENCY).forEach { batch ->
            supervisorScope {
                batch
                    .map { channelId ->
                        async(PerformanceDispatcher.networkIO) {
                            val channelVideos = byChannel[channelId].orEmpty()
                            val lengths =
                                fetchChannelVideoLengths(
                                    channelId,
                                    channelVideos.firstOrNull()?.channelName.orEmpty(),
                                    channelVideos.map { it.id }.toSet(),
                                )
                            attemptedAt[channelId] = System.currentTimeMillis()
                            if (lengths.isNotEmpty()) cache[channelId] = lengths
                        }
                    }.awaitAll()
            }
        }
        return byChannel.keys.mapNotNull { cache[it] }.fold(mutableMapOf()) { acc, lengths ->
            acc.apply { putAll(lengths) }
        }
    }

    companion object {
        private const val CHANNEL_LENGTHS_CONCURRENCY = 3
    }
}
