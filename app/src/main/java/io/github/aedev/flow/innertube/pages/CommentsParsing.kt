package io.github.aedev.flow.innertube.pages

import io.github.aedev.flow.utils.ShivLog

import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.data.model.RichText
import io.github.aedev.flow.data.model.RichTextEmoji
import io.github.aedev.flow.data.model.RichTextHighlight
import io.github.aedev.flow.data.model.RichTextSpan
import io.github.aedev.flow.data.model.RichTextTarget
import io.github.aedev.flow.utils.YouTubeLink
import io.github.aedev.flow.utils.youTubeBrowseLink
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

private const val HEART_STATE_HEARTED = "TOOLBAR_HEART_STATE_HEARTED"
private const val LIKE_STATE_LIKED = "TOOLBAR_LIKE_STATE_LIKED"
private const val LIKE_STATE_DISLIKED = "TOOLBAR_LIKE_STATE_DISLIKED"

/**
 * The first object stored under [key] anywhere inside this element, depth-first.
 * A surface command is not always the endpoint itself: it can be an executor whose
 * command list carries the endpoint, which is how YouTube.js resolves it too.
 */
private fun JsonElement?.findKeyObject(key: String): JsonObject? {
    when (this) {
        is JsonObject -> {
            (this[key] as? JsonObject)?.let { return it }
            for (value in values) value.findKeyObject(key)?.let { return it }
        }
        is JsonArray -> for (item in this) item.findKeyObject(key)?.let { return it }
        else -> Unit
    }
    return null
}

/** The first string stored under [key] anywhere inside this element, depth-first. */
private fun JsonElement?.findKeyString(key: String): String? {
    when (this) {
        is JsonObject -> {
            this[key].stringOrNull()?.let { return it }
            for (value in values) value.findKeyString(key)?.let { return it }
        }
        is JsonArray -> for (item in this) item.findKeyString(key)?.let { return it }
        else -> Unit
    }
    return null
}

/** One toolbar-surface command's perform-action endpoint, as raw JSON to replay verbatim. */
private fun JsonObject?.surfaceAction(command: String): String? =
    this?.get(command).findKeyObject("performCommentActionEndpoint")?.toString()

/** Every object stored under [key] anywhere inside this element, depth-first. */
private fun JsonElement?.findAllKeyObjects(
    key: String,
    out: MutableList<JsonObject> = mutableListOf(),
): List<JsonObject> {
    when (this) {
        is JsonObject -> {
            (this[key] as? JsonObject)?.let(out::add)
            for (value in values) value.findAllKeyObjects(key, out)
        }
        is JsonArray -> for (item in this) item.findAllKeyObjects(key, out)
        else -> Unit
    }
    return out
}

/** A command's own node: the first of its wrapper keys, or the node itself (NavigationEndpoint's rule). */
private fun JsonElement?.unwrapCommand(): JsonObject? {
    val obj = this.objectOrNull() ?: return null
    for (wrapper in listOf("innertubeCommand", "command", "performOnceCommand")) {
        obj[wrapper].objectOrNull()?.let { return it }
    }
    return obj
}

/** A buttonRenderer's command: the first of `serviceEndpoint`, `navigationEndpoint`, `command`. */
private fun JsonElement?.buttonCommand(): JsonElement? {
    val obj = this.objectOrNull() ?: return null
    for (key in listOf("serviceEndpoint", "navigationEndpoint", "command")) {
        obj[key]?.let { return it }
    }
    return null
}

/**
 * The path a command names for itself, kept only when it is a plain `comment/…` path under
 * `/youtubei/v1/` — a payload can never send a write somewhere else (Limusic's rule).
 */
private fun plainCommentPath(apiUrl: String?): String? {
    val path = apiUrl?.removePrefix("/youtubei/v1/") ?: return null
    val valid =
        path.startsWith("comment/") &&
            path.length > "comment/".length &&
            !path.contains("//") &&
            !path.endsWith('/') &&
            path.all { it.isLowerCase() || it.isDigit() || it == '_' || it == '/' }
    return path.takeIf { valid }
}

