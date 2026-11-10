package io.github.aedev.flow.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One history row, kept in memory and mirrored to disk so it survives a restart. */
@Serializable
data class VideoHistoryEntry(
    val videoId: String,
    val position: Long,
    val duration: Long,
    val timestamp: Long,
    val title: String,
    val thumbnailUrl: String,
    val channelName: String = "",
    val channelId: String = "",
    val isMusic: Boolean = false,
    val isShort: Boolean = false,
    val isLocal: Boolean = false,
    /** History only: the server-issued token that removes this entry from the account. */
    val removeFeedbackToken: String? = null,
) {
    val progressPercentage: Float
        get() = if (duration > 0) (position.toFloat() / duration.toFloat()) * 100f else 0f
}

/** Position and duration of one entry, for progress bars and watched checks. */
data class WatchProgress(
    val videoId: String,
    val position: Long,
    val duration: Long,
    val timestamp: Long,
)

private const val SESSION_HISTORY_LIMIT = 500
private const val PERSIST_DEBOUNCE_MS = 3000L

/**
 * Playback positions and the recent-history list. Resume, progress bars, watched checks and
 * the signed-out History tab all read from here, and the account's own history (server)
 * covers signed-in surfaces. Entries are also mirrored to disk, so a restart no longer
 * empties Home's continue-watching and Library's history while signed out; the account
 * feed takes over on sign-in as before. The pause-history preference still blocks new
 * saves (never deletions).
 */
