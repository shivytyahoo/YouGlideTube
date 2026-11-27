package io.github.shivytyahoo.glidetube.ui.screens.home

import io.github.shivytyahoo.glidetube.data.local.VideoHistoryEntry
import io.github.shivytyahoo.glidetube.data.model.Video

data class HomeUiState(
    val videos: List<Video> = emptyList(),
    val shorts: List<Video> = emptyList(),
    val continueWatchingVideos: List<VideoHistoryEntry> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isRefreshing: Boolean = false,
    val hasMorePages: Boolean = true,
    /** The last load-more found nothing because its requests failed; the feed stays open for a retry. */
    val loadMoreFailed: Boolean = false,
    val error: String? = null,
    val isFlowFeed: Boolean = false,
    /** Signed out: the empty state offers search and sign-in instead of a bare error. */
    val isGuest: Boolean = false,
    val lastRefreshTime: Long = 0L,
    /** Why each card is on Home; channel memory cards also say so under their title. */
    val origins: Map<String, HomeCardOrigin> = emptyMap(),
    /** Real minutes watched today, from the on-device ledger; the greeting's second line. */
    val todayWatchedMinutes: Int = 0,
    /** The taste's strongest keywords, as the interests row; empty until anything is learned. */
    val interests: List<String> = emptyList(),
    /** The guest feed's source shelves; signed-in Home has none — its feed stays as served. */
    val shelves: List<HomeShelf> = emptyList(),
)
