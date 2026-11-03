package io.github.aedev.flow.ui.screens.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.local.VideoHistoryEntry
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.data.local.AccountHistoryCapture
import io.github.aedev.flow.data.local.SessionHistory
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.data.shorts.ShortsContentFilter
import io.github.aedev.flow.data.shorts.queue.ShortsQueueHandoff
import io.github.aedev.flow.data.shorts.queue.ShortsQueueSource
import io.github.aedev.flow.utils.PerformanceDispatcher
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val ENRICHMENT_BATCH_LIMIT = 30
private const val ENRICHMENT_CHUNK_SIZE = 5
private const val ENRICHMENT_CHUNK_DELAY_MS = 300L

enum class HistoryContentFilter {
    All,
    Videos,
    Shorts,
    Music,
    LocalVideos,
    LocalMusic,
    ;

    fun matches(entry: VideoHistoryEntry): Boolean =
        when (this) {
            All -> !entry.isLocal
            Videos -> !entry.isMusic && !entry.isShort && !entry.isLocal
            Shorts -> !entry.isMusic && entry.isShort && !entry.isLocal
            Music -> entry.isMusic && !entry.isLocal
            LocalVideos -> entry.isLocal && !entry.isMusic
            LocalMusic -> entry.isLocal && entry.isMusic
        }
}

enum class HistorySort {
    Newest,
    Oldest,
}