private fun JsonElement?.namedCommentPath(): String? =
    this
        .objectOrNull()
        ?.get("commandMetadata")
        .objectOrNull()
        ?.get("webCommandMetadata")
        .objectOrNull()
        ?.get("apiUrl")
        .stringOrNull()
        .let(::plainCommentPath)

/** One server-issued write: the params token, the path it posts to, and the two body fields. */
private data class WriteOffer(
    val params: String,
    val path: String,
    val paramsField: String,
    val textField: String,
)

/** The write a button's service endpoint carries, when it holds a non-empty params string. */
private fun replayWrite(
    service: JsonElement?,
    endpointKey: String,
    paramsField: String,
    fallbackPath: String,
    textField: String,
): WriteOffer? {
    val data = service.unwrapCommand() ?: return null
    val params =
        data[endpointKey]
            .objectOrNull()
            ?.get(paramsField)
            .stringOrNull()
            ?.takeIf(String::isNotEmpty)
            ?: return null
    val path = data.namedCommentPath() ?: service.namedCommentPath() ?: fallbackPath
    return WriteOffer(params = params, path = path, paramsField = paramsField, textField = textField)
}

/**
 * The reply write a comment's toolbar surface offers. The dialog's reply button carries a
 * `createCommentReplyEndpoint` with its OWN `createReplyParams` — not the create box's
 * `createCommentParams`, which is why the 0.3.15 extraction (and its Reply button) found
 * nothing. Path: the one the command names, else `comment/create_comment_reply`.
 */
private fun JsonObject?.replyWrite(): WriteOffer? {
    val dialog =
        this
            ?.get("replyCommand")
            .findAllKeyObjects("commentReplyDialogRenderer")
            .firstOrNull()
            ?: return null
    val button = dialog["replyButton"].objectOrNull()?.get("buttonRenderer")
    return replayWrite(
        service = button.buttonCommand(),
        endpointKey = "createCommentReplyEndpoint",
        paramsField = "createReplyParams",
        fallbackPath = "comment/create_comment_reply",
        textField = "commentText",
    )
}

/** A dialog's pre-filled text, read plainly: `simpleText`, `content`, or its runs joined. */
private fun JsonElement?.editableText(): String? {
    val obj = this.objectOrNull() ?: return null
    val text =
        obj["simpleText"].stringOrNull()
            ?: obj["content"].stringOrNull()
            ?: obj["runs"]
                .arrayOrNull()
                ?.map { it.objectOrNull()?.get("text").stringOrNull() ?: return null }
                ?.joinToString("")
    return text?.takeIf(String::isNotEmpty)
}

private class MenuWrites(
    val edit: WriteOffer?,
    val editText: String?,
    val deleteAction: String?,
)

/** An edit dialog one of the menu item's shapes opens: its write and the pre-filled text. */
private fun editOf(nav: JsonElement?): Pair<WriteOffer, String?>? {
    val root = nav.unwrapCommand() ?: return null
    // A top-level comment: updateCommentDialogEndpoint → commentDialogRenderer → submitButton.
    root["updateCommentDialogEndpoint"]
        .objectOrNull()
        ?.get("dialog")
        .objectOrNull()
        ?.get("commentDialogRenderer")
        .objectOrNull()
        ?.let { dialog ->
            val button = dialog["submitButton"].objectOrNull()?.get("buttonRenderer")
            replayWrite(
                service = button.buttonCommand(),
                endpointKey = "updateCommentEndpoint",
                paramsField = "updateCommentParams",
                fallbackPath = "comment/update_comment",
                textField = "commentText",
            )?.let { return it to dialog["editableText"].editableText() }
        }
    // A reply: updateCommentReplyDialogEndpoint → commentReplyDialogRenderer → replyButton,
    // and its text travels as `replyText`, not `commentText`.
    root["updateCommentReplyDialogEndpoint"]
        .objectOrNull()
        ?.get("dialog")
        .objectOrNull()
        ?.get("commentReplyDialogRenderer")
        .objectOrNull()
        ?.let { dialog ->
            val button = dialog["replyButton"].objectOrNull()?.get("buttonRenderer")
            replayWrite(
                service = button.buttonCommand(),
                endpointKey = "updateCommentReplyEndpoint",
                paramsField = "updateReplyParams",
                fallbackPath = "comment/update_comment_reply",
                textField = "replyText",
            )?.let { return it to dialog["editableText"].editableText() }
        }
    return null
}

