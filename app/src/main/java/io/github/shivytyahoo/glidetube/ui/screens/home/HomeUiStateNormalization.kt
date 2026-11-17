package io.github.shivytyahoo.glidetube.ui.screens.home

import io.github.shivytyahoo.glidetube.data.local.VideoHistoryEntry
import io.github.shivytyahoo.glidetube.data.model.Video
import io.github.shivytyahoo.glidetube.data.model.distinctByNonBlankKeyOrSelf

internal fun HomeUiState.withUniqueLazyContent(): HomeUiState {
    val uniqueVideos = videos.distinctByNonBlankKeyOrSelf(Video::id)
    val uniqueShorts = shorts.distinctByNonBlankKeyOrSelf(Video::id)
    val uniqueHistory = continueWatchingVideos.distinctByNonBlankKeyOrSelf(VideoHistoryEntry::videoId)
    return if (
        uniqueVideos === videos &&
        uniqueShorts === shorts &&
        uniqueHistory === continueWatchingVideos
    ) {
        this
    } else {
        copy(
            videos = uniqueVideos,
            shorts = uniqueShorts,
            continueWatchingVideos = uniqueHistory,
        )
    }
}
