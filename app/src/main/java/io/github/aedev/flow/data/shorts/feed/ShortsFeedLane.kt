package io.github.aedev.flow.data.shorts.feed

import io.github.aedev.flow.data.model.ShortVideo

/** Where a reel in the algorithmic feed came from. The order is the blend's quota order. */
enum class ShortsFeedLane {
    /** YouTube's reel graph around a reel the user chose: a seeded `reel_watch_sequence` chain. */
    RELATED,

    /** YouTube's anonymous reel feed: the seedless chain, which never ends. */
    EXPLORE,

    /** Shorts search results for the queries the engine generates from learnt topics. */
    DISCOVERY,

    /** Subscribed channels: the RSS feed's reels first, then their Shorts tabs. */
    SUBSCRIPTIONS,
}

data class ShortsLaneItem(
    val short: ShortVideo,
    val lane: ShortsFeedLane,
    val seedId: String? = null,
)

/** Who the feed is for, which decides how the lanes share a page. */
data class ShortsFeedProfile(
    val subscribedChannelIds: Set<String>,
    val isColdStart: Boolean,
) {
    val hasSubscriptions: Boolean
        get() = subscribedChannelIds.isNotEmpty()
}

/**
 * Everything a reel must not be to reach the page. Read once per page. The id sets cover every
 * lane; [isBlockedText] can only judge reels that carry a title, so id-only reels from the
 * sequence chains are judged again when their `/player` response names them.
 */
data class ShortsFeedFilters(
    val watchedIds: Set<String> = emptySet(),
    val seenIds: Set<String> = emptySet(),
    val suppressedIds: Set<String> = emptySet(),
    val excludedChannelIds: Set<String> = emptySet(),
    val isBlockedText: (title: String, channelName: String) -> Boolean = { _, _ -> false },
)

/** Where a reel seed comes from. */
enum class ShortsSeedSource {
    LIKED,
    WANT_MORE,
    SAVED,
    WATCHED,

    /** A reel the user dwelt on this session — usable, weakest. */
    FEED,
}

/** A reel the user engaged with, offered as the seed of a related reel chain. */
data class ShortsSeedInput(
    val id: String,
    val channelId: String,
    val source: ShortsSeedSource,
    val timestamp: Long,
    /** Watched share for [ShortsSeedSource.WATCHED]; ignored elsewhere. */
    val percentWatched: Double = 100.0,
)