/** Delete: the confirm dialog's button holds a perform-action endpoint, replayed like a vote. */
private fun deleteOf(nav: JsonElement?): String? {
    val service =
        nav
            .unwrapCommand()
            ?.get("confirmDialogEndpoint")
            .objectOrNull()
            ?.get("content")
            .objectOrNull()
            ?.get("confirmDialogRenderer")
            .objectOrNull()
            ?.get("confirmButton")
            .objectOrNull()
            ?.get("buttonRenderer")
            .buttonCommand()
    val endpoint = service.unwrapCommand()?.get("performCommentActionEndpoint").objectOrNull() ?: return null
    val hasToken =
        endpoint["action"].stringOrNull()?.isNotEmpty() == true ||
            endpoint["actions"].arrayOrNull()?.isNotEmpty() == true
    return endpoint.toString().takeIf { hasToken }
}

/**
 * The viewer's own comment's menu writes, matched by structure — never by (localized) label
 * or icon, so any language shows the same offers. Only called for `isCurrentUser` comments.
 */
private fun JsonObject?.menuWrites(): MenuWrites {
    val menu = this?.get("menuCommand") ?: return MenuWrites(null, null, null)
    val items =
        menu.findAllKeyObjects("menuRenderer").flatMap { renderer ->
            renderer["items"].arrayOrNull().orEmpty()
        }
    var edit: WriteOffer? = null
    var editText: String? = null
    var delete: String? = null
    for (item in items) {
        val renderer =
            item
                .objectOrNull()
                ?.values
                ?.firstOrNull { it.objectOrNull()?.get("navigationEndpoint") != null }
                .objectOrNull()
                ?: continue
        val nav = renderer["navigationEndpoint"]
        if (edit == null) {
            editOf(nav)?.let { found ->
                edit = found.first
                editText = found.second
                continue
            }
        }
        if (delete == null) delete = deleteOf(nav)
    }
    return MenuWrites(edit, editText, delete)
}

/** Delete-probe budget: the first two parsed entities per process reveal their full key sets. */
private val commentProbeCount = java.util.concurrent.atomic.AtomicInteger(0)

/** Write-offer budget: the first few parsed comments per process report what they offer. */
private val writeProbeCount = java.util.concurrent.atomic.AtomicInteger(0)

/**
 * Entity payloads keyed by the key a `commentViewModel` points at.
 *
 * A comment response carries the renderers and their content in two separate places: the thread
 * list names keys, and `frameworkUpdates` holds the payloads those keys resolve to.
 */
internal fun JsonElement?.commentMutations(): Map<String, JsonElement?> =
    objectOrNull()
        ?.get("frameworkUpdates")
        .objectOrNull()
        ?.get("entityBatchUpdate")
        .objectOrNull()
        ?.get("mutations")
        .arrayOrNull()
        .orEmpty()
        .mapNotNull { mutation ->
            val objectValue = mutation.objectOrNull() ?: return@mapNotNull null
            val key = objectValue["entityKey"].stringOrNull() ?: return@mapNotNull null
            key to objectValue["payload"]
        }.toMap()

/**
 * The reply a `comment/create_comment_reply` answer itself carries, built from its mutation
 * batch — no list fetch, no propagation wait. The batch holds the new reply's entity plus its
 * toolbar surface, which is where the vote/menu/reply tokens live. Returns null when the batch
 * does not carry a usable own reply, so the caller falls back to the bare row + refresh paths.
 */
