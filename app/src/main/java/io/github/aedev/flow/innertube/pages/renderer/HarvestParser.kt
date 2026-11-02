package io.github.aedev.flow.innertube.pages.renderer

import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.pages.ServerFeedPage
import io.github.aedev.flow.innertube.pages.arrayOrNull
import io.github.aedev.flow.innertube.pages.objectOrNull
import io.github.aedev.flow.innertube.pages.parseYouTubeViewCount
import io.github.aedev.flow.innertube.pages.stringOrNull
import io.github.aedev.flow.innertube.pages.youtubeText
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import io.github.aedev.flow.utils.relativedate.RelativeUploadDateParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Shape-tolerant fallback for browse responses the strict feed parser cannot read.
 *
 * Some surfaces answer in renderer shapes [io.github.aedev.flow.innertube.pages.toServerFeedPage]
 * has no entry for — a TV/guest home response can carry a full grid whose items use none of
 * the known video renderers, so the strict walk yields an empty page and the surface falls to
 * its error state even though the server sent a perfectly good feed. Rather than guess the
 * new shape, this harvester scans the whole tree for anything that looks like a video item —
 * a plausible [videoId] next to a thumbnail — and builds plain [Video]s out of whatever
 * metadata fields the node happens to carry.
 *
 * It runs only when the strict parse found nothing, so a response the strict parser reads
 * never passes through here.
 */
fun JsonElement.harvestFeedPage(): ServerFeedPage {
    val videos = mutableListOf<Video>()
    val shorts = mutableListOf<Video>()
    val seen = HashSet<String>()
    var continuation: String? = null

    fun walk(
        node: JsonElement?,
        inShortsContext: Boolean,
    ) {
        when (node) {
            is JsonArray -> node.forEach { walk(it, inShortsContext) }

            is JsonObject -> {
                val shortsContext =
                    inShortsContext || node.keys.any { it in SHORTS_CONTEXT_KEYS }
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
                node.toHarvestedVideo(isShort = shortsContext || node.opensReelPlayer())?.let { video ->
                    if (seen.add(video.id)) {
                        if (video.isShort) shorts += video else videos += video
                    }
                }
                node.values.forEach { walk(it, shortsContext) }
            }

            else -> Unit
        }
    }
    walk(this, inShortsContext = false)

    return ServerFeedPage(
        videos = videos,
        shorts = shorts,
        continuation = continuation,
    )
}

/** Ancestor containers whose items are Shorts whatever renderer the item itself uses. */
private val SHORTS_CONTEXT_KEYS =
    setOf(
        "reelItemRenderer",
        "shortsLockupViewModel",
        "reelShelfRenderer",
        "shortsShelfRenderer",
    )

private val VIDEO_ID_PATTERN = Regex("[A-Za-z0-9_-]{11}")

/**
 * A node counts as a video item when it carries an 11-character video id and a thumbnail —
 * the pair every playable row has and pure endpoint/metadata nodes never do.
 */
private fun JsonObject.toHarvestedVideo(isShort: Boolean): Video? {
    // Classic rows carry "videoId"; the lockup view-models the TV and new web layouts are
    // built from carry the same id as "contentId", the picture under "contentImage" and
    // the title inside the metadata view-model. A harvester that only reads the classic
    // keys walks straight past a whole TV home page.
    val videoId =
        (this["videoId"].stringOrNull() ?: this["contentId"].stringOrNull())
            ?.takeIf { VIDEO_ID_PATTERN.matches(it) }
            ?: return null
    if (
        !containsKey("thumbnail") && !containsKey("title") && !containsKey("headline") &&
        !containsKey("contentImage") && !containsKey("metadata")
    ) {
        return null
    }
    val uploadText = this["publishedTimeText"].youtubeText().orEmpty()
    return Video(
        id = videoId,
        title =
            this["title"].youtubeText()
                ?: this["headline"].youtubeText()
                ?: lockupTitle()
                ?: "",
        channelName = harvestBylineName().orEmpty(),
        channelId = harvestBylineChannelId().orEmpty(),
        thumbnailUrl = ThumbnailUrlResolver.normalizeVideoThumbnail(videoId, this["thumbnail"].largestImageUrl()),
        duration =
            parseDurationText(this["lengthText"].youtubeText())
                ?: parseDurationText(overlayTimeText())
                ?: 0,
        viewCount = parseYouTubeViewCount(this["viewCountText"].youtubeText()),
        uploadDate = uploadText,
        timestamp = RelativeUploadDateParser.parse(uploadText, YouTube.locale.hl) ?: 0L,
        channelThumbnailUrl =
            this["channelThumbnail"].largestImageUrl().orEmpty()
                .ifEmpty { nestedChannelThumbnail().orEmpty() },
        isShort = isShort,
    )
}

