package io.github.shivytyahoo.glidetube.innertube.models.response

import io.github.shivytyahoo.glidetube.innertube.models.Continuation
import io.github.shivytyahoo.glidetube.innertube.models.MusicResponsiveListItemRenderer
import io.github.shivytyahoo.glidetube.innertube.models.Tabs
import kotlinx.serialization.Serializable

@Serializable
data class SearchResponse(
    val contents: Contents?,
    val continuationContents: ContinuationContents?,
) {
    @Serializable
    data class Contents(
        val tabbedSearchResultsRenderer: Tabs?,
    )

    @Serializable
    data class ContinuationContents(
        val musicShelfContinuation: MusicShelfContinuation? = null,
    ) {
        @Serializable
        data class MusicShelfContinuation(
            val contents: List<Content>,
            val continuations: List<Continuation>?,
        ) {
            @Serializable
            data class Content(
                val musicResponsiveListItemRenderer: MusicResponsiveListItemRenderer,
            )
        }
    }
}