internal fun parsePostedReply(responseBody: String, postedText: String): Comment? {
    val root = runCatching { Json.parseToJsonElement(responseBody) }.getOrNull() ?: return null
    val mutations = root.commentMutations()
    val wanted = postedText.trim()
    val entity =
        mutations.values.firstNotNullOfOrNull { payload ->
            val entityPayload =
                payload.objectOrNull()?.get("commentEntityPayload").objectOrNull()
                    ?: return@firstNotNullOfOrNull null
            val author = entityPayload["author"].objectOrNull()
                ?: return@firstNotNullOfOrNull null
            if (!author["isCurrentUser"].booleanOrFalse()) return@firstNotNullOfOrNull null
            val props = entityPayload["properties"].objectOrNull()
                ?: return@firstNotNullOfOrNull null
            val text = props["content"].youtubeText()?.trim()
            if (text != wanted) return@firstNotNullOfOrNull null
            entityPayload
        } ?: return null
    val props = entity["properties"].objectOrNull() ?: return null
    val author = entity["author"].objectOrNull()
    val toolbar = entity["toolbar"].objectOrNull()
    val toolbarSurface =
        props["toolbarSurfaceKey"]
            .stringOrNull()
            ?.let { mutations[it] }
            .objectOrNull()
            ?.get("engagementToolbarSurfaceEntityPayload")
            .objectOrNull()
    val replyWrite = toolbarSurface.replyWrite()
    val menu = toolbarSurface.menuWrites()
    val comment =
        Comment(
            id =
                props["commentId"].stringOrNull()
                    ?.takeIf(String::isNotBlank)
                    ?: return null,
            author = author?.get("displayName").stringOrNull().orEmpty(),
            authorThumbnail =
                normalizeImageUrl(
                    author?.get("avatarThumbnailUrl").stringOrNull()?.takeIf(String::isNotBlank)
                        ?: entity["avatar"]
                            .objectOrNull()
                            ?.get("image")
                            .objectOrNull()
                            ?.get("sources")
                            .bestThumbnailUrl()
                        ?: "",
                ),
            text = props["content"].youtubeText().orEmpty(),
            likeCount = parseCount(toolbar?.get("likeCountNotliked")),
            publishedTime = props["publishedTime"].stringOrNull().orEmpty(),
            authorChannelId =
                author?.get("channelId").stringOrNull()
                    ?: author
                        ?.get("navigationEndpoint")
                        .objectOrNull()
                        ?.get("browseEndpoint")
                        .objectOrNull()
                        ?.get("browseId")
                        .stringOrNull()
                    ?: "",
            richText = props["content"].toRichText(null),
            likeCountText = toolbar?.get("likeCountNotliked").youtubeText().orEmpty(),
            likeAction = toolbarSurface.surfaceAction("likeCommand"),
            unlikeAction = toolbarSurface.surfaceAction("unlikeCommand"),
            dislikeAction = toolbarSurface.surfaceAction("dislikeCommand"),
            undislikeAction = toolbarSurface.surfaceAction("undislikeCommand"),
            replyParams = replyWrite?.params,
            replyPath = replyWrite?.path,
            isOwn = true,
            editParams = menu.edit?.params,
            editPath = menu.edit?.path,
            editParamsField = menu.edit?.paramsField,
            editTextField = menu.edit?.textField,
            editPrefill = menu.editText,
            deleteAction = menu.deleteAction,
        )
    ShivLog.i(
        "replyEntity like=${comment.likeAction != null} edit=${comment.editParams != null} " +
            "delete=${comment.deleteAction != null} reply=${comment.replyParams != null}",
    )
    return comment
}

