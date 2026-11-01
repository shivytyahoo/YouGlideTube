package io.github.aedev.flow.ui.screens.home.chips

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import io.github.aedev.flow.data.local.HomeFeedCacheFilters
import io.github.aedev.flow.data.local.HomeFeedCacheRepository
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.engagement.FeedExclusions
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.data.repository.needsChannelMetadata
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.pages.ServerChip
import io.github.aedev.flow.ui.screens.home.filterValid
import io.github.aedev.flow.ui.screens.home.filterWatched
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** What a chip load needs from the Home feed it sits on. */
internal class ChipFeedContext(
    val filters: suspend () -> HomeFeedCacheFilters,
    val exclusions: suspend () -> FeedExclusions,
    val watched: () -> Set<String>,
)

/**
 * The chips above Home are the server's own topic chips — the ones YouTube puts on its
 * home feed — and each one loads that topic's home straight from the server with the
 * params the feed itself handed out. Nothing is assembled or ranked locally.
 */
class HomeChipFeeds
    @Inject
    constructor(
        private val repository: YouTubeRepository,
        private val homeFeedCache: HomeFeedCacheRepository,
    ) {
        private val chipsState = MutableStateFlow(HomeChipsState(chips = listOf(HomeChip.All)))
        internal val state: StateFlow<HomeChipsState> = chipsState.asStateFlow()

        private val cache = ChipResultCache()
        private lateinit var scope: CoroutineScope
        private lateinit var savedState: SavedStateHandle
        private lateinit var context: ChipFeedContext
        private var network: CoroutineDispatcher = PerformanceDispatcher.networkIO
        private var loadJob: Job? = null
        private val enrichInFlight = ConcurrentHashMap.newKeySet<String>()
        private val enrichAttempts = ConcurrentHashMap<String, Int>()

        @Volatile
        private var topics: List<HomeChip.Topic> = emptyList()

        internal fun attach(
            scope: CoroutineScope,
            savedState: SavedStateHandle,
            context: ChipFeedContext,
            io: CoroutineDispatcher = PerformanceDispatcher.diskIO,
            network: CoroutineDispatcher = PerformanceDispatcher.networkIO,
        ) {
            this.scope = scope
            this.savedState = savedState
            this.context = context
            this.network = network
            chipsState.update { it.copy(selected = savedState[SELECTED_KEY] ?: HomeChip.All.key) }
            publishChips()
        }

        /** The chips the latest server home page carried; called by the Home feed load. */
        fun onServerChips(chips: List<ServerChip>) {
            topics =
                chips
                    .filter { it.params != null && !it.label.equals("All", ignoreCase = true) }
                    .map { HomeChip.Topic(it.label, it.params) }
                    .distinctBy { it.key }
            publishChips()
            val selected = chipFor(chipsState.value.selected)
            if (selected != null && selected != HomeChip.All && chipsState.value.feed == null) {
                load(selected, force = false)
            }
        }

        /** Kept for the Home lifecycle: the chips now arrive with the feed itself. */
        fun refreshChips() = publishChips()

        fun select(key: String) {
            val chip = chipFor(key) ?: HomeChip.All
            savedState[SELECTED_KEY] = chip.key
            loadJob?.cancel()
            if (chip == HomeChip.All) {
                chipsState.update { it.copy(selected = chip.key, feed = null, isRefreshing = false) }
                return
            }
            chipsState.update {
                it.copy(selected = chip.key, feed = cache.get(chip, System.currentTimeMillis()) ?: ChipFeed.Loading)
            }
            load(chip, force = false)
        }

        /** Pull to refresh on a chip refreshes that chip alone. */
        fun refreshSelected() {
            val chip = chipFor(chipsState.value.selected) ?: return
            if (chip == HomeChip.All) return
            chipsState.update { it.copy(isRefreshing = true) }
            load(chip, force = true)
        }

        private fun chipFor(key: String): HomeChip? =
            (listOf(HomeChip.All) + topics).firstOrNull { it.key == key }

        private fun publishChips() {
            if (!::scope.isInitialized) return
            val all = listOf<HomeChip>(HomeChip.All) + topics
            chipsState.update { state ->
                if (all.none { it.key == state.selected }) {
                    state.copy(chips = all, selected = HomeChip.All.key, feed = null)
                } else {
                    state.copy(chips = all)
                }
            }
        }

        private fun load(
            chip: HomeChip,
            force: Boolean,
        ) {
            val topic = chip as? HomeChip.Topic ?: return
            val now = System.currentTimeMillis()
            if (!force) {
                cache.get(topic, now)?.let { cached ->
                    chipsState.update { if (it.selected == topic.key) it.copy(feed = cached) else it }
                    return
                }
            }
            loadJob?.cancel()
            loadJob =
                scope.launch(network) {
                    val feed =
                        try {
                            val page = YouTube.serverHome(params = topic.params).getOrNull()
                            if (page == null) {
                                ChipFeed.Failed
                            } else {
                                val exclusions = context.exclusions()
                                val videos =
                                    page.videos
                                        .distinctBy { it.id }
                                        .filterValid()
                                        .filterWatched(context.watched())
                                        .filterNot(exclusions::hidesFromRecommendations)
                                if (videos.isEmpty()) ChipFeed.Empty else ChipFeed.Videos(videos)
                            }
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (error: Exception) {
                            Log.d(TAG, "Chip ${topic.key} failed: ${error.message}")
                            ChipFeed.Failed
                        }
                    if (feed != ChipFeed.Failed) cache.put(topic, feed, System.currentTimeMillis())
                    chipsState.update {
                        if (it.selected == topic.key) it.copy(feed = feed, isRefreshing = false) else it
                    }
                }
        }

        /** Fills a card the grid has just shown with its channel's avatar, a few at a time. */
        fun enrichVisible(video: Video) {
            if (!video.needsChannelMetadata()) return
            if ((enrichAttempts[video.id] ?: 0) >= ENRICH_ATTEMPTS || !enrichInFlight.add(video.id)) return
            enrichAttempts.merge(video.id, 1, Int::plus)
            scope.launch(network) {
                val enriched =
                    try {
                        repository.enrichMissingChannelMetadata(listOf(video), limit = 1).firstOrNull()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        Log.d(TAG, "Filling ${video.id} failed: ${error.message}")
                        null
                    } finally {
                        enrichInFlight.remove(video.id)
                    }
                if (enriched == null || enriched == video) return@launch
                runCatching { homeFeedCache.saveVideoMetadata(listOf(enriched)) }
                chipsState.update { state ->
                    val feed = state.feed as? ChipFeed.Videos ?: return@update state
                    state.copy(feed = ChipFeed.Videos(feed.videos.map { if (it.id == video.id) enriched else it }))
                }
            }
        }

        private companion object {
            const val TAG = "HomeChipFeeds"

            // A failed fill is tried once more when the card is next shown, then left alone.
            const val ENRICH_ATTEMPTS = 2
            const val SELECTED_KEY = "home_selected_chip"
        }
    }
