package io.github.shivytyahoo.glidetube.innertube.pages

import io.github.shivytyahoo.glidetube.innertube.models.YTItem

data class LibraryContinuationPage(
    val items: List<YTItem>,
    val continuation: String?,
)
