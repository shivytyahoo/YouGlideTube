package io.github.shivytyahoo.glidetube.ui.screens.home

import io.github.shivytyahoo.glidetube.data.model.Video
import io.github.shivytyahoo.glidetube.data.repository.YouTubeRepository

/**
 * The guest feed's seed pool: the session's searches first, then the trending chart. One page is
 * one search's results or one slice of the chart; the pool is spent when both run out. [rotation]
 * moves the starting point so a refresh shows different videos instead of the same list again, and
 * [excludeIds] keeps the previous feed's videos out of the new one.
 */
internal class GuestFallbackPool(
    private val repository: YouTubeRepository,
    queries: List<String>,
    private val excludeIds: Set<String>,
    private val rotation: Int,
    private val chartSupplier: suspend () -> List<Video>,
) {
    private val pendingQueries = ArrayDeque(rotate(queries, rotation))
    private val pendingChart = ArrayDeque<Video>()
    private var chartFetched = false

    val hasMore: Boolean
        get() = pendingQueries.isNotEmpty() || pendingChart.isNotEmpty() || !chartFetched

    suspend fun nextPage(count: Int): List<Video> {
        while (pendingQueries.isNotEmpty()) {
            val (page, _) = repository.searchVideos(pendingQueries.removeFirst())
            val fresh = page.filter { it.id !in excludeIds }
            if (fresh.isNotEmpty()) return fresh.take(count)
        }
        if (pendingChart.isNotEmpty()) return takeChart(count)
        if (!chartFetched) {
            chartFetched = true
            pendingChart.addAll(rotate(chartSupplier(), rotation).filter { it.id !in excludeIds })
            if (pendingChart.isNotEmpty()) return takeChart(count)
        }
        return emptyList()
    }

    private fun takeChart(count: Int): List<Video> {
        val slice = mutableListOf<Video>()
        repeat(count) { pendingChart.removeFirstOrNull()?.let(slice::add) }
        return slice
    }
}

internal fun <T> rotate(
    list: List<T>,
    rotation: Int,
): List<T> {
    if (list.isEmpty()) return list
    val start = rotation.mod(list.size)
    return list.subList(start, list.size) + list.subList(0, start)
}