@HiltViewModel
class HistoryViewModel
    @Inject
    constructor(
        private val viewHistory: SessionHistory,
        private val accountHistoryCapture: AccountHistoryCapture,
        private val youTubeRepository: YouTubeRepository,
        private val shortsContentFilter: ShortsContentFilter,
        private val shortsQueueHandoff: ShortsQueueHandoff,
    ) : ViewModel() {
        private val isEnriching = AtomicBoolean(false)
        private val attemptedEnrichment = HashSet<String>()
        private val sharing = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L)

        private val _uiState = MutableStateFlow(HistoryUiState())
        val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

        // Signed-in server list, kept apart so session plays can head it live.
        private val _serverEntries = MutableStateFlow<List<VideoHistoryEntry>>(emptyList())
        private val _serverShorts = MutableStateFlow<Map<String, io.github.aedev.flow.data.model.Video>>(emptyMap())

        private val _searchQuery = MutableStateFlow("")
        val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

        private val _contentFilter = MutableStateFlow(HistoryContentFilter.All)
        val contentFilter: StateFlow<HistoryContentFilter> = _contentFilter.asStateFlow()

        private val _sortOrder = MutableStateFlow(HistorySort.Newest)
        val sortOrder: StateFlow<HistorySort> = _sortOrder.asStateFlow()

        private val _yearFilter = MutableStateFlow<Int?>(null)
        val yearFilter: StateFlow<Int?> = _yearFilter.asStateFlow()

        val availableYears: StateFlow<List<Int>> =
            _uiState
                .map { state ->
                    state.historyEntries
                        .map { historyYear(it.timestamp) }
                        .distinct()
                        .sortedDescending()
                }.distinctUntilChanged()
                .flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, sharing, emptyList())

        val displayEntries: StateFlow<List<VideoHistoryEntry>> =
            combine(
                _uiState.map { it.historyEntries }.distinctUntilChanged(),
                _searchQuery,
                _contentFilter,
                _sortOrder,
                _yearFilter,
            ) { entries, query, filter, sort, year ->
                val trimmed = query.trim()
                entries
                    .asSequence()
                    .filter { filter.matches(it) }
                    .filter { year == null || historyYear(it.timestamp) == year }
                    .filter {
                        trimmed.isBlank() ||
                            it.title.contains(trimmed, ignoreCase = true) ||
                            it.channelName.contains(trimmed, ignoreCase = true)
                    }.let { sequence ->
                        if (sort == HistorySort.Newest) {
                            sequence.sortedByDescending { it.timestamp }
                        } else {
                            sequence.sortedBy { it.timestamp }
                        }
                    }.toList()
            }.flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, sharing, emptyList())

        init {
            viewModelScope.launch {
                shortsContentFilter.enabled.collect { enabled ->
                    _uiState.update { it.copy(shortsEnabled = enabled) }
                    if (!enabled && _contentFilter.value == HistoryContentFilter.Shorts) {
                        _contentFilter.value = HistoryContentFilter.All
                    }
                }
            }

            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true) }
                // Signed in, the server list (FEhistory) is the base, but session
                // plays head it: the server never learns of a watch made in this
                // app, so without the merge the newest plays never show.
                if (YouTube.isSignedIn()) {
                    viewModelScope.launch { loadServerHistory() }
                    combine(
                        _serverEntries,
                        _serverShorts,
                        viewHistory.getAllHistory(),
                        shortsContentFilter.enabled,
                    ) { server, serverShorts, local, shortsEnabled ->
                        val localSorted = local.sortedByDescending { it.timestamp }
                        val localIds = localSorted.map { it.videoId }.toSet()
                        val merged = localSorted + server.filterNot { it.videoId in localIds }
                        // Server entries carry no watch time, so the merged order is
                        // kept with tightly descending stamps, as the server list was.
                        val now = System.currentTimeMillis()
                        val stamped = merged.mapIndexed { index, entry -> entry.copy(timestamp = now - index * 1_000L) }
                        val filtered = if (shortsEnabled) stamped else stamped.filterNot { it.isShort }
                        filtered to (serverShorts + loadShortVideos(filtered.filter { it.isShort }))
                    }.collect { (entries, shorts) ->
                        _uiState.update { it.copy(historyEntries = entries, shortVideos = shorts) }
                    }
                    return@launch
                }
                combine(
                    viewHistory.getAllHistory(),
                    accountHistoryCapture.entriesFlow,
                    shortsContentFilter.enabled,
                ) { history, captured, shortsEnabled ->
                    // Signed out, the list is the session's own plays plus the last
                    // account snapshot, joined in watch-time order with no repeats:
                    // a session row always wins its id, the snapshot fills the rest.
                    val localIds = history.map { it.videoId }.toSet()
                    val merged =
                        (history + captured.filterNot { it.videoId in localIds })
                            .sortedByDescending { it.timestamp }
                    if (shortsEnabled) merged else merged.filterNot { it.isShort }
                }.collect { history ->
                        val enriched = history
                        val shortVideos = loadShortVideos(enriched)

                        _uiState.update {
                            it.copy(
                                historyEntries = enriched,
                                shortVideos = shortVideos,
                                isLoading = false,
                            )
                        }

                        val stubs =
                            enriched
                                .asSequence()
                                .filter { entry ->
                                    !entry.isLocal &&
                                        entry.videoId !in attemptedEnrichment &&
                                        (
                                            entry.title.isEmpty() ||
                                                entry.channelName.isEmpty() ||
                                                (entry.isShort && !shortVideos.containsKey(entry.videoId))
                                        )
                                }.distinctBy { it.videoId }
                                .take(ENRICHMENT_BATCH_LIMIT)
                                .toList()
                        if (stubs.isNotEmpty()) {
                            enrichFromApi(stubs)
                        }
                    }
            }
        }

        /** The account's watch history, newest first, as flat entries the screen already renders. */
        private suspend fun loadServerHistory() {
            val videos = mutableListOf<io.github.aedev.flow.data.model.Video>()
            val shortVideos = mutableMapOf<String, io.github.aedev.flow.data.model.Video>()
            var continuation: String? = null
            var pages = 0
            do {
                val page = YouTube.serverHistory(continuation).getOrNull() ?: break
                videos += page.videos
                page.shorts.forEach { short ->
                    shortVideos[short.id] = short
                    videos += short
                }
                continuation = page.continuation
                pages++
            } while (continuation != null && pages < 3)
            // Continuation pages repeat entries; the history list keys rows by video id.
            val uniqueVideos = videos.distinctBy { it.id }
            videos.clear()
            videos += uniqueVideos
            val now = System.currentTimeMillis()
            val entries =
                videos.mapIndexed { index, video ->
                    VideoHistoryEntry(
                        videoId = video.id,
                        position = 0,
                        duration = video.duration.toLong(),
                        // The server orders newest-first but gives no per-item watch time;
                        // keep that order with tightly descending stamps.
                        timestamp = now - index * 1_000L,
                        title = video.title,
                        thumbnailUrl = video.thumbnailUrl,
                        channelName = video.channelName,
                        channelId = video.channelId,
                        isShort = video.isShort,
                    )
                }
            _serverEntries.value = entries
            _serverShorts.value = shortVideos
            // Snapshot the account list now: without this a logout drops every
            // server row, and the signed-out screen would show session plays only.
            accountHistoryCapture.save(entries)
            _uiState.update { it.copy(isLoading = false) }
        }

        fun setSearchQuery(query: String) {
            _searchQuery.value = query
        }

        fun setContentFilter(filter: HistoryContentFilter) {
            _contentFilter.value = filter
        }

        fun setSortOrder(sort: HistorySort) {
            _sortOrder.value = sort
        }

        fun setYearFilter(year: Int?) {
            _yearFilter.value = year
        }

        fun shortsRowSource(
            row: List<Video>,
            tapped: Video,
        ): ShortsQueueSource = shortsQueueHandoff.sourceForShelf(row, tapped)

        private suspend fun loadShortVideos(entries: List<VideoHistoryEntry>): Map<String, Video> =
            entries
                .filter { it.isShort }
                .map { entry ->
                    entry.videoId to
                        Video(
                            id = entry.videoId,
                            title = entry.title,
                            channelName = entry.channelName,
                            channelId = entry.channelId,
                            thumbnailUrl = entry.thumbnailUrl,
                            duration = (entry.duration / 1000).toInt(),
                            // History entries never store a view count: -1 is the
                            // codebase's unknown sentinel (see toVideo), not 0.
                            viewCount = -1L,
                            uploadDate = "",
                            timestamp = entry.timestamp,
                            isMusic = entry.isMusic,
                            isShort = true,
                        )
                }.toMap()

        private fun enrichFromApi(stubs: List<VideoHistoryEntry>) {
            if (!isEnriching.compareAndSet(false, true)) return
            attemptedEnrichment.addAll(stubs.map { it.videoId })
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                try {
                    stubs.chunked(ENRICHMENT_CHUNK_SIZE).forEach { chunk ->
                        chunk.forEach { stub ->
                            try {
                                val video = youTubeRepository.getVideo(stub.videoId) ?: return@forEach
                                viewHistory.bulkSaveHistoryEntries(
                                    listOf(
                                        stub.copy(
                                            title = video.title,
                                            thumbnailUrl =
                                                ThumbnailUrlResolver.normalizeVideoThumbnail(
                                                    stub.videoId,
                                                    video.thumbnailUrl,
                                                ),
                                            channelName = video.channelName,
                                            channelId = video.channelId,
                                            duration = video.duration * 1000L,
                                            isShort = stub.isShort || video.isShort,
                                        ),
                                    ),
                                )
                            } catch (_: Exception) {
                            }
                        }
                        delay(ENRICHMENT_CHUNK_DELAY_MS)
                    }
                } finally {
                    isEnriching.set(false)
                }
            }
        }

        fun clearHistory() {
            viewModelScope.launch {
                viewHistory.clearAllHistory()
            }
            // Signed in, clearing is this session's view of the server list too,
            // or the merge collector would put its entries straight back.
            _serverEntries.value = emptyList()
            _serverShorts.value = emptyMap()
            _uiState.update { it.copy(historyEntries = emptyList(), shortVideos = emptyMap()) }
        }

        fun clearShortsHistory() {
            viewModelScope.launch {
                viewHistory.clearShortsHistory()
            }
            _serverEntries.update { entries -> entries.filterNot { it.isShort } }
            _serverShorts.value = emptyMap()
            _uiState.update { state ->
                state.copy(
                    historyEntries = state.historyEntries.filterNot { it.isShort },
                    shortVideos = emptyMap(),
                )
            }
        }

        fun removeFromHistory(videoId: String) {
            viewModelScope.launch {
                viewHistory.clearVideoHistory(videoId)
            }
            _serverEntries.update { entries -> entries.filterNot { it.videoId == videoId } }
            _serverShorts.update { shorts -> shorts - videoId }
            _uiState.update { state ->
                state.copy(
                    historyEntries = state.historyEntries.filterNot { it.videoId == videoId },
                    shortVideos = state.shortVideos - videoId,
                )
            }
        }

        fun restoreHistoryEntry(entry: VideoHistoryEntry) {
            viewModelScope.launch {
                viewHistory.bulkSaveHistoryEntries(listOf(entry))
            }
            _uiState.update { state ->
                if (state.historyEntries.any { it.videoId == entry.videoId }) {
                    state
                } else {
                    state.copy(historyEntries = (listOf(entry) + state.historyEntries).sortedByDescending { it.timestamp })
                }
            }
        }
    }

internal fun historyYear(timestamp: Long): Int = Calendar.getInstance().apply { timeInMillis = timestamp }.get(Calendar.YEAR)

data class HistoryUiState(
    val historyEntries: List<VideoHistoryEntry> = emptyList(),
    val shortVideos: Map<String, Video> = emptyMap(),
    val isLoading: Boolean = false,
    val shortsEnabled: Boolean = true,
)
