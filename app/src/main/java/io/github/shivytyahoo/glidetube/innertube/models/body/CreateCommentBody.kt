package io.github.shivytyahoo.glidetube.innertube.models.body

import io.github.shivytyahoo.glidetube.innertube.models.Context
import kotlinx.serialization.Serializable

/** `comment/create`: the params token comes from the comment section's own create box. */
@Serializable
data class CreateCommentBody(
    val context: Context,
    val createCommentParams: String,
    val commentText: String,
)