internal fun JsonObject.toModernComment(
    mutations: Map<String, JsonElement?>,
    repliesRenderer: JsonObject?,
    ownVideoId: String? = null,
    pinnedText: String? = null,
): Comment? {
    val commentKey = this["commentKey"].stringOrNull() ?: return null
    val entity =
        mutations[commentKey]
            .objectOrNull()
            ?.get("commentEntityPayload")
            .objectOrNull()
            ?: return null
    val properties = entity["properties"].objectOrNull() ?: return null
    val author = entity["author"].objectOrNull()
    val toolbar = entity["toolbar"].objectOrNull()
    // Where would a delete/edit action live? No open client implements comment deletion, so
    // the payload itself must say what an own comment carries: log the full key sets of the
    // entity's three parts for the first couple of comments parsed in this process.
    val probe = commentProbeCount.incrementAndGet() <= 2
    if (probe) {
        ShivLog.i("commentEntityProbe entity=${entity.keys} props=${properties.keys} " +
                "author=${author?.keys} toolbar=${toolbar?.keys}",
        )
    }
    val id =
        properties["commentId"].stringOrNull()
            ?: this["commentId"].stringOrNull()
            ?: return null
    // Post comments carry the avatar only as this flat author URL; the nested image/sources
    // objects below exist on video comments and are kept as fallbacks.
    val avatar =
        author?.get("avatarThumbnailUrl").stringOrNull()?.takeIf(String::isNotBlank)
            ?: entity["avatar"]
                .objectOrNull()
                ?.get("image")
                .objectOrNull()
                ?.get("sources")
                .bestThumbnailUrl()
            ?: author
                ?.get("avatar")
                .objectOrNull()
                ?.get("image")
                .objectOrNull()
                ?.get("sources")
                .bestThumbnailUrl()
            ?: ""
    val toolbarState =
        properties["toolbarStateKey"]
            .stringOrNull()
            ?.let { mutations[it] }
            .objectOrNull()
            ?.get("engagementToolbarStateEntityPayload")
            .objectOrNull()
    // The toolbar surface carries this comment's own vote commands — the endpoints
    // YouTube.js replays for like/dislike. The view model names its key; some sections
    // repeat it in the entity properties.
    val toolbarSurface =
        (this["toolbarSurfaceKey"].stringOrNull() ?: properties["toolbarSurfaceKey"].stringOrNull())
            ?.let { mutations[it] }
            .objectOrNull()
            ?.get("engagementToolbarSurfaceEntityPayload")
            .objectOrNull()
    if (probe) {
        val commentSurface =
            this["commentSurfaceKey"]
                .stringOrNull()
                ?.let { mutations[it] }
                .objectOrNull()
        val commentSurfaceInner =
            commentSurface
                ?.values
                ?.firstOrNull()
                .objectOrNull()
        ShivLog.i("commentSurfaceProbe vm=${this.keys} surface=${toolbarSurface?.keys} " +
                "commentSurface=${commentSurface?.keys} commentSurfaceInner=${commentSurfaceInner?.keys}",
        )
    }
    val isOwnComment = author?.get("isCurrentUser").booleanOrFalse()
    val replyWrite = toolbarSurface.replyWrite()
    val menu = if (isOwnComment) toolbarSurface.menuWrites() else MenuWrites(null, null, null)
    // Own comments always report their offers (they are rare and always the interesting
    // case); other comments share a small budget so the log stays readable.
    if (isOwnComment || writeProbeCount.incrementAndGet() <= 6) {
        ShivLog.i("writeOffers own=$isOwnComment reply=${replyWrite != null} " +
                "edit=${menu.edit != null} delete=${menu.deleteAction != null}",
        )
    }
    val content = properties["content"]
    val richText = content.toRichText(ownVideoId)
    val resolvedPinnedText =
        pinnedText?.takeIf(String::isNotBlank)
            ?: this["pinnedText"].youtubeText()?.takeIf(String::isNotBlank)
            ?: properties["pinnedText"].youtubeText()?.takeIf(String::isNotBlank)
    val likeCountText = toolbar?.get("likeCountNotliked").youtubeText().orEmpty()
    return Comment(
        id = id,
        author = author?.get("displayName").stringOrNull().orEmpty(),
        authorThumbnail = normalizeImageUrl(avatar),
        text = richText?.text ?: content.youtubeText().orEmpty(),
        likeCount = parseCount(toolbar?.get("likeCountNotliked")),
        publishedTime = properties["publishedTime"].stringOrNull().orEmpty(),
        replyCount = parseCount(toolbar?.get("replyCount")),
        isPinned = resolvedPinnedText != null,
        continuationToken = repliesRenderer?.findReplyContinuation(),
        authorChannelId =
            author?.get("channelId").stringOrNull()
                ?: author
                    ?.get("navigationEndpoint")
                    .objectOrNull()
                    ?.get("browseEndpoint")
                    .objectOrNull()
                    ?.get("browseId")
                    .stringOrNull()
                ?: "",
        richText = richText,
        likeCountText = likeCountText,
        pinnedByText = resolvedPinnedText,
        isHearted = toolbarState?.get("heartState").stringOrNull() == HEART_STATE_HEARTED,
        heartedByText = toolbar?.get("heartActiveTooltip").stringOrNull(),
        isVerified = author?.get("isVerified").booleanOrFalse(),
        isCreator = author?.get("isCreator").booleanOrFalse(),
        isArtist = author?.get("isArtist").booleanOrFalse(),
        isLiked = toolbarState?.get("likeState").stringOrNull() == LIKE_STATE_LIKED,
        isDisliked = toolbarState?.get("likeState").stringOrNull() == LIKE_STATE_DISLIKED,
        likeCountLikedText = toolbar?.get("likeCountLiked").youtubeText().orEmpty(),
        likeAction = toolbarSurface.surfaceAction("likeCommand"),
        unlikeAction = toolbarSurface.surfaceAction("unlikeCommand"),
        dislikeAction = toolbarSurface.surfaceAction("dislikeCommand"),
        undislikeAction = toolbarSurface.surfaceAction("undislikeCommand"),
        replyParams = replyWrite?.params,
        replyPath = replyWrite?.path,
        isOwn = isOwnComment,
        editParams = menu.edit?.params,
        editPath = menu.edit?.path,
        editParamsField = menu.edit?.paramsField,
        editTextField = menu.edit?.textField,
        editPrefill = menu.editText,
        deleteAction = menu.deleteAction,
    )
}

