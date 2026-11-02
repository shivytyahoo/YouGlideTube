package io.github.aedev.flow.innertube.pages

import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.innertube.pages.renderer.forEachObject
import io.github.aedev.flow.innertube.pages.renderer.largestImageUrl
import kotlinx.serialization.json.JsonElement

/**
 * Every channel in the guide's subscriptions section, in the guide's own order. Channel
 * entries are the only guide entries whose id is a channel id — the section headers and
 * destinations use feed ids — so the prefix is the membership test.
 */
fun JsonElement.toGuideChannels(): List<Channel> {
    val channels = LinkedHashMap<String, Channel>()
    forEachObject { node ->
        val entry = node["guideEntryRenderer"].objectOrNull() ?: return@forEachObject
        val id =
            entry["entryData"]
                .objectOrNull()
                ?.get("guideEntryData")
                .objectOrNull()
                ?.get("guideEntryId")
                .stringOrNull()
                ?.takeIf { it.startsWith("UC") }
                ?: return@forEachObject
        if (id !in channels) {
            channels[id] =
                Channel(
                    id = id,
                    name = entry["formattedTitle"].youtubeText().orEmpty(),
                    thumbnailUrl = entry["thumbnail"].largestImageUrl().orEmpty(),
                    subscriberCount = 0L,
                    isSubscribed = true,
                )
        }
    }
    if (channels.isNotEmpty()) return channels.values.toList()
    // TVHTML5's guide carries no guideEntryRenderer section, so the strict scan
    // finds nothing and every channel surface renders empty. Only then, fall back
    // to any entry whose nested browse endpoint points at a channel — the guide
    // is a navigation menu, so a channel endpoint there is a subscription. The
    // title guard matters because the walk visits parents first: a wrapper above
    // the entry also contains the endpoint but none of the entry's own text.
    fun channelIdWithin(node: JsonElement?, depth: Int): String? {
        val obj = node.objectOrNull() ?: return null
        obj["browseEndpoint"]
            .objectOrNull()
            ?.get("browseId")
            .stringOrNull()
            ?.takeIf { it.startsWith("UC") }
            ?.let { return it }
        if (depth <= 0) return null
        for ((_, child) in obj) {
            channelIdWithin(child, depth - 1)?.let { return it }
        }
        return null
    }
    forEachObject { node ->
        val id = channelIdWithin(node, 3) ?: return@forEachObject
        val name =
            node["formattedTitle"].youtubeText()
                ?: node["title"].youtubeText()
                ?: node["text"].youtubeText()
                ?: ""
        val thumbnail = node["thumbnail"].largestImageUrl().orEmpty()
        if (id !in channels && (name.isNotBlank() || thumbnail.isNotBlank())) {
            channels[id] =
                Channel(
                    id = id,
                    name = name,
                    thumbnailUrl = thumbnail,
                    subscriberCount = 0L,
                    isSubscribed = true,
                )
        }
    }
    return channels.values.toList()
}
