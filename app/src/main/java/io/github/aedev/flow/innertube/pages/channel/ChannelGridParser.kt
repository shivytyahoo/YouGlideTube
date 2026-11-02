package io.github.aedev.flow.innertube.pages.channel

import io.github.aedev.flow.innertube.pages.arrayOrNull
import io.github.aedev.flow.innertube.pages.objectOrNull
import io.github.aedev.flow.innertube.pages.renderer.FeedItem
import io.github.aedev.flow.innertube.pages.renderer.FeedItemOwner
import io.github.aedev.flow.innertube.pages.renderer.distinctKey
import io.github.aedev.flow.innertube.pages.renderer.findRenderers
import io.github.aedev.flow.innertube.pages.renderer.harvestFeedPage
import io.github.aedev.flow.innertube.pages.renderer.largestImageUrl
import io.github.aedev.flow.innertube.pages.renderer.toFeedItem
import io.github.aedev.flow.innertube.pages.renderer.toFeedShelves
import io.github.aedev.flow.innertube.pages.stringOrNull
import io.github.aedev.flow.innertube.pages.youtubeText
import kotlinx.serialization.json.JsonElement

/**
 * A grid tab, in any of the four shapes it arrives in — initial browse, sort switch, page append and
 * the older `richGridContinuation` — because [gridItemLists] already enumerates all four.
 *
 * Items come only from the grid's own lists, never from a walk of the whole response: the header
 * carries carousels of its own, and a document-order search picks those up as channel content.
 */
internal fun JsonElement.toChannelTabContent(
    kind: ChannelTabKind,
    fallbackOwner: FeedItemOwner = FeedItemOwner(),
): ChannelTabContent {
    val owner = resolveOwner(fallbackOwner)
    val sections = if (kind == ChannelTabKind.Home) selectedTabContent().toFeedShelves(owner) else emptyList()
    val strictItems =
        if (sections.isNotEmpty()) {
            emptyList()
        } else {
            gridItemLists()
                .flatMap { list -> list.mapNotNull { it.toFeedItem(owner) } }
                .distinctBy { it.distinctKey() }
        }
    // TVHTML5 channel tabs carry their rows as tiles, which the strict grid map
    // misses — the tab then parses empty. Only then, harvest the same tree the
    // feed parser already trusts. Posts are not videos, so they stay out of
    // this fallback and keep their own shape to be handled.
    val harvested = if (strictItems.isEmpty() && sections.isEmpty()) harvestFeedPage() else null
    return ChannelTabContent(
        kind = kind,
        items =
            strictItems.ifEmpty {
                when (kind) {
                    ChannelTabKind.Shorts -> harvested?.shorts.orEmpty().map { FeedItem.ShortItem(it) }
                    else -> harvested?.videos.orEmpty().map { FeedItem.VideoItem(it) }
                }
            },
        sections = sections,
        filters = channelFilterGroups(),
        continuation = harvested?.continuation ?: channelItemContinuation(),
        owner = owner,
    )
}

/** Only the first browse carries the header; a continuation is anonymous, so the caller's owner wins. */
internal fun JsonElement.resolveOwner(fallback: FeedItemOwner): FeedItemOwner {
    val metadata = findRenderers("channelMetadataRenderer")["channelMetadataRenderer"]
    val rawName = metadata?.get("title").youtubeText()?.takeIf(String::isNotBlank) ?: fallback.name
    val rawAvatar = metadata?.get("avatar").largestImageUrl() ?: fallback.avatarUrl
    // Same TVHTML5 gap as the header: no channelMetadataRenderer, so the owner
    // parses blank. Only then, fall back to the node naming this channel id.
    val channelNode =
        if ((rawName.isBlank() || rawAvatar.isBlank()) && fallback.id.isNotBlank()) {
            findChannelNode(fallback.id)
        } else {
            null
        }
    return FeedItemOwner(
        id =
            metadata?.get("externalChannelId").stringOrNull()
                ?: metadata?.get("externalId").stringOrNull()
                ?: fallback.id,
        name =
            rawName.ifBlank {
                channelNode?.get("title").youtubeText()?.takeIf(String::isNotBlank) ?: ""
            },
        avatarUrl =
            rawAvatar.ifBlank {
                channelNode?.get("avatar").largestImageUrl()
                    ?: channelNode?.get("thumbnail").largestImageUrl()
                    ?: ""
            },
    )
}

/** The selected tab's own content, so a Home shelf walk cannot wander into another tab's payload. */
private fun JsonElement.selectedTabContent(): JsonElement =
    objectOrNull()
        ?.get("contents")
        .objectOrNull()
        ?.get("twoColumnBrowseResultsRenderer")
        .objectOrNull()
        ?.get("tabs")
        .arrayOrNull()
        .orEmpty()
        .firstNotNullOfOrNull { tab ->
            val renderer = tab.objectOrNull()?.get("tabRenderer").objectOrNull() ?: return@firstNotNullOfOrNull null
            if (renderer["selected"].stringOrNull() != "true") return@firstNotNullOfOrNull null
            renderer["content"].objectOrNull()?.get("sectionListRenderer")
        }
        ?: this
