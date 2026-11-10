package io.github.aedev.flow.innertube.pages

import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.pages.renderer.FeedItem
import io.github.aedev.flow.innertube.pages.renderer.FeedItemOwner
import io.github.aedev.flow.innertube.pages.renderer.findRenderers
import io.github.aedev.flow.innertube.pages.renderer.forEachObject
import io.github.aedev.flow.innertube.pages.renderer.harvestFeedPage
import io.github.aedev.flow.innertube.pages.renderer.largestImageUrl
import io.github.aedev.flow.innertube.pages.renderer.toFeedItem
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** One page of a YouTube playlist from the WEB `VL<id>` browse, and the token for the next. */
data class VideoPlaylistPage(
    val title: String? = null,
    val ownerName: String? = null,
    val ownerId: String? = null,
    val description: String? = null,
    val thumbnailUrl: String? = null,
    val videos: List<Video> = emptyList(),
    val continuation: String? = null,
    val isCourse: Boolean = false,
    /** Shorts-only playlists arrive as a grid of 100 that YouTube sends no next page for. */
    val isShortsGrid: Boolean = false,
    /** The count in the header, unavailable videos included. */
    val videoCount: Int? = null,
    /** The Save button's like tokens, when the page carries one. */
    val likeParams: String? = null,
    val removeLikeParams: String? = null,
)

/**
 * Reads the first page (header plus items) or a continuation (items only). Items arrive as video
 * lockups carrying their own channel; unavailable entries are simply absent from the response.
 */
internal fun JsonElement.toVideoPlaylistPage(): VideoPlaylistPage {
    val (items, isShortsGrid) = playlistItems()
    val header =
        findRenderers(
            "pageHeaderRenderer",
            "pageHeaderViewModel",
            "playlistHeaderRenderer",
            "playlistSidebarPrimaryInfoRenderer",
            "courseProgressViewModel",
        )
    val pageHeader = header["pageHeaderViewModel"]
    // Courses still arrive with the older playlistHeaderRenderer.
    val legacyHeader = header["playlistHeaderRenderer"]
    val sidebar = header["playlistSidebarPrimaryInfoRenderer"]
    val strictVideos =
        items
            .mapNotNull { item ->
                val video =
                    when (val feedItem = item.toFeedItem(FeedItemOwner())) {
                        is FeedItem.VideoItem -> feedItem.video
                        is FeedItem.ShortItem -> feedItem.video
                        else -> null
                    }
                video?.let { it.copy(playlistSetVideoId = item.findSetVideoId()) }
            }.distinctBy { it.id }
    // TVHTML5 playlist pages carry their items as tiles, which the strict item map
    // misses entirely — the page then parses to zero songs. Only when the strict
    // parse found nothing, harvest the same tree the feed parser already trusts,
    // so an account list never renders empty for a shape difference.
    val harvested = if (strictVideos.isEmpty()) harvestFeedPage() else null
    val owner = pageHeader?.headerOwner() ?: legacyHeader?.legacyOwner()
    val likeTokens = findPlaylistLikeTokens()
    return VideoPlaylistPage(
        title =
            (
                header["pageHeaderRenderer"]?.get("pageTitle").stringOrNull()
                    ?: pageHeader
                        ?.get("title")
                        .objectOrNull()
                        ?.get("dynamicTextViewModel")
                        .objectOrNull()
                        ?.get("text")
                        .youtubeText()
                    ?: legacyHeader?.get("title").youtubeText()
            )?.takeIf(String::isNotBlank),
        ownerName = owner?.name,
        ownerId = owner?.id,
        description =
            pageHeader
                ?.get("description")
                .objectOrNull()
                ?.get("descriptionPreviewViewModel")
                .objectOrNull()
                ?.get("description")
                .youtubeText()
                ?: sidebar?.get("description").youtubeText(),
        thumbnailUrl =
            sidebar
                ?.get("thumbnailRenderer")
                .objectOrNull()
                ?.get("playlistVideoThumbnailRenderer")
                .objectOrNull()
                ?.get("thumbnail")
                .largestImageUrl(),
        videos = strictVideos.ifEmpty { harvested?.videos.orEmpty() },
        continuation = harvested?.continuation ?: items.lastOrNull().continuationToken(),
        isCourse = header["courseProgressViewModel"] != null,
        isShortsGrid = isShortsGrid,
        videoCount =
            sidebar
                ?.get("stats")
                .arrayOrNull()
                ?.firstOrNull()
                .youtubeText()
                ?.filter(Char::isDigit)
                ?.toIntOrNull(),
        likeParams = likeTokens.first,
        removeLikeParams = likeTokens.second,
    )
}

/**
 * The Save button's like tokens. youtubei.js LikeEndpoint sends `{ target, params }`, with
 * `likeParams` for LIKE and `removeLikeParams` for INDIFFERENT (removelike) — the app used to
 * send no params at all, and the server answered removelike with 403 "The caller does not have
 * permission" (report 24). The button lives under the header in either shape, so the whole
 * response is walked and the first playlist-target likeEndpoint wins per field.
 */