/** The title a lockup view-model carries, down in its metadata block. */
private fun JsonObject.lockupTitle(): String? =
    this["metadata"]
        .objectOrNull()
        ?.get("lockupMetadataViewModel")
        .objectOrNull()
        ?.get("title")
        .youtubeText()

/** The running time a grid row prints on its thumbnail when it carries no `lengthText`. */
private fun JsonObject.overlayTimeText(): String? =
    this["thumbnailOverlays"]
        .arrayOrNull()
        .orEmpty()
        .firstNotNullOfOrNull { overlay ->
            overlay
                .objectOrNull()
                ?.get("thumbnailOverlayTimeStatusRenderer")
                .objectOrNull()
                ?.get("text")
                .youtubeText()
        }

/** A tap that opens the reel player marks the row as a Short even outside a shorts shelf. */
private fun JsonObject.opensReelPlayer(): Boolean {
    val endpoint = this["navigationEndpoint"].objectOrNull() ?: return false
    if (endpoint["reelWatchEndpoint"] != null) return true
    return endpoint["commandMetadata"]
        .objectOrNull()
        ?.get("webCommandMetadata")
        .objectOrNull()
        ?.get("webPageType")
        .stringOrNull() == "WEB_PAGE_TYPE_SHORTS"
}

private fun JsonObject.harvestBylineName(): String? =
    listOf("ownerText", "shortBylineText", "longBylineText")
        .firstNotNullOfOrNull { this[it].youtubeText()?.takeIf(String::isNotBlank) }
        ?: lockupByline()?.name
        ?: nestedBylineText()

private fun JsonObject.harvestBylineChannelId(): String? =
    listOf("ownerText", "shortBylineText", "longBylineText")
        .firstNotNullOfOrNull { this[it].bylineBrowseId() }
        ?: lockupByline()?.id
        ?: nestedBylineId()

/**
 * A tile's channel line often sits one metadata block down, not on the tile
 * itself — without the walk, those cards render a placeholder avatar and a
 * blank name. Only reached when the tile itself carries no byline, and the
 * walk visits parents first, so the shallowest nested line wins.
 */
private fun JsonObject.nestedBylineText(): String? {
    var found: String? = null
    forEachObject { node ->
        if (found != null) return@forEachObject
        found =
            listOf("ownerText", "shortBylineText", "longBylineText")
                .firstNotNullOfOrNull { node[it].youtubeText()?.takeIf(String::isNotBlank) }
    }
    return found
}

private fun JsonObject.nestedBylineId(): String? {
    var found: String? = null
    forEachObject { node ->
        if (found != null) return@forEachObject
        found =
            listOf("ownerText", "shortBylineText", "longBylineText")
                .firstNotNullOfOrNull { node[it].bylineBrowseId() }
    }
    return found
}

private fun JsonObject.nestedChannelThumbnail(): String? {
    var found: String? = null
    forEachObject { node ->
        if (found != null) return@forEachObject
        found = node["channelThumbnail"].largestImageUrl()?.takeIf(String::isNotBlank)
    }
    return found
}

/** The channel id behind a byline's runs: the browseId its navigation endpoint points at. */
private fun JsonElement?.bylineBrowseId(): String? =
    arrayOrNull()
        ?.firstNotNullOfOrNull { it.objectOrNull()?.bylineBrowseId() }
        ?: objectOrNull()?.let { node ->
            node["navigationEndpoint"]
                .objectOrNull()
                ?.get("browseEndpoint")
                .objectOrNull()
                ?.get("browseId")
                .stringOrNull()
                ?: node["runs"].bylineBrowseId()
        }
