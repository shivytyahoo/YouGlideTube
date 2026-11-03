package io.github.aedev.flow.innertube.pages.renderer

import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.innertube.pages.accessibilityLabel
import io.github.aedev.flow.innertube.pages.arrayOrNull
import io.github.aedev.flow.innertube.pages.bestThumbnailUrl
import io.github.aedev.flow.innertube.pages.commentMutations
import io.github.aedev.flow.innertube.pages.findAllCreateCommentParams
import io.github.aedev.flow.innertube.pages.findCreateCommentParams
import io.github.aedev.flow.innertube.pages.continuationToken
import io.github.aedev.flow.innertube.pages.countTextFromAccessibilityLabel
import io.github.aedev.flow.innertube.pages.findReplyContinuation
import io.github.aedev.flow.innertube.pages.normalizeImageUrl
import io.github.aedev.flow.innertube.pages.objectOrNull
import io.github.aedev.flow.innertube.pages.stringOrNull
import io.github.aedev.flow.innertube.pages.toLegacyComment
import io.github.aedev.flow.innertube.pages.toModernComment
import io.github.aedev.flow.innertube.pages.youtubeText
import io.github.aedev.flow.utils.ShivLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

data class CommunityPost(
    val id: String,
    val authorName: String,
    val authorAvatarUrl: String,
    val text: String,
    val attachment: PostAttachment?,
    val likeCountText: String,
    val commentCountText: String,
    val commentEndpointParams: String?,
    val publishedTimeText: String,
    // The like toggle's own endpoints, raw JSON; null when the post carried none
    // (signed out, or a shape without action buttons), where liking cannot be offered.
    val isLiked: Boolean = false,
    val likeAction: String? = null,
    val unlikeAction: String? = null,
)

data class CommunityPostsPage(
    val posts: List<CommunityPost>,
    val continuation: String?,
)

data class CommunityCommentsPage(
    val comments: List<Comment>,
    val continuation: String?,
    val commentCountText: String? = null,
    // The section's top-level comment box tokens, same scan video comments use;
    // empty for guests, where the composer must stay hidden.
    val createCommentParams: String? = null,
    val createCommentParamsCandidates: List<String> = emptyList(),
)

internal fun JsonElement.toCommunityPostsPage(
    fallbackAuthorName: String,
    fallbackAuthorAvatarUrl: String,
    owner: FeedItemOwner = FeedItemOwner(name = fallbackAuthorName, avatarUrl = fallbackAuthorAvatarUrl),
): CommunityPostsPage {
    val posts = mutableListOf<CommunityPost>()
    var continuation: String? = null

    fun collect(element: JsonElement?) {
        when (element) {
            is JsonArray -> {
                element.forEach(::collect)
            }

            is JsonObject -> {
                val thread = element["backstagePostThreadRenderer"].objectOrNull()
                val renderer =
                    thread
                        ?.get("post")
                        .objectOrNull()
                        ?.get("backstagePostRenderer")
                        .objectOrNull()
                        ?: element["postRenderer"].objectOrNull()

                if (renderer != null) {
                    renderer
                        .toCommunityPost(fallbackAuthorName, fallbackAuthorAvatarUrl, owner)
                        ?.let(posts::add)
                    return
                }

                if (continuation == null) {
                    continuation =
                        element["continuationItemRenderer"]
                            .objectOrNull()
                            ?.continuationToken()
                }
                element.values.forEach(::collect)
            }

            else -> {
                Unit
            }
        }
    }

    collect(this)
    return CommunityPostsPage(
        posts = posts.distinctBy(CommunityPost::id),
        continuation = continuation,
    )
}

internal fun JsonElement.toCommunityCommentsPage(): CommunityCommentsPage {
    val root = objectOrNull()
    val mutations = commentMutations()

    val comments = mutableListOf<Comment>()
    var continuation: String? = null

    fun collect(element: JsonElement?) {
        when (element) {
            is JsonArray -> {
                element.forEach(::collect)
            }

            is JsonObject -> {
                val thread = element["commentThreadRenderer"].objectOrNull()
                if (thread != null) {
                    val rawViewModel = thread["commentViewModel"].objectOrNull()
                    val viewModel =
                        rawViewModel
                            ?.get("commentViewModel")
                            .objectOrNull()
                            ?: rawViewModel
                    val repliesRenderer =
                        thread["replies"]
                            .objectOrNull()
                            ?.get("commentRepliesRenderer")
                            .objectOrNull()
                    val comment =
                        viewModel?.toModernComment(mutations, repliesRenderer)
                            ?: thread["comment"]
                                .objectOrNull()
                                ?.get("commentRenderer")
                                .objectOrNull()
                                ?.toLegacyComment(repliesRenderer)
                    comment?.let(comments::add)
                    return
                }

                element["commentViewModel"]
                    .objectOrNull()
                    ?.toModernComment(mutations, null)
                    ?.let {
                        comments.add(it)
                        return
                    }

                element["commentRenderer"]
                    .objectOrNull()
                    ?.toLegacyComment(null)
                    ?.let {
                        comments.add(it)
                        return
                    }

                if (continuation == null) {
                    continuation =
                        element["continuationItemRenderer"]
                            .objectOrNull()
                            ?.continuationToken()
                }
                element.values.forEach(::collect)
            }

            else -> {
                Unit
            }
        }
    }

    collect(this)
    return CommunityCommentsPage(
        comments = comments.distinctBy(Comment::id),
        continuation = continuation,
        commentCountText = findCommentCountText(root),
        createCommentParams = findCreateCommentParams(),
        createCommentParamsCandidates = findAllCreateCommentParams(),
    )
}

