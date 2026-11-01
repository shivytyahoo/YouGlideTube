package io.github.aedev.flow.data.shorts.queue

import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.ShortVideo
import io.github.aedev.flow.data.model.toShortVideo
import io.github.aedev.flow.data.engagement.FeedExclusions
import io.github.aedev.flow.data.shorts.ShortsFeedRepository
import io.github.aedev.flow.data.shorts.spreadChannels
import io.github.aedev.flow.data.subscriptions.SubscriptionWatchedVideos
import io.github.aedev.flow.innertube.YouTube
import kotlinx.coroutines.flow.first

/**
 * The algorithmic reel feed, optionally opened on one short.
 *
 * The pager behind [ShortsFeedRepository] never reports an end — its explore chain has none — so
 * a page can come back empty (every candidate filtered) without the queue giving up on it.
 */
class AlgorithmicFeedLoader(
    private val repository: ShortsFeedRepository,
    private val seedVideoId: String? = null,
) : ShortsQueueLoader {
    override suspend fun initial(): ShortsQueuePage = page(repository.openFeed(seedVideoId))

    override suspend fun more(cursor: String?): ShortsQueuePage = page(repository.nextPage())

    private fun page(items: List<ShortVideo>): ShortsQueuePage =
        ShortsQueuePage(
            items = items,
            cursor = MORE.takeUnless { repository.isExhausted },
            exhausted = repository.isExhausted,
        )

    private companion object {
        const val MORE = "more"
    }
}

/**
 * [exclusions] keeps reels marked not interested and blocked channels out, as the Subscriptions feed
 * does. The tapped reel is exempt from every filter: the user just chose it.
 */
class SubscriptionShortsLoader(
    private val playerPreferences: PlayerPreferences,
    private val watchedVideos: SubscriptionWatchedVideos,
    private val anchorVideoId: String?,
    private val exclusions: suspend () -> FeedExclusions = { FeedExclusions.NONE },
) : ShortsQueueLoader {
    override suspend fun initial(): ShortsQueuePage {
        val excludedChannelIds = playerPreferences.subscriptionShortsExcludedChannels.first()
        val watchedIds = watchedVideos.shortIds.first()
        val hidden = exclusions()
        val page = if (YouTube.cookie == null) null else YouTube.serverSubscriptions().getOrNull()
        val items =
            page
                ?.shorts
                .orEmpty()
                .asSequence()
                .filter { it.id.isNotBlank() }
                .filter {
                    it.id == anchorVideoId ||
                        (it.channelId !in excludedChannelIds && !hidden.hides(it) && it.id !in watchedIds)
                }.sortedByDescending { it.timestamp }
                .map { it.toShortVideo() }
                .toList()
        return ShortsQueuePage(spreadChannels(items, ShortVideo::channelId), cursor = null, exhausted = true)
    }

    override suspend fun more(cursor: String?): ShortsQueuePage = exhaustedPage()
}

/**
 * A list a shelf already had in memory, handed over via [ShortsQueueHandoff].
 *
 * Finite by nature — the shelf holds what it holds. Continuing past it is the controller's job.
 */
class SnapshotLoader(
    private val items: List<ShortVideo>,
) : ShortsQueueLoader {
    override suspend fun initial(): ShortsQueuePage = ShortsQueuePage(items, cursor = null, exhausted = true)

    override suspend fun more(cursor: String?): ShortsQueuePage = exhaustedPage()
}

/**
 * A loader the user can switch off — today, the algorithmic feed that trails a finished queue.
 *
 * [enabled] is read when the hand-over would actually happen rather than when the queue is built, so
 * flipping the setting mid-watch decides what the *next* swipe past the end does. Switched off it
 * reports itself exhausted, which is the queue's existing way of saying "this is the end".
 */
class GatedShortsLoader(
    private val enabled: suspend () -> Boolean,
    private val delegate: ShortsQueueLoader,
) : ShortsQueueLoader {
    override suspend fun initial(): ShortsQueuePage = if (enabled()) delegate.initial() else exhaustedPage()

    override suspend fun more(cursor: String?): ShortsQueuePage = if (enabled()) delegate.more(cursor) else exhaustedPage()
}

private fun exhaustedPage() = ShortsQueuePage(emptyList(), cursor = null, exhausted = true)
