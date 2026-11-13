package io.github.aedev.flow.ui.screens.history

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.PlayerPreferences
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
import io.github.aedev.flow.utils.ShivLog
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val ENRICHMENT_BATCH_LIMIT = 30
private const val ENRICHMENT_CHUNK_SIZE = 5
private const val ENRICHMENT_CHUNK_DELAY_MS = 300L

// Clear All loads at most 3 history pages a pass and the server keeps a
// continuation past the third, so one pass can never empty a long history.
private const val MAX_CLEAR_PASSES = 10

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
        @ApplicationContext private val context: Context,
        private val viewHistory: SessionHistory,
        private val accountHistoryCapture: AccountHistoryCapture,
        private val youTubeRepository: YouTubeRepository,
        private val shortsContentFilter: ShortsContentFilter,
        private val shortsQueueHandoff: ShortsQueueHandoff,
    ) : ViewModel() {
        private val playerPreferences = PlayerPreferences(context)

        private val isEnriching = AtomicBoolean(false)
        private val attemptedEnrichment = HashSet<String>()
        private val sharing = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L)

        private val _uiState = MutableStateFlow(HistoryUiState())
        val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

        private val _messages = Channel<HistoryUiMessage>(Channel.BUFFERED)
        val messages: Flow<HistoryUiMessage> = _messages.receiveAsFlow()

        // Signed-in server list, kept apart so session plays can head it live.
        private val _serverEntries = MutableStateFlow<List<VideoHistoryEntry>>(emptyList())
        private val _serverShorts = MutableStateFlow<Map<String, io.github.aedev.flow.data.model.Video>>(emptyMap())

        /** The Clear-all token the last FEhistory page carried, if the server sent one. */
        private var clearHistoryToken: String? = null

        // Report 29 killed the fallback twice the moment the user left the
        // History screen: viewModelScope dies with the screen and cancels
        // the job mid-batch. Clear All runs here instead — the scope is
        // owned by this ViewModel but never cancelled with it, so leaving
        // the screen cannot stop it; the job holds the scope alive only
        // until it finishes, then both are collectable.
        private val clearScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val isClearing = AtomicBoolean(false)

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
                    viewModelScope.launch {
                        if (playerPreferences.watchHistoryPaused.first()) {
                            // Paused: freeze the server list at the last snapshot
                            // instead of refreshing it, so watches made while paused
                            // never surface here until the user unpauses and reopens
                            // History. Local writes are already blocked by
                            // SessionHistory's own pause gate.
                            _serverEntries.value = accountHistoryCapture.entriesFlow.first()
                            _uiState.update { it.copy(isLoading = false) }
                        } else {
                            loadServerHistory()
                        }
                    }
                    combine(
                        _serverEntries,
                        _serverShorts,
                        viewHistory.getAllHistory(),
                        shortsContentFilter.enabled,
                    ) { server, serverShorts, local, shortsEnabled ->
                        // The server list is the account's newest-first order, so it
                        // is the backbone: a watch the server knows (TV, web, another
                        // device) keeps its server position instead of sinking below
                        // every local row. Only session plays the server has not
                        // recorded yet head the list, newest first. A server row keeps
                        // the local resume position when this device has one.
                        val serverIds = server.map { it.videoId }.toSet()
                        val localById = local.associateBy { it.videoId }
                        val freshLocal =
                            local
                                .sortedByDescending { it.timestamp }
                                .filterNot { it.videoId in serverIds }
                        val merged =
                            freshLocal +
                                server.map { entry ->
                                    val played = localById[entry.videoId]
                                    if (played == null) {
                                        entry
                                    } else {
                                        entry.copy(
                                            position = played.position,
                                            duration = if (played.duration > 0) played.duration else entry.duration,
                                        )
                                    }
                                }
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
            val removeTokens = mutableMapOf<String, String>()
            var clearToken: String? = null
            var clearTokenSource: String? = null
            var continuation: String? = null
            var pages = 0
            do {
                val page = YouTube.serverHistory(continuation).getOrNull() ?: break
                videos += page.videos
                page.shorts.forEach { short ->
                    shortVideos[short.id] = short
                    videos += short
                }
                removeTokens += page.removeTokens
                if (clearToken == null) clearToken = page.clearHistoryToken
                if (clearTokenSource == null) clearTokenSource = page.clearHistoryTokenSource
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
                        removeFeedbackToken = removeTokens[video.id],
                    )
                }
            clearHistoryToken = clearToken
            ShivLog.i("historyTokens clear=${clearToken != null} src=$clearTokenSource removes=${removeTokens.size} videos=${videos.size}")
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
            // One clear at a time; a second tap while one runs is ignored —
            // the running job keeps shrinking the list in the background.
            if (!isClearing.compareAndSet(false, true)) return
            clearScope.launch {
                try {
                    if (YouTube.isSignedIn()) {
                        // The list IS the server list: wiping it locally only
                        // brings it all back on the next refresh (report 26).
                        // One pass removes at most the 3 loaded pages, and
                        // the server keeps a continuation past the third
                        // (report 29: cont=true on page 3), so removing the
                        // newest entries just pulls older ones into the
                        // window — the list stays full-looking after a single
                        // pass. Loop: reload, remove, repeat until a reload
                        // finds nothing left.
                        var pass = 0
                        while (pass < MAX_CLEAR_PASSES) {
                            pass++
                            loadServerHistory()
                            val entries = _serverEntries.value
                            if (entries.isEmpty()) break
                            if (!clearLoadedHistory(entries)) {
                                _messages.send(HistoryUiMessage.Failed(R.string.history_clear_failed))
                                return@launch
                            }
                            ShivLog.i("historyClearPass pass=$pass remaining=${_serverEntries.value.size}")
                        }
                        if (_serverEntries.value.isNotEmpty()) {
                            _messages.send(HistoryUiMessage.Failed(R.string.history_clear_failed))
                            return@launch
                        }
                    }
                    viewHistory.clearAllHistory()
                    accountHistoryCapture.save(emptyList())
                    clearHistoryToken = null
                    _serverEntries.value = emptyList()
                    _serverShorts.value = emptyMap()
                    _uiState.update { it.copy(historyEntries = emptyList(), shortVideos = emptyMap()) }
                } finally {
                    isClearing.set(false)
                }
            }
        }

        /**
         * Removes exactly the loaded entries from the server: the Clear-all
         * token when the page carried one, else every entry's own remove
         * token in batches of 50. Each confirmed batch drops its rows from
         * the list at once, so progress is visible and a stopped job still
         * leaves the list smaller. False on refusal or partial coverage.
         */
        private suspend fun clearLoadedHistory(entries: List<VideoHistoryEntry>): Boolean {
            val token = clearHistoryToken
            if (token != null) {
                return YouTube.sendHistoryFeedback(listOf(token)).getOrDefault(false)
            }
            val pairs =
                entries.mapNotNull { entry ->
                    entry.removeFeedbackToken?.let { entry.videoId to it }
                }
            // Partial coverage would leave history behind silently.
            if (pairs.size != entries.size) return false
            for (chunk in pairs.chunked(50)) {
                if (!YouTube.sendHistoryFeedback(chunk.map { it.second }).getOrDefault(false)) {
                    return false
                }
                val removedIds = chunk.map { it.first }.toSet()
                _serverEntries.update { list -> list.filterNot { it.videoId in removedIds } }
                _uiState.update { state ->
                    state.copy(
                        historyEntries = state.historyEntries.filterNot { it.videoId in removedIds },
                    )
                }
            }
            ShivLog.i("historyClearFallback tokens=${pairs.size}")
            return true
        }

        fun clearShortsHistory() {
            viewModelScope.launch {
                if (YouTube.isSignedIn()) {
                    // No single "delete all Shorts" token exists: every short
                    // carries its own remove token, and the feedback endpoint
                    // takes a list of them in one call.
                    val serverShorts = _serverEntries.value.filter { it.isShort }
                    if (serverShorts.isNotEmpty()) {
                        val tokens = serverShorts.mapNotNull { it.removeFeedbackToken }
                        if (tokens.isEmpty() ||
                            !YouTube.sendHistoryFeedback(tokens).getOrDefault(false)
                        ) {
                            _messages.send(HistoryUiMessage.Failed(R.string.history_clear_failed))
                            return@launch
                        }
                    }
                }
                viewHistory.clearShortsHistory()
                // The capture keeps the account snapshot; drop its shorts too,
                // or a logout would resurrect them on the signed-out screen.
                accountHistoryCapture.save(
                    accountHistoryCapture.entriesFlow.first().filterNot { it.isShort },
                )
                _serverEntries.update { entries -> entries.filterNot { it.isShort } }
                _serverShorts.value = emptyMap()
                _uiState.update { state ->
                    state.copy(
                        historyEntries = state.historyEntries.filterNot { it.isShort },
                        shortVideos = emptyMap(),
                    )
                }
            }
        }

        fun removeFromHistory(videoId: String) {
            viewModelScope.launch {
                val entry = _uiState.value.historyEntries.firstOrNull { it.videoId == videoId }
                if (YouTube.isSignedIn()) {
                    val token =
                        _serverEntries.value.firstOrNull { it.videoId == videoId }?.removeFeedbackToken
                    if (token != null &&
                        !YouTube.sendHistoryFeedback(listOf(token)).getOrDefault(false)
                    ) {
                        _messages.send(HistoryUiMessage.Failed(R.string.history_remove_failed))
                        return@launch
                    }
                    // No token: the row is session-local, or the page never
                    // carried its token — a local remove is all there is.
                }
                viewHistory.clearVideoHistory(videoId)
                _serverEntries.update { entries -> entries.filterNot { it.videoId == videoId } }
                _serverShorts.update { shorts -> shorts - videoId }
                _uiState.update { state ->
                    state.copy(
                        historyEntries = state.historyEntries.filterNot { it.videoId == videoId },
                        shortVideos = state.shortVideos - videoId,
                    )
                }
                if (entry != null) _messages.send(HistoryUiMessage.Removed(entry))
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

/** One-off History outcomes the screen shows: a removed row (with Undo) or a server refusal. */
sealed interface HistoryUiMessage {
    data class Removed(val entry: VideoHistoryEntry) : HistoryUiMessage

    data class Failed(val stringRes: Int) : HistoryUiMessage
}

data class HistoryUiState(
    val historyEntries: List<VideoHistoryEntry> = emptyList(),
    val shortVideos: Map<String, Video> = emptyMap(),
    val isLoading: Boolean = false,
    val shortsEnabled: Boolean = true,
)
