package io.github.shivytyahoo.glidetube.innertube.pages

import io.github.shivytyahoo.glidetube.innertube.models.SongItem

data class PlaylistContinuationPage(
    val songs: List<SongItem>,
    val continuation: String?,
)
