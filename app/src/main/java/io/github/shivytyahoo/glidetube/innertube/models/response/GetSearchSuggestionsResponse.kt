package io.github.shivytyahoo.glidetube.innertube.models.response

import io.github.shivytyahoo.glidetube.innertube.models.SearchSuggestionsSectionRenderer
import kotlinx.serialization.Serializable

@Serializable
data class GetSearchSuggestionsResponse(
    val contents: List<Content>?,
) {
    @Serializable
    data class Content(
        val searchSuggestionsSectionRenderer: SearchSuggestionsSectionRenderer? = null,
    )
}
