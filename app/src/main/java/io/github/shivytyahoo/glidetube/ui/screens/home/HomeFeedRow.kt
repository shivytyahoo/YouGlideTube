package io.github.shivytyahoo.glidetube.ui.screens.home

import io.github.shivytyahoo.glidetube.data.model.Video

/** One slot of the home grid: a video card, or one of the shelves that own a whole row. */
internal sealed interface HomeFeedRow {
    val key: String

    data class VideoCard(
        val video: Video,
    ) : HomeFeedRow {
        override val key: String get() = video.id
    }

    data object ContinueWatching : HomeFeedRow {
        override val key: String = "continue_watching_shelf"
    }

    data object Shorts : HomeFeedRow {
        override val key: String = "shorts_shelf"
    }

    /** The time-of-day greeting and today's watch time, above everything else. */
    data class Greeting(
        val minutesToday: Int,
    ) : HomeFeedRow {
        override val key: String = "greeting"
    }

    /** The taste's strongest keywords, each a search. */
    data class Interests(
        val words: List<String>,
    ) : HomeFeedRow {
        override val key: String = "interests"
    }

    /** One source shelf of the guest feed, swiped across. */
    data class Shelf(
        val shelf: HomeShelf,
    ) : HomeFeedRow {
        override val key: String get() = "shelf:${shelf.source}:${shelf.tag.orEmpty()}"
    }

    val spansRow: Boolean get() = this !is VideoCard
}

/**
 * The shelves span every column, so they go in after exactly one full first row; anywhere else
 * the row above them would render with holes in it. Shelf videos leave the flat list: they
 * already show in their shelf.
 */
internal fun homeFeedRows(
    videos: List<Video>,
    columns: Int,
    showContinueWatching: Boolean,
    showShorts: Boolean,
    showGreeting: Boolean = false,
    minutesToday: Int = 0,
    interests: List<String> = emptyList(),
    shelves: List<HomeShelf> = emptyList(),
): List<HomeFeedRow> {
    if (videos.isEmpty() && shelves.isEmpty()) return emptyList()
    val firstRun = columns.coerceAtLeast(1).coerceAtMost(videos.size)
    val shelfVideoIds = shelves.flatMap { it.videos.map { video -> video.id } }.toSet()
    val rest = videos.filter { it.id !in shelfVideoIds }
    return buildList {
        if (showGreeting) add(HomeFeedRow.Greeting(minutesToday))
        if (interests.isNotEmpty()) add(HomeFeedRow.Interests(interests))
        rest.take(firstRun).mapTo(this, HomeFeedRow::VideoCard)
        if (showContinueWatching) add(HomeFeedRow.ContinueWatching)
        if (showShorts) add(HomeFeedRow.Shorts)
        shelves.forEach { add(HomeFeedRow.Shelf(it)) }
        rest.drop(firstRun).mapTo(this, HomeFeedRow::VideoCard)
    }
}
