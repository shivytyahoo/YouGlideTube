package io.github.aedev.flow.ui.screens.home

import io.github.aedev.flow.utils.ShivLog

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.data.engagement.FeedInvalidationBus
import io.github.aedev.flow.data.feed.FeedPrefetchQueue
import io.github.aedev.flow.data.feed.FeedPrefetchRequest
import io.github.aedev.flow.data.local.CachedHomeVideo
import io.github.aedev.flow.data.local.HomeFeedCacheFilters
import io.github.aedev.flow.data.local.HomeFeedCacheRepository
import io.github.aedev.flow.data.local.SessionHistory
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.model.toVideo
import io.github.aedev.flow.data.engagement.FeedExclusions
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.data.repository.needsChannelMetadata
import io.github.aedev.flow.data.shorts.ShortsFeedRepository
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.pages.renderer.FeedItemOwner
import io.github.aedev.flow.ui.screens.home.chips.ChipFeedContext
import io.github.aedev.flow.ui.screens.home.chips.HomeChipFeeds
import io.github.aedev.flow.ui.screens.home.chips.HomeChipsState
import io.github.aedev.flow.utils.PerformanceDispatcher
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import io.github.aedev.flow.utils.traced
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/** Cards a load-more attempt found but did not show yet, with why each is there. */
private class HeldPage(
    val videos: List<Video>,
    val origins: Map<String, HomeCardOrigin>,
)

