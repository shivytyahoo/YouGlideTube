package io.github.shivytyahoo.glidetube.innertube.pages

import io.github.shivytyahoo.glidetube.innertube.models.AlbumItem

data class ExplorePage(
    val newReleaseAlbums: List<AlbumItem>,
    val moodAndGenres: List<MoodAndGenres.Item>,
)
