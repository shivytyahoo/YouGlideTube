package io.github.aedev.flow.innertube.pages

import io.github.aedev.flow.data.model.Comment
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val COMMENT_SECTION_IDENTIFIER = "comment-item-section"
private const val PINNED_RENDERING_PRIORITY = "RENDERING_PRIORITY_PINNED_COMMENT"

/** One entry of the comment section's sort menu, with the continuation that loads that order. */
data class VideoCommentSort(
    val title: String,
    val token: String,
    val selected: Boolean,
)

data class VideoCommentsPage(
    val comments: List<Comment>,
    val continuation: String?,
    val sortOptions: List<VideoCommentSort> = emptyList(),
    val totalText: String? = null,
    val totalCount: Long? = null,
    /** The create box's token for posting a comment; only a logged-in section carries one. */
    val createCommentParams: String? = null,
    /**
     * Every create token the payload carries, in payload order. A section payload holds
     * more than one kind of create token (the box's own, and per-thread reply ones), and
     * only one of them posts a top-level comment — the poster tries them in order.
     */
    val createCommentParamsCandidates: List<String> = emptyList(),
    /** Where in the payload each candidate was found — the diagnosis for what a token IS. */
    val createCommentParamsPaths: List<String> = emptyList(),
)

/**
 * The continuation that loads a video's comment section, from a `next` response for that video.
 *
 * The section arrives as a placeholder the web player would only expand once the user scrolls to
 * it, which is why the first page costs a second request.
 */
internal fun JsonElement.videoCommentsContinuation(): String? {
    val contents =
        objectOrNull()
            ?.get("contents")
            .objectOrNull()
            ?.get("twoColumnWatchNextResults")
            .objectOrNull()
            ?.get("results")
            .objectOrNull()
            ?.get("results")
            .objectOrNull()
            ?.get("contents")
            .arrayOrNull()
            .orEmpty()
    return contents.firstNotNullOfOrNull { entry ->
        val section = entry.objectOrNull()?.get("itemSectionRenderer").objectOrNull() ?: return@firstNotNullOfOrNull null
        if (section["sectionIdentifier"].stringOrNull() != COMMENT_SECTION_IDENTIFIER) return@firstNotNullOfOrNull null
        section["contents"]
            .arrayOrNull()
            ?.firstNotNullOfOrNull { item ->
                item
                    .objectOrNull()
                    ?.get("continuationItemRenderer")
                    .objectOrNull()
                    ?.continuationToken()
            }
    }
}

/**
 * A page of a video's comments.
 *
 * [ownVideoId] is what lets a `1:57` in a comment be recognised as a seek into this video rather
 * than a link to another one.
 */
internal fun JsonElement.toVideoCommentsPage(ownVideoId: String?): VideoCommentsPage {
    val mutations = commentMutations()
    val items = continuationItems()
    val header =
        items.firstNotNullOfOrNull { item ->
            item.objectOrNull()?.get("commentsHeaderRenderer").objectOrNull()
        }
    val comments = mutableListOf<Comment>()
    var continuation: String? = null

    items.forEach { item ->
        val entry = item.objectOrNull() ?: return@forEach
        val thread = entry["commentThreadRenderer"].objectOrNull()
        if (thread != null) {
            thread.toVideoComment(mutations, ownVideoId)?.let(comments::add)
            return@forEach
        }
        if (continuation == null) {
            continuation = entry["continuationItemRenderer"].objectOrNull()?.continuationToken()
        }
    }

    return VideoCommentsPage(
        comments = comments.distinctBy(Comment::id),
        continuation = continuation,
        sortOptions = header?.toSortOptions().orEmpty(),
        totalText = header?.get("commentsCount").youtubeText(),
        totalCount =
            header
                ?.get("countText")
                .youtubeText()
                ?.let(::parseYouTubeViewCount)
                ?.takeIf { it > 0L },
        createCommentParams = findCreateCommentParams(),
        createCommentParamsCandidates =
            (findLongHeaderParams().map { it.second } + findAllCreateCommentParams()).distinct(),
        createCommentParamsPaths =
            findCreateCommentParamPaths() + findLongHeaderParams().map { "long:" + it.first },
    )
}

private val PARAM_KEY_HINTS = listOf("param", "create")

/**
 * Long token-like strings inside the comments header's own subtree, as (path, value).
 *
 * The value this payload carries at the box's `createCommentParams` is 40 characters —
 * far shorter than a working create token — so if the real one rides the header under a
 * less obvious key, it is here. Sort-menu continuations are excluded by the key filter
 * (they live under "continuation"); values only, never logged beyond path and length.
 */
fun JsonElement.findLongHeaderParams(): List<Pair<String, String>> {
    var header: JsonObject? = null
    fun seek(node: JsonElement) {
        if (header != null) return
        when (node) {
            is JsonObject -> {
                (node["commentsHeaderRenderer"] as? JsonObject)?.let {
                    header = it
                    return
                }
                node.values.forEach(::seek)
            }
            is JsonArray -> node.forEach(::seek)
            else -> Unit
        }
    }
    seek(this)
    val root = header ?: return emptyList()
    val found = mutableListOf<Pair<String, String>>()
    fun walk(
        node: JsonElement,
        key: String?,
        path: String,
    ) {
        when (node) {
            is JsonPrimitive ->
                node.stringOrNull()
                    ?.takeIf { value ->
                        value.length >= 60 &&
                            key != null &&
                            PARAM_KEY_HINTS.any { key.contains(it, ignoreCase = true) }
                    }?.let { found += path to it }
            is JsonObject ->
                node.entries.forEach { (k, v) -> walk(v, k, if (path.isEmpty()) k else "$path/$k") }
            is JsonArray -> node.forEachIndexed { i, v -> walk(v, key, "$path[$i]") }
        }
    }
    walk(root, null, "commentsHeaderRenderer")
    return found.distinctBy { it.second }.sortedByDescending { it.second.length }
}

