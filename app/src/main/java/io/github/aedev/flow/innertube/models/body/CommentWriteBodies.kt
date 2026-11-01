package io.github.aedev.flow.innertube.models.body

import io.github.aedev.flow.innertube.models.Context
import kotlinx.serialization.Serializable

/**
 * The comment write bodies. These are typed on purpose: the HTTP client's JSON writes a
 * [Context] with its defaults encoded, exactly like the proven create body — a hand-built
 * JSON body encoded the context with a default-configured Json instead and silently dropped
 * its defaulted `request`/`user` parts, which is the shape the write endpoints refused.
 */

/** `comment/create_comment_reply`: the reply dialog's own token, not the create box's. */
@Serializable
data class CreateReplyBody(
    val context: Context,
    val createReplyParams: String,
    val commentText: String,
)

/** `comment/update_comment`: an own top-level comment's new text. */
@Serializable
data class UpdateCommentBody(
    val context: Context,
    val updateCommentParams: String,
    val commentText: String,
)

/** `comment/update_comment_reply`: an own reply's new text travels as `replyText`. */
@Serializable
data class UpdateReplyBody(
    val context: Context,
    val updateReplyParams: String,
    val replyText: String,
)