internal fun JsonObject.toLegacyComment(repliesRenderer: JsonObject?): Comment? {
    val id = this["commentId"].stringOrNull()?.takeIf(String::isNotBlank) ?: return null
    return Comment(
        id = id,
        author = this["authorText"].youtubeText().orEmpty(),
        authorThumbnail = normalizeImageUrl(this["authorThumbnail"].bestThumbnailUrl().orEmpty()),
        text = this["contentText"].youtubeText().orEmpty(),
        likeCount = parseCount(this["voteCount"]),
        publishedTime = this["publishedTimeText"].youtubeText().orEmpty(),
        replyCount = parseCount(this["replyCount"]),
        isPinned = this["pinnedCommentBadge"] != null,
        continuationToken = repliesRenderer?.findReplyContinuation(),
        authorChannelId =
            this["authorEndpoint"]
                .objectOrNull()
                ?.get("browseEndpoint")
                .objectOrNull()
                ?.get("browseId")
                .stringOrNull()
                ?: "",
        likeCountText = this["voteCount"].youtubeText().orEmpty(),
        isVerified = this["authorCommentBadge"] != null,
    )
}

/**
 * Builds the text and its typed spans from an attributed-text object.
 *
 * [ownVideoId] separates a timestamp — a watch endpoint pointing back at the video the text
 * belongs to — from a link to a different video, which is a navigation rather than a seek.
 */