/** The payload path of every create token, in the order found — what each token actually is. */
fun JsonElement.findCreateCommentParamPaths(): List<String> {
    val found = mutableListOf<String>()
    fun walk(
        node: JsonElement,
        path: String,
    ) {
        when (node) {
            is JsonObject -> {
                if (node["createCommentParams"].stringOrNull()?.isNotBlank() == true) {
                    found += path.ifEmpty { "<root>" }
                }
                node.entries.forEach { (key, value) ->
                    walk(value, if (path.isEmpty()) key else "$path/$key")
                }
            }
            is JsonArray -> node.forEachIndexed { index, value -> walk(value, "$path[$index]") }
            else -> Unit
        }
    }
    walk(this, "")
    return found.distinct()
}

/** The create box's params token, wherever in the payload it sits; null for guests. */
fun JsonElement.findCreateCommentParams(): String? =
    when (this) {
        is JsonObject -> {
            val direct = this["createCommentParams"].stringOrNull()?.takeIf(String::isNotBlank)
            direct ?: values.firstNotNullOfOrNull { it.findCreateCommentParams() }
        }
        is JsonArray -> firstNotNullOfOrNull { it.findCreateCommentParams() }
        else -> null
    }

/** Every create token in the payload, in the order they appear, duplicates removed. */
fun JsonElement.findAllCreateCommentParams(): List<String> {
    val found = mutableListOf<String>()
    fun walk(node: JsonElement) {
        when (node) {
            is JsonObject -> {
                node["createCommentParams"].stringOrNull()?.takeIf(String::isNotBlank)?.let(found::add)
                node.values.forEach(::walk)
            }
            is JsonArray -> node.forEach(::walk)
            else -> Unit
        }
    }
    walk(this)
    return found.distinct()
}

/** A page of one comment's replies: the same threads one level down, with no header of their own. */
internal fun JsonElement.toCommentRepliesPage(ownVideoId: String?): VideoCommentsPage {
    val mutations = commentMutations()
    val comments = mutableListOf<Comment>()
    var continuation: String? = null

    continuationItems().forEach { item ->
        val entry = item.objectOrNull() ?: return@forEach
        val view = entry["commentViewModel"].objectOrNull()
        val model = view?.get("commentViewModel").objectOrNull() ?: view
        if (model != null) {
            model.toModernComment(mutations, null, ownVideoId)?.let(comments::add)
            return@forEach
        }
        entry["commentThreadRenderer"]
            .objectOrNull()
            ?.toVideoComment(mutations, ownVideoId)
            ?.let(comments::add)
        if (continuation == null) {
            continuation = entry["continuationItemRenderer"].objectOrNull()?.continuationToken()
        }
    }

    return VideoCommentsPage(
        comments = comments.distinctBy(Comment::id),
        continuation = continuation,
    )
}

private fun JsonObject.toVideoComment(
    mutations: Map<String, JsonElement?>,
    ownVideoId: String?,
): Comment? {
    val rawViewModel = this["commentViewModel"].objectOrNull()
    val model =
        rawViewModel
            ?.get("commentViewModel")
            .objectOrNull()
            ?: rawViewModel
    val repliesRenderer =
        this["replies"]
            .objectOrNull()
            ?.get("commentRepliesRenderer")
            .objectOrNull()
    val comment =
        model?.toModernComment(
            mutations = mutations,
            repliesRenderer = repliesRenderer,
            ownVideoId = ownVideoId,
            pinnedText = model["pinnedText"].youtubeText(),
        )
            ?: this["comment"]
                .objectOrNull()
                ?.get("commentRenderer")
                .objectOrNull()
                ?.toLegacyComment(repliesRenderer)
    return if (this["renderingPriority"].stringOrNull() == PINNED_RENDERING_PRIORITY) {
        comment?.copy(isPinned = true)
    } else {
        comment
    }
}

private fun JsonObject.toSortOptions(): List<VideoCommentSort> =
    this["sortMenu"]
        .objectOrNull()
        ?.get("sortFilterSubMenuRenderer")
        .objectOrNull()
        ?.get("subMenuItems")
        .arrayOrNull()
        .orEmpty()
        .mapNotNull { item ->
            val entry = item.objectOrNull() ?: return@mapNotNull null
            val token =
                entry["serviceEndpoint"]
                    .objectOrNull()
                    ?.get("continuationCommand")
                    .objectOrNull()
                    ?.get("token")
                    .stringOrNull()
                    ?: return@mapNotNull null
            VideoCommentSort(
                title = entry["title"].youtubeText().orEmpty(),
                token = token,
                selected = entry["selected"].booleanOrFalse(),
            )
        }

/**
 * The items of every reload and append command in the response.
 *
 * A first page splits its header and its threads across two commands; a later page carries one.
 */
private fun JsonElement.continuationItems(): List<JsonElement> =
    objectOrNull()
        ?.get("onResponseReceivedEndpoints")
        .arrayOrNull()
        .orEmpty()
        .flatMap { endpoint ->
            val entry = endpoint.objectOrNull() ?: return@flatMap emptyList<JsonElement>()
            val command =
                entry["reloadContinuationItemsCommand"].objectOrNull()
                    ?: entry["appendContinuationItemsAction"].objectOrNull()
                    ?: return@flatMap emptyList<JsonElement>()
            command["continuationItems"].arrayOrNull() ?: JsonArray(emptyList())
        }
