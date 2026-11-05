package io.github.aedev.flow.ui.screens.subscriptions

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.engagement.FeedInvalidationBus
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.engagement.FeedExclusions
import io.github.aedev.flow.data.subscriptions.SubscriptionWatchedVideos
import io.github.aedev.flow.data.subscriptions.withHighQualityThumbnails
import io.github.aedev.flow.utils.PerformanceDispatcher
import io.github.aedev.flow.utils.ShivLog
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import io.github.aedev.flow.utils.formatYouTubeRelativeTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.aedev.flow.innertube.YouTube
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel as EventChannel

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SubscriptionsViewModel
    @Inject
    constructor(
        private val playerPreferences: PlayerPreferences,
        private val subscriptionWatchedVideos: SubscriptionWatchedVideos,
        private val accountSubscriptions: io.github.aedev.flow.data.account.AccountSubscriptions,
    ) : ViewModel() {
        companion object {
            private const val TAG = "SubsViewModel"

            private const val DURATION_ENRICHMENT_BATCH_SIZE = 3
            private const val DURATION_METADATA_TIMEOUT_MS = 4_000L
            private const val DURATION_RETRY_AFTER_MS = 30 * 60 * 1_000L
            private const val RELATIVE_TIME_TICK_MS = 60L * 1000L
        }

        private val _uiState = MutableStateFlow(SubscriptionsUiState())
        val uiState: StateFlow<SubscriptionsUiState> = _uiState.asStateFlow()

        private var latestFeedVideos: List<Video> = emptyList()
        private var watchedVideoIds: Set<String> = emptySet()
        private var watchedShortIds: Set<String> = emptySet()
        private var unplayableVideoIds: Set<String> = emptySet()
        private var excludedShortsChannelIds: Set<String> = emptySet()
        private val durationEnrichmentAttemptedAt = mutableMapOf<String, Long>()
        private var durationEnrichmentJob: Job? = null
        private var visibleVideoIds: Set<String> = emptySet()
        private var hasPendingVisibleEnrichment = false
        private var hasStarted = false

        /**
         * Starts the preference/feed collectors. Deliberately not run from `init`: the TV shell
         * hoists this ViewModel at launch (see `FlowTvApp`), so constructing it would kick off the
         * subscription RSS fetch before the user ever opens Subscriptions. Called from the screens
         * instead, so the work still begins exactly when the feed becomes visible.
         *
         * Idempotent, and only ever called from composition (main thread).
         */
        fun ensureStarted() {
            if (hasStarted) return
            hasStarted = true

            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                playerPreferences.effectiveShortsShelfEnabled.collect { enabled ->
                    _uiState.update { it.copy(isShortsShelfEnabled = enabled) }
                }
            }

            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                combine(
                    playerPreferences.subscriptionShowVideos,
                    playerPreferences.effectiveSubscriptionShowShorts,
                    playerPreferences.subscriptionShowLive,
                ) { showVideos, showShorts, showLive ->
                    Triple(showVideos, showShorts, showLive)
                }.distinctUntilChanged()
                    .collect { (showVideos, showShorts, showLive) ->
                        _uiState.update {
                            it.copy(
                                showSubscriptionVideos = showVideos,
                                showSubscriptionShorts = showShorts,
                                showSubscriptionLive = showLive,
                            )
                        }
                        refreshVisibleFeed()
                    }
            }

            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                playerPreferences.subscriptionCollaborationsEnabled
                    .distinctUntilChanged()
                    .collect { showCollaborations ->
                        _uiState.update { it.copy(showCollaborations = showCollaborations) }
                        refreshVisibleFeed()
                    }
            }

            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                playerPreferences.subscriptionShowCheckedVideoCount
                    .distinctUntilChanged()
                    .collect { showCount ->
                        _uiState.update { it.copy(showLastRefreshVideoCount = showCount) }
                    }
            }

            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                playerPreferences.subscriptionShortsExcludedChannels
                    .distinctUntilChanged()
                    .collect { ids ->
                        excludedShortsChannelIds = ids
                        _uiState.update { it.copy(excludedShortsChannelIds = ids) }
                        refreshVisibleFeed()
                    }
            }

            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                playerPreferences.subsFullWidthView.collect { fullWidth ->
                    _uiState.update { it.copy(isFullWidthView = fullWidth) }
                }
            }

            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                playerPreferences.subsSortMode.collect { stored ->
                    val mode = SubscriptionSortMode.fromStorage(stored)
                    _uiState.update { it.copy(sortMode = mode) }
                }
            }

            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                combine(subscriptionWatchedVideos.ids, subscriptionWatchedVideos.shortIds, ::Pair).collect { (videoIds, shortIds) ->
                    watchedVideoIds = videoIds
                    watchedShortIds = shortIds
                    refreshVisibleFeed()
                }
            }

            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                combine(
                    playerPreferences.unplayableVideoIds,
                    playerPreferences.hideUnplayableVideosFromSubscriptions,
                ) { ids, hideUnplayable ->
                    if (hideUnplayable) ids else emptySet()
                }.distinctUntilChanged()
                    .collect { ids ->
                        unplayableVideoIds = ids
                        refreshVisibleFeed()
                    }
            }

            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                FeedInvalidationBus.events.collect { event ->
                    if (event is FeedInvalidationBus.Event.AccountChanged) {
                        refreshFeed()
                    } else if (event is FeedInvalidationBus.Event.ChannelBlocked || event is FeedInvalidationBus.Event.NotInterested) {
                        refreshVisibleFeed()
                    }
                }
            }

            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                _uiState.subscriptionCount
                    .map { observers -> observers > 0 }
                    .distinctUntilChanged()
                    .collectLatest { observed ->
                        if (!observed) return@collectLatest
                        while (true) {
                            delay(RELATIVE_TIME_TICK_MS)
                            refreshVisibleFeed()
                        }
                    }
            }

            // The account list is the one source: its arrival (and every later change, e.g. an
            // unsubscribe from this screen) drives the server refresh. Signed out it never
            // loads, and the screen shows its sign-in empty state instead.
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                accountSubscriptions.channels.collect {
                        if (signedIn()) {
                            runRefresh(force = false, showLoading = _uiState.value.recentVideos.isEmpty())
                        }
                    }
            }
        }

        /**
         * Fetches only the channels that are actually due, unless [force] (an explicit pull-to-refresh)
         * asks for every channel. With a group selected, only that group's channels (#1099).
         */
        private suspend fun runRefresh(
            force: Boolean,
            showLoading: Boolean,
        ) {
            // The whole screen runs off the account, exactly like YouTube: no local
            // subscription store, no per-channel RSS assembly. Signed out there is
            // nothing to refresh — the screen shows its sign-in empty state.
            if (signedIn()) {
                runServerRefresh(showLoading)
            } else if (showLoading) {
                _uiState.update { it.copy(isLoading = false) }
            }
        }

        private fun signedIn(): Boolean = YouTube.isSignedIn()

        /** The account's own subscriptions feed (FEsubscriptions) plus its channel rail. */
        private suspend fun runServerRefresh(showLoading: Boolean) {
            if (showLoading) {
                _uiState.update { it.copy(isLoading = true) }
            }
            try {
                val videos = mutableListOf<Video>()
                var channels: List<Channel> = emptyList()
                var continuation: String? = null
                var pages = 0
                do {
                    val page = YouTube.serverSubscriptions(continuation).getOrNull() ?: break
                    videos += page.videos + page.shorts
                    if (page.channels.isNotEmpty()) channels = page.channels
                    continuation = page.continuation
                    pages++
                } while (continuation != null && pages < 3)
                // Continuation pages repeat videos the first page already carried; the feed's
                // lazy grid keys rows by video id, so a repeat is a duplicate-key crash.
                val uniqueVideos = videos.distinctBy { it.id }
                videos.clear()
                videos += uniqueVideos
                if (channels.isEmpty() && videos.isNotEmpty()) {
                    // The page's own channel rail does not always parse; the feed's videos
                    // carry their channels, so the rail can always be built from them.
                    channels =
                        videos
                            .filter { it.channelId.isNotBlank() }
                            .groupBy { it.channelId }
                            .map { (channelId, channelVideos) ->
                                Channel(
                                    id = channelId,
                                    name = channelVideos.firstOrNull { it.channelName.isNotBlank() }?.channelName.orEmpty(),
                                    thumbnailUrl =
                                        channelVideos.firstNotNullOfOrNull { video ->
                                            video.channelThumbnailUrl.takeIf(String::isNotBlank)
                                                ?: video.channelThumbnailUrls.firstOrNull(String::isNotBlank)
                                        }.orEmpty(),
                                    subscriberCount = 0L,
                                )
                            }
                }
                // The guide's list is the complete membership (names + photos for every
                // channel, even ones with no recent uploads); feed-derived channels the
                // guide somehow missed are appended, never the other way around.
                accountSubscriptions.awaitLoaded()
                val guideChannels = accountSubscriptions.channels.value
                if (guideChannels.isNotEmpty()) {
                    val known = guideChannels.map { it.id }.toSet()
                    channels = guideChannels + channels.filter { it.id !in known }
                }
                if (channels.isNotEmpty()) {
                    _uiState.update { it.copy(subscribedChannels = channels) }
                    enrichMissingAvatars(channels)
                }
                latestFeedVideos = videos
                updateVideos(videos, channels.ifEmpty { _uiState.value.subscribedChannels })
                val refreshedAt = System.currentTimeMillis()
                _uiState.update {
                    it.copy(
                        lastRefreshTime = refreshedAt,
                        lastRefreshText = formatYouTubeRelativeTime(refreshedAt),
                        lastRefreshVideoCount = videos.size,
                        failedChannelIds = emptySet(),
                        failedChannelReasons = emptyMap(),
                    )
                }
            } finally {
                _uiState.update {
                    it.copy(isLoading = false, refreshProcessedChannels = 0, refreshTotalChannels = 0)
                }
            }
        }

        /**
         * Fills in avatars the feed pages did not carry, from each channel's own page, a few at
         * a time. The rail appears instantly with placeholders; photos land as they resolve.
         */
        private fun enrichMissingAvatars(channels: List<Channel>) {
            val missing = channels.filter { it.thumbnailUrl.isBlank() }
            if (missing.isEmpty()) return
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                missing.chunked(6).forEach { batch ->
                    val filled =
                        batch
                            .map { channel ->
                                async {
                                    val avatar =
                                        runCatching {
                                            YouTube.channel(channel.id).getOrNull()?.header?.avatarUrl
                                        }.getOrNull()
                                    channel.id to avatar
                                }
                            }.awaitAll()
                            .filter { !it.second.isNullOrBlank() }
                            .toMap()
                    if (filled.isEmpty()) return@forEach
                    _uiState.update { state ->
                        state.copy(
                            subscribedChannels =
                                state.subscribedChannels.map { channel ->
                                    filled[channel.id]?.let { channel.copy(thumbnailUrl = it) } ?: channel
                                },
                        )
                    }
                    refreshVisibleFeed()
                }
            }
        }

        private suspend fun refreshVisibleFeed() {
            if (latestFeedVideos.isNotEmpty()) {
                updateVideos(latestFeedVideos)
            }
        }

        private suspend fun updateVideos(
            videos: List<Video>,
            channels: List<Channel> = _uiState.value.subscribedChannels,
        ) {
            val state = _uiState.value
            val sections =
                subscriptionFeedSections(
                    videos = videos.withHighQualityThumbnails().withSubscriptionAvatars(channels),
                    filters =
                        SubscriptionFeedFilters(
                            showVideos = state.showSubscriptionVideos,
                            showShorts = state.showSubscriptionShorts,
                            showLive = state.showSubscriptionLive,
                            showCollaborations = state.showCollaborations,
                            watchedVideoIds = watchedVideoIds,
                            watchedShortIds = watchedShortIds,
                            unplayableVideoIds = unplayableVideoIds,
                            allowedChannelIds = null,
                            excludedShortsChannelIds = excludedShortsChannelIds,
                            exclusions = FeedExclusions.NONE,
                        ),
                    now = System.currentTimeMillis(),
                )
            // The feed-to-screen handoff: the device report proves the feed parses
            // but cannot see this layer, so the counts land in Diagnostics too.
            ShivLog.i(
                "subsUi in=${videos.size} recent=${sections.recentVideos.size} shorts=${sections.shorts.size} " +
                    "showV=${state.showSubscriptionVideos} showS=${state.showSubscriptionShorts} " +
                    "watched=${watchedVideoIds.size} unplayable=${unplayableVideoIds.size}",
            )
            _uiState.update { it.copy(recentVideos = sections.recentVideos, shorts = sections.shorts) }
        }

        fun updateVisibleVideoIds(videoIds: Set<String>) {
            visibleVideoIds = videoIds
            if (videoIds.isEmpty()) {
                hasPendingVisibleEnrichment = false
                durationEnrichmentJob?.cancel()
                durationEnrichmentJob = null
                return
            }
            scheduleVisibleDurationEnrichment()
        }

        private fun scheduleVisibleDurationEnrichment() {
            if (durationEnrichmentJob?.isActive == true) {
                hasPendingVisibleEnrichment = true
                return
            }

            val nowMillis = System.currentTimeMillis()
            val visibleWindow =
                visibleSubscriptionEnrichmentWindow(
                    videos = _uiState.value.recentVideos,
                    visibleVideoIds = visibleVideoIds,
                )
            val candidates =
                missingDurationCandidates(
                    videos = visibleWindow,
                    attemptedAtMillis = durationEnrichmentAttemptedAt,
                    nowMillis = nowMillis,
                    retryAfterMillis = DURATION_RETRY_AFTER_MS,
                )
            if (candidates.isEmpty()) return

            hasPendingVisibleEnrichment = false
            durationEnrichmentJob =
                viewModelScope.launch(PerformanceDispatcher.networkIO) {
                    val runningJob = coroutineContext[Job]
                    try {
                        val enrichedById = mutableMapOf<String, Video>()
                        candidates.chunked(DURATION_ENRICHMENT_BATCH_SIZE).forEach { batch ->
                            val enrichedBatch =
                                supervisorScope {
                                    batch
                                        .map { video ->
                                            async(PerformanceDispatcher.networkIO) {
                                                fetchSubscriptionPlayerMetadata(video, DURATION_METADATA_TIMEOUT_MS)
                                            }
                                        }.awaitAll()
                                }
                            withContext(Dispatchers.Main.immediate) {
                                val attemptedAt = System.currentTimeMillis()
                                batch.forEach { video ->
                                    durationEnrichmentAttemptedAt[video.id] = attemptedAt
                                }
                            }
                            enrichedBatch.filterNotNull().associateByTo(enrichedById) { it.id }
                        }

                        if (enrichedById.isEmpty()) return@launch

                        val mergedVideos =
                            latestFeedVideos
                                .map { video -> enrichedById[video.id]?.let(video::withPlayerMetadata) ?: video }
                                .withHighQualityThumbnails()
                                .withSubscriptionAvatars(_uiState.value.subscribedChannels)

                        latestFeedVideos = mergedVideos
                        updateVideos(mergedVideos)
                        Log.d(TAG, "Duration enrichment applied to ${enrichedById.size} subscription videos")
                    } finally {
                        withContext(NonCancellable + Dispatchers.Main.immediate) {
                            if (durationEnrichmentJob === runningJob) {
                                durationEnrichmentJob = null
                                if (hasPendingVisibleEnrichment && visibleVideoIds.isNotEmpty()) {
                                    scheduleVisibleDurationEnrichment()
                                }
                            }
                        }
                    }
                }
        }

        fun selectChannel(channelId: String?) {
            _uiState.update { it.copy(selectedChannelId = channelId) }
        }

        /** Explicit user refresh: every channel shown, regardless of how recently it was fetched. */
        fun refreshFeed() {
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                runRefresh(force = true, showLoading = true)
            }
        }

        /** Background top-up: only the channels that have aged out or have a pending upload signal. */
        fun refreshIfStaleOrMissedUploads() {
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                runRefresh(force = false, showLoading = false)
            }
        }

        /** Re-runs only the channels the last refresh could not reach. */
        fun retryFailedChannels() {
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                val failed = _uiState.value.failedChannelIds
                if (failed.isEmpty()) return@launch
                _uiState.update { it.copy(failedChannelIds = emptySet(), failedChannelReasons = emptyMap()) }
                runRefresh(force = true, showLoading = true)
            }
        }

        fun dismissFailedChannels() {
            _uiState.update { it.copy(failedChannelIds = emptySet(), failedChannelReasons = emptyMap()) }
        }

        fun unsubscribe(channelId: String) {
            if (signedIn()) {
                // Signed in, the account list is the only store this touches — writing the
                // local database here too is how the two used to contradict each other.
                accountSubscriptions.setSubscribed(
                    Channel(id = channelId, name = "", thumbnailUrl = "", subscriberCount = 0L),
                    false,
                )
                viewModelScope.launch(PerformanceDispatcher.networkIO) {
                    runServerRefresh(showLoading = false)
                }
                return
            }
        }

        fun updateNotificationState(
            channelId: String,
            enabled: Boolean,
        ) {
            // Per-channel notification preference lived in the local store; subscriptions are
            // account-only now and carry no local notification level.
        }

        fun setShortsChannelExcluded(
            channelId: String,
            excluded: Boolean,
        ) {
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                playerPreferences.setSubscriptionShortsChannelExcluded(channelId, excluded)
            }
        }

        fun toggleViewMode() {
            val newValue = !_uiState.value.isFullWidthView
            _uiState.update { it.copy(isFullWidthView = newValue) }
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                playerPreferences.setSubsFullWidthView(newValue)
            }
        }

        fun setSortMode(mode: SubscriptionSortMode) {
            _uiState.update { it.copy(sortMode = mode) }
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                playerPreferences.setSubsSortMode(mode.name)
            }
        }

        /**
         * Re-subscribes [channel] (used for undo). Signed-in channels live in the account,
         * so the undo writes there and refreshes the feed from the server.
         */
        fun subscribeChannel(channel: Channel) {
            if (!signedIn()) return
            accountSubscriptions.setSubscribed(channel, true)
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                runServerRefresh(showLoading = false)
            }
        }
    }
