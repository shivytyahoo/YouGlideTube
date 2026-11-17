package io.github.shivytyahoo.glidetube.innertube.models.body

import io.github.shivytyahoo.glidetube.innertube.models.Context
import kotlinx.serialization.Serializable

@Serializable
data class GetSearchSuggestionsBody(
    val context: Context,
    val input: String,
)