internal fun JsonObject.toCommunityPost(
    fallbackAuthorName: String,
    fallbackAuthorAvatarUrl: String,
    owner: FeedItemOwner,
): CommunityPost? {
    val id = this["postId"].stringOrNull()?.takeIf(String::isNotBlank) ?: return null
    val actionButtons =
        this["actionButtons"]
            .objectOrNull()
            ?.get("commentActionButtonsRenderer")
            .objectOrNull()
    val replyButton =
        actionButtons
            ?.get("replyButton")
            .objectOrNull()
            ?.get("buttonRenderer")
            .objectOrNull()
    val navigationEndpoint = replyButton?.get("navigationEndpoint").objectOrNull()
    val likeToggle =
        actionButtons
            ?.get("likeButton")
            .objectOrNull()
            ?.get("toggleButtonRenderer")
            .objectOrNull()
    val likeButtonEndpoint =
        actionButtons
            ?.get("likeButton")
            .objectOrNull()
            ?.get("buttonRenderer")
            .objectOrNull()
            ?.get("serviceEndpoint")
            .objectOrNull()
    val authorAvatar =
        this["authorThumbnail"].bestThumbnailUrl()
            ?: fallbackAuthorAvatarUrl
    return CommunityPost(
        id = id,
        authorName =
            this["authorText"]
                .youtubeText()
                ?.takeIf(String::isNotBlank)
                ?: fallbackAuthorName,
        authorAvatarUrl = normalizeImageUrl(authorAvatar),
        text = this["contentText"].youtubeText().orEmpty(),
        attachment = this["backstageAttachment"].toPostAttachment(owner),
        likeCountText = this["voteCount"].youtubeText().orEmpty(),
        commentCountText =
            replyButton?.get("text").youtubeText()
                ?: replyButton?.accessibilityLabel()?.countTextFromAccessibilityLabel()
                ?: "",
        commentEndpointParams =
            navigationEndpoint
                ?.get("browseEndpoint")
                .objectOrNull()
                ?.get("params")
                .stringOrNull()
                ?: navigationEndpoint
                    ?.get("signInEndpoint")
                    .objectOrNull()
                    ?.get("nextEndpoint")
                    .objectOrNull()
                    ?.get("browseEndpoint")
                    .objectOrNull()
                    ?.get("params")
                    .stringOrNull(),
        publishedTimeText = this["publishedTimeText"].youtubeText().orEmpty(),
        isLiked =
            (likeToggle?.get("isToggled") as? JsonPrimitive)?.booleanOrNull ?: false,
        likeAction =
            likeToggle?.get("defaultServiceEndpoint").objectOrNull()?.toString()
                ?: likeButtonEndpoint?.toString(),
        unlikeAction =
            likeToggle?.get("toggledServiceEndpoint").objectOrNull()?.toString(),
    ).also {
        // Keys only: identifies what endpoint the like button actually carries,
        // since the stored endpoint turned out to hold no likeEndpoint at all.
        ShivLog.i(
            "postCard like toggle=${likeToggle != null} " +
                "defaultKeys=${likeToggle?.get("defaultServiceEndpoint").objectOrNull()?.keys} " +
                "buttonKeys=${likeButtonEndpoint?.keys} toggled=${it.isLiked}",
        )
    }
}

private fun findCommentCountText(root: JsonObject?): String? {
    val panels = root?.get("engagementPanels").arrayOrNull().orEmpty()
    panels.forEach { panel ->
        val section =
            panel
                .objectOrNull()
                ?.get("engagementPanelSectionListRenderer")
                .objectOrNull()
                ?: return@forEach
        val identifier = section["panelIdentifier"].stringOrNull()
        if (identifier == "comment-item-section" || identifier == "engagement-panel-comments-section") {
            section["header"]
                .objectOrNull()
                ?.get("engagementPanelTitleHeaderRenderer")
                .objectOrNull()
                ?.get("contextualInfo")
                .youtubeText()
                ?.let { return it }
        }
    }
    return null
}