@Singleton
class SessionHistory
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val entries = MutableStateFlow<List<VideoHistoryEntry>>(emptyList())
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val historyJson = Json { ignoreUnknownKeys = true }
        private val historyFile: File
            get() = File(context.filesDir, "guest_history.json")
        private var persistJob: Job? = null

        init {
            ioScope.launch { restore() }
        }

        private suspend fun restore() {
            val decoded =
                runCatching {
                    withContext(Dispatchers.IO) {
                        val file = historyFile
                        if (!file.exists()) return@withContext emptyList()
                        historyJson.decodeFromString<List<VideoHistoryEntry>>(file.readText())
                    }
                }.getOrElse { emptyList() }
            if (decoded.isNotEmpty()) entries.value = decoded.take(SESSION_HISTORY_LIMIT)
        }

        private fun schedulePersist() {
            persistJob?.cancel()
            persistJob =
                ioScope.launch {
                    delay(PERSIST_DEBOUNCE_MS)
                    if (PlayerPreferences(context).isWatchHistorySavingBlocked()) return@launch
                    writeList(entries.value)
                }
        }

        private fun persistRemoval(videoIds: Set<String>) {
            ioScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val file = historyFile
                        if (!file.exists()) return@withContext
                        val remaining =
                            historyJson.decodeFromString<List<VideoHistoryEntry>>(file.readText())
                                .filter { it.videoId !in videoIds }
                        if (remaining.isEmpty()) file.delete() else file.writeText(historyJson.encodeToString(remaining))
                    }
                }
            }
        }

        private fun dropPersistedFile() {
            ioScope.launch {
                runCatching { withContext(Dispatchers.IO) { historyFile.delete() } }
            }
        }

        private suspend fun writeList(list: List<VideoHistoryEntry>) {
            runCatching {
                withContext(Dispatchers.IO) {
                    historyFile.writeText(historyJson.encodeToString(list.take(SESSION_HISTORY_LIMIT)))
                }
            }
        }

        private fun upsert(entry: VideoHistoryEntry) {
            entries.update { current ->
                val rest = current.filterNot { it.videoId == entry.videoId }
                (listOf(entry) + rest).take(SESSION_HISTORY_LIMIT)
            }
            schedulePersist()
        }

        private fun find(videoId: String): VideoHistoryEntry? = entries.value.firstOrNull { it.videoId == videoId }

        // ── Writes ───────────────────────────────────────────────────────────────

        suspend fun savePlaybackPosition(
            videoId: String,
            position: Long,
            duration: Long,
            title: String = "",
            thumbnailUrl: String = "",
            channelName: String = "",
            channelId: String = "",
            isMusic: Boolean = false,
            isShort: Boolean = false,
            isLocal: Boolean = LocalMediaIds.isLocal(videoId),
        ) {
            if (PlayerPreferences(context).isWatchHistorySavingBlocked()) return
            val existing = find(videoId)
            val thumbnail = if (isLocal) thumbnailUrl else ThumbnailUrlResolver.normalizeVideoThumbnail(videoId, thumbnailUrl)
            upsert(
                VideoHistoryEntry(
                    videoId = videoId,
                    position = position,
                    duration = duration,
                    timestamp = System.currentTimeMillis(),
                    title = title.ifBlank { existing?.title.orEmpty() },
                    thumbnailUrl = thumbnail.ifBlank { existing?.thumbnailUrl.orEmpty() },
                    channelName = channelName.ifBlank { existing?.channelName.orEmpty() },
                    channelId = channelId.ifBlank { existing?.channelId.orEmpty() },
                    isMusic = isMusic || (existing?.isMusic ?: false),
                    isShort = isShort || (existing?.isShort ?: false),
                    isLocal = isLocal,
                ),
            )
        }

        suspend fun markCompleted(
            videoId: String,
            durationMs: Long,
        ) {
            if (durationMs <= 0L) return
            if (PlayerPreferences(context).isWatchHistorySavingBlocked()) return
            val existing = find(videoId) ?: return
            upsert(existing.copy(position = durationMs, duration = durationMs, timestamp = System.currentTimeMillis()))
        }

        /** Clears playback progress for [videoId]; the entry stays, its card stops reading as watched. */
        suspend fun clearProgress(videoId: String) {
            val existing = find(videoId) ?: return
            upsert(existing.copy(position = 0L))
        }

        suspend fun getSavedPosition(videoId: String): Long = find(videoId)?.position ?: 0L

        suspend fun touchHistoryEntry(
            videoId: String,
            title: String = "",
            thumbnailUrl: String = "",
            channelName: String = "",
            channelId: String = "",
            duration: Long = 0L,
            isShort: Boolean = false,
        ) {
            if (PlayerPreferences(context).isWatchHistorySavingBlocked()) return
            val existing = find(videoId)
            upsert(
                VideoHistoryEntry(
                    videoId = videoId,
                    position = existing?.position ?: 0L,
                    duration = duration.takeIf { it > 0 } ?: existing?.duration ?: 0L,
                    timestamp = System.currentTimeMillis(),
                    title = title.ifBlank { existing?.title.orEmpty() },
                    thumbnailUrl =
                        ThumbnailUrlResolver.normalizeVideoThumbnail(videoId, thumbnailUrl)
                            .ifBlank { existing?.thumbnailUrl.orEmpty() },
                    channelName = channelName.ifBlank { existing?.channelName.orEmpty() },
                    channelId = channelId.ifBlank { existing?.channelId.orEmpty() },
                    isMusic = existing?.isMusic ?: false,
                    isShort = isShort || (existing?.isShort ?: false),
                    isLocal = LocalMediaIds.isLocal(videoId),
                ),
            )
        }

        /** Merges fetched metadata into stored stubs; real playback progress is never overwritten. */
        suspend fun bulkSaveHistoryEntries(newEntries: List<VideoHistoryEntry>) {
            if (newEntries.isEmpty()) return
            newEntries.forEach { entry ->
                val existing = find(entry.videoId)
                if (existing == null) {
                    upsert(entry)
                } else {
                    upsert(
                        existing.copy(
                            title = existing.title.ifBlank { entry.title },
                            thumbnailUrl = existing.thumbnailUrl.ifBlank { entry.thumbnailUrl },
                            channelName = existing.channelName.ifBlank { entry.channelName },
                            channelId = existing.channelId.ifBlank { entry.channelId },
                            duration = if (existing.duration > 0) existing.duration else entry.duration,
                            isShort = existing.isShort || entry.isShort,
                            isMusic = existing.isMusic || entry.isMusic,
                        ),
                    )
                }
            }
        }

        suspend fun clearVideoHistory(videoId: String) {
            entries.update { current -> current.filterNot { it.videoId == videoId } }
            persistRemoval(setOf(videoId))
        }

        /** Marks [videoId] fully watched so it leaves the resume prompt. */
        suspend fun markAsWatched(videoId: String) {
            val existing = find(videoId) ?: return
            if (existing.duration > 0) upsert(existing.copy(position = existing.duration))
        }

        suspend fun clearAllHistory() {
            entries.value = emptyList()
            dropPersistedFile()
        }

        suspend fun clearShortsHistory() {
            val removed = entries.value.filter { it.isShort }.map { it.videoId }.toSet()
            entries.update { current -> current.filterNot { it.isShort } }
            if (removed.isNotEmpty()) persistRemoval(removed)
        }

        // ── Reads ────────────────────────────────────────────────────────────────

        fun getPlaybackPosition(videoId: String): Flow<Long> = entries.map { list -> list.firstOrNull { it.videoId == videoId }?.position ?: 0L }

        fun getVideoHistory(videoId: String): Flow<VideoHistoryEntry?> = entries.map { list -> list.firstOrNull { it.videoId == videoId } }

        /** All session history, newest first. */
        fun getAllHistory(): Flow<List<VideoHistoryEntry>> = entries

        fun getRecentLibraryHistory(limit: Int): Flow<List<VideoHistoryEntry>> =
            entries.map { list -> list.filter { !it.isShort && !it.isLocal }.take(limit) }

        /** Video (non-music) history, newest first. */
        fun getVideoHistoryFlow(): Flow<List<VideoHistoryEntry>> =
            entries.map { list -> list.filter { !it.isMusic && !it.isLocal } }

        /** Music history, newest first. */
        fun getMusicHistoryFlow(): Flow<List<VideoHistoryEntry>> =
            entries.map { list -> list.filter { it.isMusic && !it.isLocal } }

        /** Plays of files on the device, newest first. */
        fun getLocalHistoryFlow(): Flow<List<VideoHistoryEntry>> =
            entries.map { list -> list.filter { it.isLocal } }

        private fun List<VideoHistoryEntry>.progress(): List<WatchProgress> =
            map { WatchProgress(it.videoId, it.position, it.duration, it.timestamp) }

        /** Position and duration of every entry, for progress bars. */
        fun getAllWatchProgress(): Flow<List<WatchProgress>> = entries.map { it.progress() }

        /** Position and duration of every video (non-music, non-local) entry, for watched checks. */
        fun getVideoWatchProgress(): Flow<List<WatchProgress>> =
            entries.map { list -> list.filter { !it.isMusic && !it.isLocal }.progress() }

        suspend fun getRecentVideoHistory(
            limit: Int,
            includeShorts: Boolean,
        ): List<VideoHistoryEntry> =
            entries.value
                .filter { !it.isMusic && !it.isLocal }
                .filter { includeShorts || !it.isShort }
                .take(limit)

        fun observeRecentVideoHistory(
            limit: Int,
            includeShorts: Boolean,
        ): Flow<List<VideoHistoryEntry>> =
            entries.map { list ->
                list
                    .filter { !it.isMusic && !it.isLocal }
                    .filter { includeShorts || !it.isShort }
                    .take(limit)
            }

        suspend fun getShortProgress(): List<WatchProgress> =
            entries.value.filter { it.isShort }.progress()

        suspend fun getWatchProgress(videoId: String): WatchProgress? =
            find(videoId)?.let { WatchProgress(it.videoId, it.position, it.duration, it.timestamp) }

        fun getVideoCount(): Flow<Int> = entries.map { list -> list.count { !it.isMusic && !it.isLocal } }

        fun getLibraryHistoryCount(): Flow<Int> = entries.map { list -> list.count { !it.isShort && !it.isLocal } }

        /** Progress rows for [videoIds], for watched checks over an arbitrary id set. */
        suspend fun getProgressFor(videoIds: List<String>): List<WatchProgress> {
            val wanted = videoIds.toHashSet()
            return entries.value.filter { it.videoId in wanted }.progress()
        }

        /** Distinct channel ids appearing in session history, oldest use first. */
        suspend fun watchedChannelIds(): List<String> =
            entries.value.asReversed().mapNotNull { it.channelId.takeIf(String::isNotBlank) }.distinct()

        /** The most recently watched unfinished video (<95% complete), for the resume prompt. */
        suspend fun getLatestUnfinishedVideo(): VideoHistoryEntry? =
            entries.value.firstOrNull { entry ->
                !entry.isLocal && entry.duration > 0 && entry.position > 0 && entry.position.toDouble() / entry.duration < 0.95
            }
    }
