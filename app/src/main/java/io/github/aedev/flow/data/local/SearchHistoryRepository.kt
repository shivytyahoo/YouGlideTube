package io.github.aedev.flow.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class SearchHistoryItem(
    val id: String = UUID.randomUUID().toString(),
    val query: String,
    val timestamp: Long = System.currentTimeMillis(),
    val type: SearchType = SearchType.TEXT,
    /** The filters the search ran with; null on entries saved before filters were kept. */
    val filters: SearchFilter? = null,
)

@Serializable
enum class SearchType {
    TEXT,
    VOICE,
    SUGGESTION,
}

enum class SearchHistoryScope {
    VIDEO,
    MUSIC,
}

data class SearchSuggestion(
    val text: String,
    val type: SuggestionType = SuggestionType.VIDEO,
)

enum class SuggestionType {
    VIDEO,
    CHANNEL,
    PLAYLIST,
    TRENDING,
}

/**
 * Search history, persisted in the player preferences DataStore: recent searches and the
 * five history settings survive restarts. The in-memory flows stay the live source; every
 * mutation writes through to DataStore, and the stored lists load once at startup.
 */
@Singleton
class SearchHistoryRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        companion object {
            private const val DEFAULT_MAX_HISTORY_SIZE = 50
            private const val DEFAULT_RETENTION_DAYS = 90
        }

        private val histories =
            MutableStateFlow<Map<SearchHistoryScope, List<SearchHistoryItem>>>(
                SearchHistoryScope.entries.associateWith { emptyList() },
            )
        private val historyEnabled = MutableStateFlow(true)
        private val suggestionsEnabled = MutableStateFlow(true)
        private val maxHistorySize = MutableStateFlow(DEFAULT_MAX_HISTORY_SIZE)
        private val autoDeleteHistory = MutableStateFlow(false)
        private val historyRetentionDays = MutableStateFlow(DEFAULT_RETENTION_DAYS)

        private val preferences = PlayerPreferences(context)
        private val historyJson = Json { ignoreUnknownKeys = true }
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Loads the persisted lists and settings once; mutations await it so no save is lost. */
        private val loadJob =
            ioScope.async {
                runCatching {
                    historyEnabled.value = preferences.searchHistoryEnabled.first()
                    suggestionsEnabled.value = preferences.searchSuggestionsEnabled.first()
                    maxHistorySize.value = preferences.searchHistoryMaxSize.first()
                    autoDeleteHistory.value = preferences.searchHistoryAutoDelete.first()
                    historyRetentionDays.value = preferences.searchHistoryRetentionDays.first()
                    val loaded =
                        SearchHistoryScope.entries.associateWith { scope ->
                            val raw =
                                if (scope == SearchHistoryScope.VIDEO) {
                                    preferences.searchHistoryVideoJson.first()
                                } else {
                                    preferences.searchHistoryMusicJson.first()
                                }
                            val items =
                                raw?.let {
                                    runCatching { historyJson.decodeFromString<List<SearchHistoryItem>>(it) }
                                        .getOrDefault(emptyList())
                                } ?: emptyList()
                            filterExpiredHistory(items)
                        }
                    histories.value = loaded
                    // Write back only when pruning dropped stale entries.
                    loaded.forEach { (scope, items) -> persistScope(scope, items, writeEvenIfUnchanged = false) }
                }
            }

        private suspend fun awaitLoaded() = loadJob.join()

        private suspend fun persistScope(
            scope: SearchHistoryScope,
            items: List<SearchHistoryItem>,
            writeEvenIfUnchanged: Boolean = true,
        ) {
            val json = historyJson.encodeToString(items)
            if (scope == SearchHistoryScope.VIDEO) {
                if (writeEvenIfUnchanged || preferences.searchHistoryVideoJson.first() != json) {
                    preferences.setSearchHistoryVideoJson(json)
                }
            } else {
                if (writeEvenIfUnchanged || preferences.searchHistoryMusicJson.first() != json) {
                    preferences.setSearchHistoryMusicJson(json)
                }
            }
        }

        // Save search query
        suspend fun saveSearchQuery(
            query: String,
            type: SearchType = SearchType.TEXT,
            scope: SearchHistoryScope = SearchHistoryScope.VIDEO,
            filters: SearchFilter? = null,
        ) {
            awaitLoaded()
            if (!isSearchHistoryEnabled()) return
            if (query.isBlank()) return

            val updated =
                histories.value.getValue(scope).withSearch(
                    query = query,
                    type = type,
                    filters = filters,
                    maxSize = maxHistorySize.value,
                    now = System.currentTimeMillis(),
                )
            histories.value = histories.value + (scope to updated)
            persistScope(scope, updated)
        }

        // Get search history as Flow
        fun getSearchHistoryFlow(scope: SearchHistoryScope = SearchHistoryScope.VIDEO): Flow<List<SearchHistoryItem>> =
            histories.map { all ->
                if (historyEnabled.value) {
                    filterExpiredHistory(all.getValue(scope))
                } else {
                    emptyList()
                }
            }

        // Get recent searches (limit)
        suspend fun getRecentSearches(limit: Int = 10): List<SearchHistoryItem> = getSearchHistoryFlow().first().take(limit)

        // Delete specific search item
        suspend fun deleteSearchItem(
            itemId: String,
            scope: SearchHistoryScope = SearchHistoryScope.VIDEO,
        ) {
            awaitLoaded()
            val updated = histories.value.getValue(scope).filter { it.id != itemId }
            histories.value = histories.value + (scope to updated)
            persistScope(scope, updated)
        }

        suspend fun clearSearchHistory(scope: SearchHistoryScope) {
            awaitLoaded()
            histories.value = histories.value + (scope to emptyList())
            persistScope(scope, emptyList())
        }

        suspend fun clearSearchHistory() {
            awaitLoaded()
            histories.value = SearchHistoryScope.entries.associateWith { emptyList() }
            SearchHistoryScope.entries.forEach { scope -> persistScope(scope, emptyList()) }
        }

        suspend fun replaceSearchHistory(
            items: List<SearchHistoryItem>,
            scope: SearchHistoryScope = SearchHistoryScope.VIDEO,
        ) {
            awaitLoaded()
            val updated = items.newestUnique(maxHistorySize.value)
            histories.value = histories.value + (scope to updated)
            persistScope(scope, updated)
        }

        /**
         * Adds searches from another source to the ones kept, newest first, each query once and
         * no more than the viewer's limit. Returns how many of [items] are kept; nothing is added while
         * search history is off, and searches older than an auto-delete window are left out.
         */
        suspend fun importSearches(
            items: List<SearchHistoryItem>,
            scope: SearchHistoryScope,
        ): Int {
            awaitLoaded()
            if (items.isEmpty() || !isSearchHistoryEnabled()) return 0
            val importedIds = items.mapTo(HashSet()) { it.id }
            val merged = filterExpiredHistory(histories.value.getValue(scope) + items).newestUnique(maxHistorySize.value)
            histories.value = histories.value + (scope to merged)
            persistScope(scope, merged)
            return merged.count { it.id in importedIds }
        }

        private fun List<SearchHistoryItem>.newestUnique(maxSize: Int): List<SearchHistoryItem> =
            asSequence()
                .filter { it.query.isNotBlank() }
                .sortedByDescending { it.timestamp }
                .distinctBy { it.query.searchHistoryKey() }
                .take(maxSize)
                .toList()

        // Settings: Enable/disable search history
        suspend fun setSearchHistoryEnabled(enabled: Boolean) {
            awaitLoaded()
            historyEnabled.value = enabled
            preferences.setSearchHistoryEnabled(enabled)
        }

        fun isSearchHistoryEnabledFlow(): Flow<Boolean> = historyEnabled

        suspend fun isSearchHistoryEnabled(): Boolean {
            awaitLoaded()
            return isSearchHistoryEnabledFlow().first()
        }

        // Settings: Enable/disable search suggestions
        suspend fun setSearchSuggestionsEnabled(enabled: Boolean) {
            awaitLoaded()
            suggestionsEnabled.value = enabled
            preferences.setSearchSuggestionsEnabled(enabled)
        }

        fun isSearchSuggestionsEnabledFlow(): Flow<Boolean> = suggestionsEnabled

        suspend fun isSearchSuggestionsEnabled(): Boolean = isSearchSuggestionsEnabledFlow().first()

        // Settings: Max history size
        suspend fun setMaxHistorySize(size: Int) {
            awaitLoaded()
            maxHistorySize.value = size
            histories.value =
                histories.value.mapValues { (_, current) ->
                    if (current.size > size) current.take(size) else current
                }
            preferences.setSearchHistoryMaxSize(size)
            histories.value.forEach { (scope, items) -> persistScope(scope, items) }
        }

        fun getMaxHistorySizeFlow(): Flow<Int> = maxHistorySize

        // Settings: Auto-delete history
        suspend fun setAutoDeleteHistory(enabled: Boolean) {
            awaitLoaded()
            autoDeleteHistory.value = enabled
            preferences.setSearchHistoryAutoDelete(enabled)
        }

        fun isAutoDeleteHistoryEnabledFlow(): Flow<Boolean> = autoDeleteHistory

        // Settings: History retention days
        suspend fun setHistoryRetentionDays(days: Int) {
            awaitLoaded()
            historyRetentionDays.value = days
            preferences.setSearchHistoryRetentionDays(days)
        }

        fun getHistoryRetentionDaysFlow(): Flow<Int> = historyRetentionDays

        // Helper: Filter expired history
        private fun filterExpiredHistory(history: List<SearchHistoryItem>): List<SearchHistoryItem> {
            if (!autoDeleteHistory.value) return history

            val cutoffTime = System.currentTimeMillis() - (historyRetentionDays.value * 24 * 60 * 60 * 1000L)

            return history.filter { it.timestamp >= cutoffTime }
        }

        // Get search suggestions from YouTube API (now handled by YouTubeRepository)
        // This method is kept for backward compatibility but deprecated
        @Deprecated("Use YouTubeRepository.getSearchSuggestions() instead")
        fun getSearchSuggestions(query: String): List<SearchSuggestion> {
            if (query.isBlank()) return emptyList()

            // Return empty list - actual suggestions should come from YouTubeRepository
            return emptyList()
        }
    }