internal fun JsonElement?.toRichText(ownVideoId: String?): RichText? {
    val value = objectOrNull() ?: return null
    val text = value["content"].stringOrNull() ?: return null
    val spans =
        value["commandRuns"]
            .arrayOrNull()
            .orEmpty()
            .mapNotNull { run -> run.objectOrNull()?.toRichTextSpan(text.length, ownVideoId) }
    val emojis =
        value["attachmentRuns"]
            .arrayOrNull()
            .orEmpty()
            .mapNotNull { run -> run.objectOrNull()?.toRichTextEmoji(text.length) }
    val highlights =
        value["decorationRuns"]
            .arrayOrNull()
            .orEmpty()
            .mapNotNull { run -> run.objectOrNull()?.toRichTextHighlight(text.length) }
    return RichText(text = text, spans = spans, emojis = emojis, highlights = highlights)
}

private fun JsonObject.toRichTextSpan(
    textLength: Int,
    ownVideoId: String?,
): RichTextSpan? {
    val start = this["startIndex"].intOrNull() ?: return null
    val length = this["length"].intOrNull() ?: return null
    if (start < 0 || length <= 0 || start + length > textLength) return null
    val command =
        this["onTap"]
            .objectOrNull()
            ?.get("innertubeCommand")
            .objectOrNull()
            ?: return null
    val target = command.toRichTextTarget(ownVideoId) ?: return null
    return RichTextSpan(start = start, length = length, target = target)
}

private fun JsonObject.toRichTextTarget(ownVideoId: String?): RichTextTarget? {
    this["watchEndpoint"].objectOrNull()?.let { watch ->
        val videoId = watch["videoId"].stringOrNull() ?: return@let
        val startSeconds = watch["startTimeSeconds"].longOrNull()
        return if (videoId == ownVideoId && startSeconds != null) {
            RichTextTarget.Timestamp(startSeconds)
        } else {
            RichTextTarget.Video(videoId = videoId, startSeconds = startSeconds)
        }
    }
    this["browseEndpoint"].objectOrNull()?.let { browse ->
        val browseId = browse["browseId"].stringOrNull() ?: return@let
        val canonical = browse["canonicalBaseUrl"].stringOrNull()
        if (canonical != null && canonical.startsWith("/hashtag/")) {
            return RichTextTarget.Hashtag(canonical.removePrefix("/hashtag/"))
        }
        return when (youTubeBrowseLink(browseId)) {
            null, is YouTubeLink.Channel -> RichTextTarget.Channel(browseId)
            else -> RichTextTarget.Url("https://www.youtube.com/browse/$browseId")
        }
    }
    this["urlEndpoint"].objectOrNull()?.let { url ->
        val raw = url["url"].stringOrNull() ?: return@let
        return RichTextTarget.Url(unwrapRedirectUrl(raw))
    }
    val webUrl =
        this["commandMetadata"]
            .objectOrNull()
            ?.get("webCommandMetadata")
            .objectOrNull()
            ?.get("url")
            .stringOrNull()
            ?: return null
    return RichTextTarget.Url(unwrapRedirectUrl(webUrl))
}

private fun JsonObject.toRichTextHighlight(textLength: Int): RichTextHighlight? {
    val highlight =
        this["textDecorator"]
            .objectOrNull()
            ?.get("highlightTextDecorator")
            .objectOrNull()
            ?: return null
    val start = highlight["startIndex"].intOrNull() ?: return null
    val length = highlight["length"].intOrNull() ?: return null
    if (start < 0 || length <= 0 || start + length > textLength) return null
    return RichTextHighlight(start = start, length = length)
}

private fun JsonObject.toRichTextEmoji(textLength: Int): RichTextEmoji? {
    val start = this["startIndex"].intOrNull() ?: return null
    val length = this["length"].intOrNull() ?: return null
    if (start < 0 || length < 0 || start + length > textLength) return null
    val element = this["element"].objectOrNull() ?: return null
    val image =
        element["type"]
            .objectOrNull()
            ?.get("imageType")
            .objectOrNull()
            ?.get("image")
            .objectOrNull()
            ?: return null
    val url = image["sources"].bestThumbnailUrl() ?: return null
    val label =
        element["properties"]
            .objectOrNull()
            ?.get("accessibilityProperties")
            .objectOrNull()
            ?.get("label")
            .stringOrNull()
            .orEmpty()
    return RichTextEmoji(
        start = start,
        length = length,
        imageUrl = normalizeImageUrl(url),
        label = label,
    )
}

