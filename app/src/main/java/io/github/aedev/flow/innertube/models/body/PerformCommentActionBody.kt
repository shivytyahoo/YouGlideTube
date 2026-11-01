package io.github.aedev.flow.innertube.models.body

import io.github.aedev.flow.innertube.models.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * `comment/perform_comment_action`: the actions are the toolbar surface's own payload,
 * replayed verbatim — the same pattern as the create box token. Working clients
 * (YouTube.js) call the endpoint exactly this way for like/dislike and their undos.
 */
@Serializable
data class PerformCommentActionBody(
    val context: Context,
    val actions: List<JsonElement>,
)
