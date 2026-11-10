package io.github.aedev.flow.innertube.pages

import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.pages.renderer.FeedItem
import io.github.aedev.flow.innertube.pages.renderer.FeedItemOwner
import io.github.aedev.flow.innertube.pages.renderer.distinctKey
import io.github.aedev.flow.innertube.pages.renderer.toFeedItem
import io.github.aedev.flow.utils.ShivLog
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
    /** Playlists the surface carries (the account playlists page). */
    val playlists: List<io.github.aedev.flow.data.model.Playlist> = emptyList(),
    val chips: List<ServerChip> = emptyList(),
    val continuation: String? = null,
    /** History only: the Clear-all button's feedback token, from the feed actions. */
    val clearHistoryToken: String? = null,
    /** Where the Clear-all token came from: button, menu or noIcon — for the report. */
    val clearHistoryTokenSource: String? = null,
    /** History only: each video's own remove token, from its DELETE menu item. */
    val removeTokens: Map<String, String> = emptyMap(),
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
    // The newer continuation shape, kept separate so it never overrides a renderer
    // token: home and subscriptions already page correctly on the renderer token,
    // and only a surface with no renderer token at all falls back to this one.
    var viewModelContinuation: String? = null
    var chips: List<ServerChip> = emptyList()
    var clearHistoryToken: String? = null
    var clearHistoryTokenSource: String? = null
    val removeTokens = mutableMapOf<String, String>()
    // Text buttons carry no icon: every confirm-dialog feedback token one
    // yields, kept so a single unambiguous one can stand in for Clear-all.
    val noIconButtonTokens = mutableSetOf<String>()
    // One dump per page: the first DELETE control whose endpoint yields no
    // token, so the next report shows its real chain (report 29: the token
    // is in the page — 204 feedback endpoints — but no search found it).
    var clearButtonDumped = false

    // The DELETE control's own keys plus a bounded slice of its endpoint,
    // so the next report shows the chain the unwrapper missed.
    fun dumpClearButton(kind: String, control: JsonObject, endpoint: JsonElement?) {
        if (clearButtonDumped) return
        clearButtonDumped = true
        ShivLog.i(
            "historyClearButton kind=$kind keys=${control.keys.joinToString(",")} " +
                "endpoint=${endpoint.toString().take(600)}",
        )
    }

    fun walk(
        node: JsonElement?,
        inItem: Boolean = false,
    ) {
        when (node) {
            is JsonArray -> {
                // Items carry their own DELETE menu items; the Clear-all
                // control lives outside them, so the clear search skips
                // every subtree that parsed to a feed item.
                val itemElements = HashSet<JsonElement>()
                for (element in node) {
                    val item = element.toFeedItem(FeedItemOwner())
                    if (item == null) continue
                    itemElements += element
                    if (seen.add(item.distinctKey())) {
                        items += item
                        // History only: keep the video's own remove token with it,
                        // so a delete can be told to the server instead of only
                        // wiping the local row (report 26).
                        val videoId =
                            when (item) {
                                is FeedItem.VideoItem -> item.video.id
                                is FeedItem.ShortItem -> item.video.id
                                else -> null
                            }
                        if (videoId != null) {
                            element.findHistoryRemoveToken()?.let { removeTokens[videoId] = it }
                        }
                    }
                }
                node.forEach { element -> walk(element, inItem || element in itemElements) }
            }

            is JsonObject -> {
                // History only: the Clear-all control — the feed actions'
                // DELETE button, or the page header menu's DELETE item
                // (youtubei.js reads it as a Button either way). Its
                // confirm dialog carries the feedback token that clears the
                // server list (youtubei.js History.clearWatchHistory).
                if (!inItem && clearHistoryToken == null) {
                    node["buttonRenderer"].objectOrNull()?.let { button ->
                        val icon = button["icon"].objectOrNull()?.get("iconType").stringOrNull()
                        if (icon == "DELETE") {
                            val endpoint = button.endpointObject()
                            endpoint.feedbackTokenFromEndpoint()?.let {
                                clearHistoryToken = it
                                clearHistoryTokenSource = "button"
                            } ?: dumpClearButton("button", button, endpoint)
                        } else if (icon == null) {
                            // A text button (no icon) can still be Clear-all;
                            // only a single unambiguous one is ever trusted.
                            button.endpointObject().feedbackTokenFromEndpoint()?.let {
                                noIconButtonTokens += it
                            }
                        }
                    }
                    node["menuServiceItemRenderer"].objectOrNull()?.let { item ->
                        val icon = item["icon"].objectOrNull()?.get("iconType").stringOrNull()
                        if (icon == "DELETE") {
                            val endpoint = item.endpointObject()
                            endpoint.feedbackTokenFromEndpoint()?.let {
                                clearHistoryToken = it
                                clearHistoryTokenSource = "menu"
                            } ?: dumpClearButton("menu", item, endpoint)
                        }
                    }
                }
                node["continuationItemRenderer"]
                    .objectOrNull()
                    ?.get("continuationEndpoint")
                    .objectOrNull()
                    ?.get("continuationCommand")
                    .objectOrNull()
                    ?.get("token")
                    .stringOrNull()
                    ?.let { continuation = it }
                node["continuationItemViewModel"]
                    .objectOrNull()
                    ?.get("continuationCommand")
                    .objectOrNull()
                    ?.get("innertubeCommand")
                    .objectOrNull()
                    ?.get("continuationCommand")
                    .objectOrNull()
                    ?.get("token")
                    .stringOrNull()
                    ?.let { viewModelContinuation = it }
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
                node.values.forEach { walk(it, inItem) }
            }

            else -> Unit
        }
    }
    walk(this)

    if (clearHistoryToken == null && noIconButtonTokens.size == 1) {
        clearHistoryToken = noIconButtonTokens.first()
        clearHistoryTokenSource = "noIcon"
    }

    return ServerFeedPage(
        videos = items.filterIsInstance<FeedItem.VideoItem>().map { it.video },
        shorts =
            items
                .filterIsInstance<FeedItem.ShortItem>()
                .map { it.video.copy(isShort = true) },
        channels = items.filterIsInstance<FeedItem.RelatedChannelItem>().map { it.channel },
        playlists = items.filterIsInstance<FeedItem.PlaylistItem>().map { it.playlist },
        chips = chips,
        continuation = continuation ?: viewModelContinuation,
        clearHistoryToken = clearHistoryToken,
        clearHistoryTokenSource = clearHistoryTokenSource,
        removeTokens = removeTokens,
    )
}

