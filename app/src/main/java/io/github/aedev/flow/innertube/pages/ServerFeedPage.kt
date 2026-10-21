package io.github.aedev.flow.innertube.pages

import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.pages.renderer.FeedItem
import io.github.aedev.flow.innertube.pages.renderer.FeedItemOwner
import io.github.aedev.flow.innertube.pages.renderer.distinctKey
import io.github.aedev.flow.innertube.pages.renderer.toFeedItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A topic chip exactly as the server feed offers it: a label and the browse params that load it. */
data class ServerChip(
    val label: String,
    val params: String?,
)

/**
 * One page of a server-driven feed — home, subscriptions or history. Items keep the
 * server's own order; nothing here is ranked, mixed or topped up locally.
 */
data class ServerFeedPage(
    val videos: List<Video> = emptyList(),
    val shorts: List<Video> = emptyList(),
    /** Channels the surface carries (the subscriptions page's channel rail). */
    val channels: List<io.github.aedev.flow.data.model.Channel> = emptyList(),
    val chips: List<ServerChip> = emptyList(),
    val continuation: String? = null,
)

/**
 * Reads a browse response (or one of its continuation payloads) into a [ServerFeedPage].
 *
 * The item containers differ per surface — richGridRenderer on home, section lists on
 * subscriptions and history, shelves for shorts — so instead of modelling each one, the
 * whole tree is walked and every array is offered to the shared feed-item parsers. The
 * page continuation is the last token found: YouTube hangs it below the items it pages.
 */
fun JsonElement.toServerFeedPage(): ServerFeedPage {
    val items = mutableListOf<FeedItem>()
    val seen = HashSet<String>()
    var continuation: String? = null
    var chips: List<ServerChip> = emptyList()

    fun walk(node: JsonElement?) {
        when (node) {
            is JsonArray -> {
                for (element in node) {
                    val item = element.toFeedItem(FeedItemOwner())
                    if (item != null && seen.add(item.distinctKey())) {
                        items += item
                    }
                }
                node.forEach(::walk)
            }

            is JsonObject -> {
                node["continuationItemRenderer"]
                    .objectOrNull()
                    ?.get("continuationEndpoint")
                    .objectOrNull()
                    ?.get("continuationCommand")
                    .objectOrNull()
                    ?.get("token")
                    .stringOrNull()
                    ?.let { continuation = it }
                node["nextContinuationData"]
                    .objectOrNull()
                    ?.get("continuation")
                    .stringOrNull()
                    ?.let { continuation = it }
                if (chips.isEmpty()) {
                    val chipBar =
                        node["feedFilterChipBarRenderer"].objectOrNull()
                            ?: node["chipCloudRenderer"].objectOrNull()
                    chipBar
                        ?.get("chips")
                        .arrayOrNull()
                        ?.mapNotNull { it.toServerChip() }
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { chips = it }
                }
                node.values.forEach(::walk)
            }

            else -> Unit
        }
    }
    walk(this)

    return ServerFeedPage(
        videos = items.filterIsInstance<FeedItem.VideoItem>().map { it.video },
        shorts =
            items
                .filterIsInstance<FeedItem.ShortItem>()
                .map { it.video.copy(isShort = true) },
        channels = items.filterIsInstance<FeedItem.RelatedChannelItem>().map { it.channel },
        chips = chips,
        continuation = continuation,
    )
}

private fun JsonElement.toServerChip(): ServerChip? {
    val chip = objectOrNull()?.get("chipCloudChipRenderer").objectOrNull() ?: return null
    val label = chip["text"].youtubeText() ?: return null
    val params =
        chip["navigationEndpoint"]
            .objectOrNull()
            ?.get("browseEndpoint")
            .objectOrNull()
            ?.get("params")
            .stringOrNull()
    return ServerChip(label = label, params = params)
}
