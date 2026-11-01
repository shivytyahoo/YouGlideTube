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
    return channels.values.toList()
}