/**
 * YouTube prints outbound links as `youtube.com/redirect?...&q=<encoded target>`. The wrapper is
 * what the user would otherwise see and copy, so the real destination is recovered here.
 */
internal fun unwrapRedirectUrl(url: String): String {
    if (!url.contains("/redirect?")) return url
    val query = url.substringAfter('?', "").split('&')
    val target = query.firstOrNull { it.startsWith("q=") }?.removePrefix("q=") ?: return url
    return runCatching { java.net.URLDecoder.decode(target, "UTF-8") }.getOrDefault(url)
}

internal fun JsonObject.continuationToken(): String? =
    this["continuationEndpoint"]
        .objectOrNull()
        ?.get("continuationCommand")
        .objectOrNull()
        ?.get("token")
        .stringOrNull()
        ?: this["button"]
            .objectOrNull()
            ?.get("buttonRenderer")
            .objectOrNull()
            ?.get("command")
            .objectOrNull()
            ?.get("continuationCommand")
            .objectOrNull()
            ?.get("token")
            .stringOrNull()

internal fun JsonObject.findReplyContinuation(): String? =
    this["contents"]
        .arrayOrNull()
        ?.firstNotNullOfOrNull { content ->
            content
                .objectOrNull()
                ?.get("continuationItemRenderer")
                .objectOrNull()
                ?.continuationToken()
        }

internal fun JsonElement?.bestThumbnailUrl(): String? {
    val thumbnails =
        objectOrNull()?.get("thumbnails").arrayOrNull()
            ?: objectOrNull()?.get("sources").arrayOrNull()
            ?: arrayOrNull()
            ?: return null
    return thumbnails
        .maxByOrNull { thumbnail ->
            val objectValue = thumbnail.objectOrNull()
            val width = (objectValue?.get("width") as? JsonPrimitive)?.intOrNull ?: 0
            val height = (objectValue?.get("height") as? JsonPrimitive)?.intOrNull ?: 0
            width.toLong() * height.toLong().coerceAtLeast(1)
        }?.objectOrNull()
        ?.let { thumbnail ->
            thumbnail["url"].stringOrNull() ?: thumbnail["uri"].stringOrNull()
        }
}

internal fun JsonObject.accessibilityLabel(): String? =
    this["accessibility"].objectOrNull()?.get("label").stringOrNull()
        ?: this["accessibilityData"]
            .objectOrNull()
            ?.get("accessibilityData")
            .objectOrNull()
            ?.get("label")
            .stringOrNull()

internal fun String.countTextFromAccessibilityLabel(): String = Regex("""[\d.,]+\s*[KkMmBb]?""").find(this)?.value?.trim() ?: this

internal fun parseCount(element: JsonElement?): Int {
    val primitive = element as? JsonPrimitive
    primitive?.intOrNull?.let { return it.coerceAtLeast(0) }
    primitive?.longOrNull?.let { return it.coerceIn(0, Int.MAX_VALUE.toLong()).toInt() }
    return parseYouTubeViewCount(element.youtubeText() ?: primitive?.content)
        .coerceIn(0, Int.MAX_VALUE.toLong())
        .toInt()
}

private fun JsonElement?.intOrNull(): Int? = (this as? JsonPrimitive)?.intOrNull

private fun JsonElement?.longOrNull(): Long? = (this as? JsonPrimitive)?.longOrNull

internal fun JsonElement?.booleanOrFalse(): Boolean = (this as? JsonPrimitive)?.content?.toBoolean() == true