private class PrefetchAttempt(
    val outcome: LoadMoreOutcome,
    val held: HeldPage? = null,
)

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val repository: YouTubeRepository,
        private val accountSubscriptions: io.github.aedev.flow.data.account.AccountSubscriptions,
        private val shortsRepository: ShortsFeedRepository,
        private val playerPreferences: io.github.aedev.flow.data.local.PlayerPreferences,
        private val shortsQueueHandoff: io.github.aedev.flow.data.shorts.queue.ShortsQueueHandoff,
        private val persistentHomeFeedCache: HomeFeedCacheRepository,
        private val sessionExclusions: io.github.aedev.flow.data.engagement.SessionExclusions,
        private val viewHistory: SessionHistory,
        private val chipFeeds: HomeChipFeeds,
        savedStateHandle: SavedStateHandle,
        @ApplicationContext private val appContext: Context,
    ) : ViewModel() {
        fun shortsShelfSource(
            shelf: List<io.github.aedev.flow.data.model.Video>,
            tapped: io.github.aedev.flow.data.model.Video,
        ) = shortsQueueHandoff.sourceForShelf(shelf, tapped)

        companion object {
            private const val TAG = "HomeViewModel"
            private const val UI_STATE_SUBSCRIPTION_TIMEOUT_MS = 5_000L
            private const val MAX_RELATED_SEEDS = 4
            private const val MIN_PAGE_SIZE = 8
            private const val LOAD_MORE_GRAPH_SEEDS = 3

            // Saved rows of each source ranked again on load-more; the best of them fill the page.
            private const val RESERVE_RERANK_PER_SOURCE = 16
            private const val MAX_SAVED_SEEDS = 5
            private const val SAVED_RELATED_SLOTS = 8

            // Never-dry load-more: fallback related pass seeded from feed + saved interests.
            private const val LOAD_MORE_FALLBACK_SEEDS = 4
            private const val FEED_SEED_POOL = 30
            private const val LATE_SUBS_MAX = 8
            private const val LATE_SUBS_PER_CHANNEL = 5

            // Before the grid reports its viewport the first screen is still in view; never shift it.
            private const val LATE_SUBS_MIN_INDEX = 5
            private const val SNAPSHOT_FEED_DEBOUNCE_MS = 2_000L

            // Trace section the Home refresh benchmark reads (#1301).
            private const val TRACE_WAVE1 = "home.wave1"

            // Next pages of earlier searches a short load-more page may fetch.
            private const val DEEPER_SEARCH_PAGES = 2
            private const val SEARCH_TIMEOUT_MS = 6_000L
            private const val SERVER_FEED_TIMEOUT_MS = 20_000L
            private const val TV_PREFETCH_AHEAD_VIDEO_COUNT = 12
            private const val TV_PREFETCH_TRIGGER_REMAINING_VIDEOS = 5
        }

        private val channelMetadataEnrichmentInFlight =
            java.util.concurrent.ConcurrentHashMap
                .newKeySet<String>()

        private val _uiState = MutableStateFlow(HomeUiState())
        val uiState: StateFlow<HomeUiState> =
            _uiState
                .map(HomeUiState::withUniqueLazyContent)
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(UI_STATE_SUBSCRIPTION_TIMEOUT_MS),
                    initialValue = _uiState.value.withUniqueLazyContent(),
                )

        private var isInitialized = false
        // A TV's SoC works far harder per card than a phone's: page in smaller steps there.
        private val isTvDevice =
            io.github.aedev.flow.platform.DeviceFormFactorDetector
                .detect(appContext) == io.github.aedev.flow.platform.DeviceFormFactor.TV

        private val homePrefetchQueue =
            FeedPrefetchQueue(
                prefetchAheadItemCount = if (isTvDevice) TV_PREFETCH_AHEAD_VIDEO_COUNT else HOME_PREFETCH_AHEAD_VIDEO_COUNT,
                triggerRemainingItems = if (isTvDevice) TV_PREFETCH_TRIGGER_REMAINING_VIDEOS else HOME_PREFETCH_TRIGGER_REMAINING_VIDEOS,
            )
        private val homePrefetchWorkerLock = Any()
        private var homePrefetchJob: Job? = null

        private var subsBacklog: List<Video> = emptyList()

        private var wave2Job: Job? = null
        private var feedJob: Job? = null
        private var subsTopUpJob: Job? = null
        private var neighbourJob: Job? = null
        private var visibleMetadataJob: Job? = null

        // Changes with every feed load, so work cancelled by leaving Home can tell it from a reload.
        @Volatile
        private var feedGeneration = 0

        // The server feed's next-page token; null when the server has no page loaded or left.
        @Volatile
        private var serverContinuation: String? = null

        private val refreshFailureEvents = Channel<Unit>(Channel.CONFLATED)

        /** A refresh that failed while the old feed stayed on screen. */
        internal val refreshFailures: Flow<Unit> = refreshFailureEvents.receiveAsFlow()

        @Volatile
        private var lastVisibleVideoIndex = -1
        private var savedInterestJob: Job? = null

        private val watchedVideoIds = MutableStateFlow<Set<String>>(emptySet())
        private val watchedShortIds = MutableStateFlow<Set<String>>(emptySet())

        /** The chips above the feed and what the selected one shows. */
        internal val chips: StateFlow<HomeChipsState> = chipFeeds.state

        fun selectChip(key: String) = chipFeeds.select(key)

        fun refreshSelectedChip() = chipFeeds.refreshSelected()

        fun enrichChipVideo(video: Video) = chipFeeds.enrichVisible(video)

        init {
            chipFeeds.attach(
                scope = viewModelScope,
                savedState = savedStateHandle,
                context = ChipFeedContext(::cacheFilters, ::feedExclusions) { watchedVideoIds.value },
            )
            if (HomeFeedCache.isFresh()) {
                _uiState.update {
                    it.copy(
                        videos = HomeFeedCache.videos,
                        shorts = HomeFeedCache.shorts,
                        isLoading = false,
                        isFlowFeed = true,
                        lastRefreshTime = HomeFeedCache.timestamp,
                    )
                }
            } else {
                hydratePersistentHomeFeed()
                loadFlowFeed(forceRefresh = true)
            }
        }

        fun initialize(context: Context) {
            if (isInitialized) return
            isInitialized = true

            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                combine(
                    viewHistory.getVideoHistoryFlow(),
                    playerPreferences.hideWatchedVideosFromHome,
                    playerPreferences.watchedThreshold,
                    playerPreferences.continueWatchingEnabled,
                    playerPreferences.hideWatchedShorts,
                ) { history, hideWatched, threshold, continueWatchingEnabled, hideWatchedShorts ->
                    filterHomeHistory(
                        history = history,
                        hideWatchedVideos = hideWatched,
                        watchedThreshold = threshold,
                        continueWatchingEnabled = continueWatchingEnabled,
                        hideWatchedShorts = hideWatchedShorts,
                    )
                }.collect { result ->
                    watchedVideoIds.value = result.watchedVideoIds
                    watchedShortIds.value = result.watchedShortIds
                    _uiState.update { state ->
                        val videos = state.videos.filterWatched(result.watchedVideoIds)
                        val shorts = state.shorts.filterWatched(result.watchedShortIds)
                        if (videos != state.videos || shorts != state.shorts) {
                            HomeFeedCache.update(videos, shorts)
                        }
                        state.copy(
                            videos = videos,
                            shorts = shorts,
                            continueWatchingVideos = result.continueWatchingVideos,
                        )
                    }
                }
            }

            viewModelScope.launch {
                FeedInvalidationBus.events.collect { event ->
                    when (event) {
                        is FeedInvalidationBus.Event.ChannelBlocked -> {
                            dropChannelFromFeed(event.channelId, event.videoId)
                        }

                        is FeedInvalidationBus.Event.ChannelUnsubscribed -> {
                            dropChannelFromFeed(event.channelId)
                        }

                        is FeedInvalidationBus.Event.NotInterested -> {
                            HomeFeedCache.filterOut(videoId = event.videoId)
                            subsBacklog = subsBacklog.filter { it.id != event.videoId && it.channelId != event.channelId }
                            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                                persistentHomeFeedCache.deleteVideo(event.videoId)
                            }
                            _uiState.update { state ->
                                state.copy(
                                    videos = state.videos.filter { it.id != event.videoId },
                                    shorts = state.shorts.filter { it.id != event.videoId },
                                )
                            }
                            // Full clear — topic signals changed, discovery queries will differ
                            shortsRepository.clearCaches()
                        }

                        is FeedInvalidationBus.Event.AccountChanged -> {
                            reloadFeed()
                        }

                        is FeedInvalidationBus.Event.MarkedWatched -> {
                            HomeFeedCache.filterOut(videoId = event.videoId)
                            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                                persistentHomeFeedCache.deleteVideo(event.videoId)
                            }
                            _uiState.update { state ->
                                state.copy(
                                    videos = state.videos.filter { it.id != event.videoId },
                                    shorts = state.shorts.filter { it.id != event.videoId },
                                )
                            }
                        }
                    }
                }
            }

            viewModelScope.launch {
                playerPreferences.homeSubscriptionsEnabled
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { reloadFeed() }
            }

            viewModelScope.launch {
                playerPreferences.effectiveHomeShortsShelfEnabled.distinctUntilChanged().collect { enabled ->
                    if (!enabled) {
                        _uiState.update { it.copy(shorts = emptyList()) }
                    } else if (_uiState.value.shorts.isEmpty() && !_uiState.value.isLoading) {
                        reloadFeed()
                    }
                }
            }
        }

        fun onHomeVisible() {
            val state = _uiState.value
            startHomePrefetch(
                homePrefetchQueue.onVisible(
                    currentItemCount = state.videos.size,
                    feedReady = state.isReadyForPrefetch(),
                ),
            )
        }

        fun onHomeHidden() {
            homePrefetchQueue.onHidden()
            synchronized(homePrefetchWorkerLock) {
                homePrefetchJob?.cancel()
            }

            wave2Job?.cancel()
            savedInterestJob?.cancel()
            _uiState.update { it.copy(isLoadingMore = false) }
        }

        fun onHomeViewportChanged(lastVisibleVideoIndex: Int) {
            this.lastVisibleVideoIndex = lastVisibleVideoIndex
            val state = _uiState.value
            if (!state.isReadyForPrefetch()) return
            startHomePrefetch(
                homePrefetchQueue.onViewportChanged(
                    currentItemCount = state.videos.size,
                    lastVisibleItemIndex = lastVisibleVideoIndex,
                ),
            )
        }

        private fun HomeUiState.isReadyForPrefetch(): Boolean = videos.isNotEmpty() && !isLoading && isFlowFeed && hasMorePages

        private fun startHomePrefetch(request: FeedPrefetchRequest?) {
            request ?: return
            val worker =
                synchronized(homePrefetchWorkerLock) {
                    if (homePrefetchJob?.isCompleted == false) return
                    viewModelScope
                        .launch(
                            context = PerformanceDispatcher.networkIO,
                            start = CoroutineStart.LAZY,
                        ) {
                            drainHomePrefetchQueue(request.generation)
                        }.also { homePrefetchJob = it }
                }
            worker.start()
        }

        private suspend fun drainHomePrefetchQueue(generation: Int) {
            var pagesLoaded = 0
            var attempt = 0
            var held: HeldPage? = null
            var allowRestart = true
            try {
                wave2Job?.takeIf { it.isActive }?.join()
                while (pagesLoaded < HOME_PREFETCH_MAX_PAGES_PER_RUN) {
                    val state = _uiState.value
                    val request = homePrefetchQueue.currentRequest(state.videos.size) ?: break
                    if (request.generation != generation || !state.hasMorePages) break

                    _uiState.update { it.copy(isLoadingMore = true, loadMoreFailed = false) }
                    attempt++
                    val result = loadNextPrefetchPage(generation, held, lastAttempt = attempt >= HOME_PREFETCH_EMPTY_PAGE_RETRIES)
                    held = result.held
                    if (!homePrefetchQueue.isCurrent(generation)) break

                    // A page that appended nothing leaves the feed at the same length, so the
                    // viewport index cannot change and nothing would re-arm this queue. Retry a
                    // few times — each attempt rotates queries and seeds — before giving up.
                    when (val step = nextLoadMoreStep(result.outcome, attempt)) {
                        LoadMoreStep.DONE -> {
                            pagesLoaded++
                        }

                        LoadMoreStep.RETRY -> {
                            if (result.outcome == LoadMoreOutcome.EXHAUSTED) delay(HOME_PREFETCH_EMPTY_PAGE_BACKOFF_MS * attempt)
                        }

                        LoadMoreStep.END_FEED, LoadMoreStep.STOP_FAILED -> {
                            _uiState.update {
                                if (step == LoadMoreStep.END_FEED) it.copy(hasMorePages = false) else it.copy(loadMoreFailed = true)
                            }
                            Log.w(TAG, "Load-more stopped: $step after $attempt attempts at ${state.videos.size} cards")
                            allowRestart = false
                            break
                        }
                    }
                }
                if (pagesLoaded >= HOME_PREFETCH_MAX_PAGES_PER_RUN) {
                    allowRestart = false
                }
            } catch (cancellation: CancellationException) {
                allowRestart = false
                throw cancellation
            } finally {
                val workerJob = currentCoroutineContext()[Job]
                val ownsLoadingState =
                    synchronized(homePrefetchWorkerLock) {
                        if (homePrefetchJob === workerJob) {
                            homePrefetchJob = null
                            true
                        } else {
                            false
                        }
                    }
                if (ownsLoadingState) {
                    _uiState.update { it.copy(isLoadingMore = false) }
                    if (allowRestart) {
                        startHomePrefetch(homePrefetchQueue.currentRequest(_uiState.value.videos.size))
                    }
                }
            }
        }

        /** The viewer's retry after a load-more that failed; the next scroll retries too. */
        fun retryLoadMore() {
            _uiState.update { it.copy(loadMoreFailed = false) }
            startHomePrefetch(homePrefetchQueue.currentRequest(_uiState.value.videos.size))
        }

        private fun resetHomePrefetch() {
            homePrefetchQueue.reset()
            synchronized(homePrefetchWorkerLock) {
                homePrefetchJob?.cancel()
            }
            _uiState.update { it.copy(isLoadingMore = false, loadMoreFailed = false) }
        }

        fun removeContinueWatchingEntry(videoId: String) {
            viewModelScope.launch {
                viewHistory.clearVideoHistory(videoId)
            }
        }

        private fun dropChannelFromFeed(
            channelId: String,
            videoId: String? = null,
        ) {
            HomeFeedCache.filterOut(channelId = channelId, videoId = videoId)
            subsBacklog = subsBacklog.filter { it.channelId != channelId }
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                persistentHomeFeedCache.deleteChannel(channelId)
                videoId?.let { persistentHomeFeedCache.deleteVideo(it) }
            }
            _uiState.update { state ->
                state.copy(
                    videos = state.videos.filter { it.id != videoId && it.channelId != channelId },
                    shorts = state.shorts.filter { it.id != videoId && it.channelId != channelId },
                )
            }
            // Targeted eviction — preserves other channel caches in discovery engine
            shortsRepository.evictChannel(channelId)
        }

        private suspend fun subscriptionScope(): HomeSubscriptionScope =
            HomeSubscriptionScope.of(
                subscriptions = accountSubscriptions.ids.value,
                showOnHome = playerPreferences.homeSubscriptionsEnabled.first(),
            )

        private suspend fun feedExclusions(): FeedExclusions =
            sessionExclusions.snapshot().hidingChannels(subscriptionScope().hidden)

        private suspend fun cacheFilters(): HomeFeedCacheFilters =
            HomeFeedCacheFilters(watchedVideoIds = watchedVideoIds.value, exclusions = feedExclusions())

        private fun hydratePersistentHomeFeed() {
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                // One-time: the retired local engine's cached feed must never pose as
                // the server feed. Wipe both home caches once, on the first run of
                // the server-feed build, before anything can hydrate from them.
                val migrations =
                    appContext.getSharedPreferences(
                        "glidetube_migrations",
                        android.content.Context.MODE_PRIVATE,
                    )
                if (!migrations.getBoolean("server_feed_v1", false)) {
                    runCatching { persistentHomeFeedCache.clearAll() }
                    HomeFeedCache.clear()
                    migrations.edit().putBoolean("server_feed_v1", true).apply()
                }
                val cached =
                    runCatching {
                        persistentHomeFeedCache.loadLastFeed(cacheFilters())
                    }.getOrElse { emptyList() }
                if (cached.isEmpty()) return@launch

                // Shown before any network call: the collaboration avatars fill in after (#1301).
                var shownIds = emptyList<String>()
                _uiState.update { state ->
                    if (state.videos.isNotEmpty()) return@update state
                    val videos = cached.filterWatched(watchedVideoIds.value)
                    shownIds = videos.map { it.id }
                    HomeFeedCache.update(videos, state.shorts)
                    state.copy(
                        videos = videos,
                        isFlowFeed = true,
                        error = null,
                        lastRefreshTime = System.currentTimeMillis(),
                    )
                }
                if (shownIds.isEmpty()) return@launch
                val hydratedCached = repository.enrichLikelyCollabAvatarStacks(cached, limit = 8)
                if (hydratedCached != cached) {
                    val hydratedById = hydratedCached.associateBy { it.id }
                    _uiState.update { state ->
                        if (state.videos.map { it.id } != shownIds) return@update state
                        state.copy(videos = state.videos.map { hydratedById[it.id] ?: it })
                    }
                }
                enrichVisibleChannelMetadata(hydratedCached)?.let {
                    persistentHomeFeedCache.saveLastFeed(it)
                }
            }
        }

        fun loadFlowFeed(forceRefresh: Boolean = false) {
            if (_uiState.value.isLoading && !forceRefresh) return

            wave2Job?.cancel()
            feedJob?.cancel()
            subsTopUpJob?.cancel()
            visibleMetadataJob?.cancel()
            feedGeneration++
            resetHomePrefetch()
            serverContinuation = null
            _uiState.update { it.copy(isLoading = true, error = null) }

            feedJob =
                viewModelScope.launch(PerformanceDispatcher.networkIO) {
                    val loadStartMs = System.currentTimeMillis()
                    ShivLog.i("home load start signedIn=${YouTube.isSignedIn()}")
                    var page =
                        withTimeoutOrNull(SERVER_FEED_TIMEOUT_MS) {
                            YouTube.serverHome().getOrNull()
                        }
                    // The first load of a session — above all the one right after sign-in, while
                    // the client's identity is still warming up — can come back failed or empty
                    // when the same request a moment later succeeds. Retry before believing
                    // either, so a cold start never settles on a blank home.
                    var attempt = 0
                    while (
                        (page == null || (page.videos.isEmpty() && page.shorts.isEmpty())) &&
                        attempt < 2 && YouTube.isSignedIn()
                    ) {
                        attempt++
                        delay(if (attempt == 1) 1500L else 3000L)
                        page =
                            withTimeoutOrNull(SERVER_FEED_TIMEOUT_MS) {
                                YouTube.serverHome().getOrNull()
                            }
                        ShivLog.i("home load retry $attempt page=${if (page == null) "null" else "ok"} videos=${page?.videos?.size ?: -1}",
                        )
                    }
                    ShivLog.i("home load end page=${if (page == null) "null" else "ok"} " +
                            "videos=${page?.videos?.size ?: -1} after ${System.currentTimeMillis() - loadStartMs}ms",
                    )
                    if (page == null || (page.videos.isEmpty() && page.shorts.isEmpty())) {
                        // Signed out, an empty guest load is not a failure — home becomes the
                        // calm sign-in landing instead of an error with a Retry button (the
                        // guest shell often carries no feed at all, and shouting about it
                        // helps nobody). Signed in, never let a failed load masquerade as a
                        // feed: whatever is on screen is a stale cache, and the person must
                        // be told it failed.
                        _uiState.update { state ->
                            state.copy(
                                isLoading = false,
                                isRefreshing = false,
                                error = if (YouTube.isSignedIn()) "Could not load your feed" else null,
                                isGuestEmpty = !YouTube.isSignedIn(),
                            )
                        }
                        return@launch
                    }
                    val feedPage = checkNotNull(page)
                    serverContinuation = feedPage.continuation
                    chipFeeds.onServerChips(feedPage.chips)
                    val videos = feedPage.videos.filterWatched(watchedVideoIds.value)
                    val shorts = feedPage.shorts.filterWatched(watchedShortIds.value)
                    _uiState.update { state ->
                        state.copy(
                            videos = videos,
                            shorts = shorts,
                            isLoading = false,
                            isRefreshing = false,
                            isFlowFeed = true,
                            hasMorePages = feedPage.continuation != null,
                            error = null,
                            isGuestEmpty = false,
                            lastRefreshTime = System.currentTimeMillis(),
                            origins = emptyMap(),
                        )
                    }
                    HomeFeedCache.update(videos, shorts)
                    runCatching { persistentHomeFeedCache.saveLastFeed(videos) }
                }
        }



        /** Wave 2: the discovery queries wave 1 did not have time for, merged in after paint. */




        /**
         * One load-more attempt. Cards [held] from the run's earlier attempts start the page, and a
         * page too small to show is handed back held unless this is the [lastAttempt].
         */
        private suspend fun loadNextPrefetchPage(
            generation: Int,
            held: HeldPage?,
            lastAttempt: Boolean,
        ): PrefetchAttempt {
            val token = serverContinuation ?: return PrefetchAttempt(LoadMoreOutcome.EXHAUSTED)
            val page =
                withTimeoutOrNull(SERVER_FEED_TIMEOUT_MS) {
                    YouTube.serverHome(continuation = token).getOrNull()
                }
                    ?: return PrefetchAttempt(LoadMoreOutcome.FAILED)
            serverContinuation = page.continuation
            if (page.shorts.isNotEmpty()) {
                _uiState.update { state ->
                    val existing = state.shorts.mapTo(HashSet()) { it.id }
                    val fresh =
                        page.shorts
                            .filterWatched(watchedShortIds.value)
                            .filterNot { it.id in existing }
                    if (fresh.isEmpty()) state else state.copy(shorts = state.shorts + fresh)
                }
            }
            // A held page and its successor can carry the same video; the grid keys by id.
            val videos = (held?.videos.orEmpty() + page.videos).distinctBy { it.id }
            return when (val outcome = loadMoreOutcome(videos.size, lastAttempt, false)) {
                LoadMoreOutcome.APPENDED -> {
                    val appended = appendLoadMorePage(videos, generation, held?.origins.orEmpty())
                    appended?.let { persistentHomeFeedCache.saveLastFeed(it) }
                    PrefetchAttempt(
                        if (appended != null) LoadMoreOutcome.APPENDED else LoadMoreOutcome.EXHAUSTED,
                    )
                }

                LoadMoreOutcome.HELD -> {
                    PrefetchAttempt(outcome, HeldPage(videos, held?.origins.orEmpty()))
                }

                else -> {
                    PrefetchAttempt(outcome)
                }
            }
        }


        private suspend fun appendLoadMorePage(
            page: List<Video>,
            generation: Int,
            origins: Map<String, HomeCardOrigin>,
        ): List<Video>? {
            if (page.isEmpty() || !homePrefetchQueue.isCurrent(generation)) return null
            val exclusions = feedExclusions()
            val followed = subscriptionScope().boosted
            var updatedSnapshot: List<Video>? = null
            var appendedPage = emptyList<Video>()
            _uiState.update { state ->
                if (!homePrefetchQueue.isCurrent(generation)) return@update state
                val existingVideoIds = state.videos.mapTo(HashSet()) { it.id }
                appendedPage =
                    page
                        .filterWatched(watchedVideoIds.value)
                        .filterNot { it.id in existingVideoIds || exclusions.hidesFromRecommendations(it) }
                if (appendedPage.isEmpty()) return@update state
                val tailChannels = state.videos.takeLast(CHANNEL_GAP).map { it.channelId }
                val updated =
                    state.videos +
                        spaceByChannel(
                            rhythmBySource(appendedPage, isSubscribed = { it.channelId in followed }),
                            gap = CHANNEL_GAP,
                            seedRecent = tailChannels,
                        )
                updatedSnapshot = updated
                HomeFeedCache.update(updated, state.shorts)
                val appendedIds = appendedPage.mapTo(HashSet()) { it.id }
                state.copy(
                    videos = updated,
                    hasMorePages = true,
                    origins = state.origins + origins.filterKeys { it in appendedIds },
                )
            }
            if (appendedPage.isEmpty()) return null
            Log.d(TAG, "Load-more appended ${appendedPage.size} cards")
            return enrichVisibleChannelMetadata(appendedPage) ?: updatedSnapshot
        }

        private suspend fun enrichVisibleChannelMetadata(videos: List<Video>): List<Video>? {
            val enriched = repository.enrichMissingChannelMetadata(videos)
            if (enriched == videos) return null

            val originalById = videos.associateBy { it.id }
            val updates =
                enriched
                    .filter { enrichedVideo -> originalById[enrichedVideo.id] != enrichedVideo }
                    .associateBy { it.id }
            var updatedSnapshot: List<Video>? = null
            _uiState.update { state ->
                val updated =
                    state.videos.map { current ->
                        updates[current.id]?.let(current::withChannelMetadataFrom) ?: current
                    }
                if (updated == state.videos) return@update state
                updatedSnapshot = updated
                HomeFeedCache.update(updated, state.shorts)
                state.copy(videos = updated)
            }
            return updatedSnapshot
        }

        fun enrichChannelMetadataIfMissing(video: Video) {
            val videoId = video.id
            if (!video.needsChannelMetadata() || !channelMetadataEnrichmentInFlight.add(videoId)) return

            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                try {
                    val enriched =
                        repository
                            .enrichMissingChannelMetadata(listOf(video), limit = 1)
                            .firstOrNull()
                            ?: return@launch
                    if (enriched == video) return@launch

                    _uiState.update { state ->
                        val updated =
                            state.videos.map { current ->
                                if (current.id != videoId) {
                                    current
                                } else {
                                    current.withChannelMetadataFrom(enriched)
                                }
                            }
                        if (updated == state.videos) {
                            state
                        } else {
                            HomeFeedCache.update(updated, state.shorts)
                            state.copy(videos = updated)
                        }
                    }
                } finally {
                    channelMetadataEnrichmentInFlight.remove(videoId)
                }
            }
        }


        /**
         * The viewer's refresh. A load already running is the refresh: the indicator stays until it
         * lands, and repeated pulls cannot stack pipelines (#1171).
         */
        fun refreshFeed() {
            if (feedJob?.isActive == true) {
                _uiState.update { it.copy(isRefreshing = true) }
                return
            }
            reloadFeed()
        }

        /** Starts over, replacing a load already running: a changed setting must apply to the next feed. */
        private fun reloadFeed() {
            resetHomePrefetch()
            wave2Job?.cancel()
            HomeFeedCache.clear()
            _uiState.update { it.copy(isRefreshing = true) }
            loadFlowFeed(forceRefresh = true)
        }




        fun retry() {
            resetHomePrefetch()
            wave2Job?.cancel()
            loadFlowFeed(forceRefresh = true)
        }

        private fun addUnique(
            video: Video?,
            targetList: MutableList<Video>,
            channelCounts: MutableMap<String, Int>,
            usedVideoIds: MutableSet<String>,
            maxPerChannel: Int = 2,
        ): Boolean = addUniqueVideo(video, targetList, channelCounts, usedVideoIds, maxPerChannel)



        fun recordShelfImpressions(visibleIds: List<String>) = Unit

        fun recordImpressions(visibleKeys: List<String>) = Unit
    }