/** The endpoint a button or menu item carries, whichever key the server used. */
private fun JsonElement?.endpointObject(): JsonElement? {
    val obj = this?.objectOrNull() ?: return null
    return obj["serviceEndpoint"] ?: obj["navigationEndpoint"] ?: obj["command"]
}

/**
 * The feedback token behind an endpoint object: direct, or behind the confirm
 * dialog youtubei.js unwraps for the history controls. Unwraps the
 * innertubeCommand/command wrappers NavigationEndpoint strips first, plus the
 * serial/parallel command wrappers it reads commands from.
 */
private fun JsonElement?.feedbackTokenFromEndpoint(): String? {
    val endpoint = this?.objectOrNull() ?: return null
    endpoint["innertubeCommand"]?.let { return it.feedbackTokenFromEndpoint() }
    endpoint["command"]?.let { return it.feedbackTokenFromEndpoint() }
    endpoint["performOnceCommand"]?.let { return it.feedbackTokenFromEndpoint() }
    endpoint["serviceEndpoint"]?.let { return it.feedbackTokenFromEndpoint() }
    endpoint["serialCommand"].objectOrNull()?.get("commands").arrayOrNull()?.firstOrNull()?.let {
        return it.feedbackTokenFromEndpoint()
    }
    endpoint["parallelCommand"].objectOrNull()?.get("commands").arrayOrNull()?.firstOrNull()?.let {
        return it.feedbackTokenFromEndpoint()
    }
    endpoint["commandExecutorCommand"].objectOrNull()?.get("commands").arrayOrNull()?.firstOrNull()?.let {
        return it.feedbackTokenFromEndpoint()
    }
    endpoint["feedbackEndpoint"].objectOrNull()?.get("feedbackToken").stringOrNull()?.let { return it }
    // The dialog's content wraps the confirm button in a renderer layer:
    // content.confirmDialogRenderer.confirmEndpoint — reading
    // content.confirmEndpoint directly finds nothing (report 33's
    // historyClearButton dump shows the renderer in between).
    val content = endpoint["confirmDialogEndpoint"].objectOrNull()?.get("content").objectOrNull()
    val confirm =
        content?.get("confirmDialogRenderer").objectOrNull()?.get("confirmEndpoint")
            ?: content?.get("confirmEndpoint")
    return confirm.feedbackTokenFromEndpoint()
}

/**
 * One history item's own remove token, from its DELETE menu item:
 * the old video menu's service item, or the lockup sheet's list item
 * (youtubei.js History.removeVideo). Null when the item carries no menu.
 */
private fun JsonElement.findHistoryRemoveToken(): String? {
    var token: String? = null

    fun walk(node: JsonElement?) {
        if (token != null || node == null) return
        when (node) {
            is JsonArray -> node.forEach(::walk)

            is JsonObject -> {
                node["menuServiceItemRenderer"].objectOrNull()?.let { item ->
                    val icon = item["icon"].objectOrNull()?.get("iconType").stringOrNull()
                    if (icon == "DELETE") {
                        token = item.endpointObject().feedbackTokenFromEndpoint()
                    }
                }
                node["listItemViewModel"].objectOrNull()?.let { item ->
                    // youtubei.js Thumbnail.fromResponse reads thumbnails (or
                    // sources) off the leadingImage object — it is not a bare
                    // array, and reading it as one matched nothing (report 28:
                    // 1022 list items in the page, 16 remove tokens found).
                    val leadingImage = item["leadingImage"].objectOrNull()
                    val firstImage =
                        leadingImage?.get("thumbnails").arrayOrNull()?.firstOrNull()
                            ?: leadingImage?.get("sources").arrayOrNull()?.firstOrNull()
                    val imageName =
                        firstImage?.objectOrNull()?.get("clientResource").objectOrNull()
                            ?.get("imageName").stringOrNull()
                    if (imageName == "DELETE") {
                        // The whole on-tap endpoint, not just its command:
                        // NavigationEndpoint unwraps innertubeCommand/command
                        // itself, and the feedback endpoint can sit directly
                        // on the tap object.
                        val onTap =
                            item["rendererContext"].objectOrNull()
                                ?.get("commandContext").objectOrNull()
                                ?.get("onTap")
                        token = onTap.feedbackTokenFromEndpoint()
                    }
                }
                if (token == null) node.values.forEach(::walk)
            }

            else -> Unit
        }
    }
    walk(this)
    return token
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