private fun JsonElement.findPlaylistLikeTokens(): Pair<String?, String?> {
    var likeParams: String? = null
    var removeLikeParams: String? = null

    fun visit(element: JsonElement) {
        if (likeParams != null && removeLikeParams != null) return
        when (element) {
            is JsonObject -> {
                val endpoint = element["likeEndpoint"]?.objectOrNull()
                if (endpoint != null && endpoint.isPlaylistTarget()) {
                    if (likeParams == null) {
                        likeParams = endpoint["likeParams"].stringOrNull()?.takeIf(String::isNotBlank)
                    }
                    if (removeLikeParams == null) {
                        removeLikeParams = endpoint["removeLikeParams"].stringOrNull()?.takeIf(String::isNotBlank)
                    }
                }
                for (child in element.values) visit(child)
            }

            is JsonArray -> for (child in element) visit(child)
            else -> Unit
        }
    }
    visit(this)
    return likeParams to removeLikeParams
}

/** A likeEndpoint whose target is a playlist: `{ playlistId }`, or a bare playlist id. */
private fun JsonObject.isPlaylistTarget(): Boolean {
    val target = this["target"] ?: return false
    target.stringOrNull()?.let { return it.startsWith("PL") }
    return target.objectOrNull()?.get("playlistId").stringOrNull()?.isNotBlank() == true
}

/**
 * Items of the first page's list, or of a continuation's append action, and whether they came as
 * the Shorts grid instead of a list.
 */
private fun JsonElement.playlistItems(): Pair<List<JsonElement>, Boolean> {
    val root = objectOrNull() ?: return emptyList<JsonElement>() to false
    root["onResponseReceivedActions"].arrayOrNull()?.let { actions ->
        return actions.flatMap { action ->
            action
                .objectOrNull()
                ?.get("appendContinuationItemsAction")
                .objectOrNull()
                ?.get("continuationItems")
                .arrayOrNull()
                .orEmpty()
        } to false
    }
    var list: List<JsonElement> = emptyList()
    var isGrid = false
    root["contents"].forEachObject { node ->
        if (list.isNotEmpty()) return@forEachObject
        val contents = node["itemSectionRenderer"].objectOrNull()?.get("contents").arrayOrNull() ?: return@forEachObject
        // Playlist pages wrap their rows in playlistVideoListRenderer; system playlists
        // (Watch Later, Liked videos) still use its playlistVideoRenderer rows.
        val items =
            contents.flatMap { element ->
                element
                    .objectOrNull()
                    ?.get("playlistVideoListRenderer")
                    .objectOrNull()
                    ?.get("contents")
                    .arrayOrNull()
                    ?: listOf(element)
            }
        if (items.any { it.objectOrNull()?.containsKey("lockupViewModel") == true || it.objectOrNull()?.containsKey("playlistVideoRenderer") == true }) {
            list = items
            return@forEachObject
        }
        contents
            .firstNotNullOfOrNull {
                it
                    .objectOrNull()
                    ?.get("richGridRenderer")
                    .objectOrNull()
                    ?.get("contents")
                    .arrayOrNull()
            }?.takeIf { it.isNotEmpty() }
            ?.let {
                list = it
                isGrid = true
            }
    }
    return list to isGrid
}

/** The next-page token, from the older `continuationItemRenderer` or the `continuationItemViewModel` that replaced it. */
private fun JsonElement?.continuationToken(): String? {
    val item = objectOrNull() ?: return null
    val command =
        item["continuationItemRenderer"].objectOrNull()?.get("continuationEndpoint")
            ?: item["continuationItemViewModel"]
                .objectOrNull()
                ?.get("continuationCommand")
                .objectOrNull()
                ?.get("innertubeCommand")
    return command
        .objectOrNull()
        ?.get("continuationCommand")
        .objectOrNull()
        ?.get("token")
        .stringOrNull()
        ?.takeIf(String::isNotBlank)
}

private fun JsonObject.legacyOwner(): FeedItemOwner? {
    val run =
        this["ownerText"]
            .objectOrNull()
            ?.get("runs")
            .arrayOrNull()
            ?.firstOrNull()
            .objectOrNull() ?: return null
    val name = run["text"].stringOrNull()?.takeIf(String::isNotBlank) ?: return null
    val channelId =
        run["navigationEndpoint"]
            .objectOrNull()
            ?.get("browseEndpoint")
            .objectOrNull()
            ?.get("browseId")
            .stringOrNull()
            .orEmpty()
    return FeedItemOwner(id = channelId, name = name)
}

/** "by Name" in the header's avatar stack, linked to the owner's channel. */
private fun JsonObject.headerOwner(): FeedItemOwner? {
    var owner: FeedItemOwner? = null
    this["metadata"].forEachObject { node ->
        if (owner != null) return@forEachObject
        val text = node["avatarStackViewModel"].objectOrNull()?.get("text").objectOrNull() ?: return@forEachObject
        val name =
            text
                .youtubeText()
                ?.removePrefix("by ")
                ?.trim()
                ?.takeIf(String::isNotBlank) ?: return@forEachObject
        var channelId = ""
        text.forEachObject { run ->
            if (channelId.isEmpty()) {
                run["browseEndpoint"]
                    .objectOrNull()
                    ?.get("browseId")
                    .stringOrNull()
                    ?.let { channelId = it }
            }
        }
        owner = FeedItemOwner(id = channelId, name = name)
    }
    return owner
}

/** A playlist entry's setVideoId, wherever the item's menu endpoints carry it. */
private fun JsonElement.findSetVideoId(): String? {
    when (this) {
        is JsonObject -> {
            val direct = this["setVideoId"].stringOrNull()
            if (!direct.isNullOrBlank()) return direct
            for (child in values) {
                child.findSetVideoId()?.let { return it }
            }
        }

        is JsonArray -> {
            for (child in this) {
                child.findSetVideoId()?.let { return it }
            }
        }

        else -> Unit
    }
    return null
}
