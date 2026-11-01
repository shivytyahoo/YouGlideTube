package io.github.aedev.flow.data.local

import android.content.Context
import android.util.Log
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.music.model.withTypedArtists
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The music queue lives in memory only. It used to be mirrored to a DataStore so the queue
 * survived an app restart; that store is gone, so within a session the queue behaves exactly
 * as before and after a restart playback simply starts fresh.
 */
class QueuePersistence private constructor(
    @Suppress("unused") private val context: Context,
) {
    companion object {
        private const val TAG = "QueuePersistence"
        private const val SAVE_DEBOUNCE_MS = 5_000L // Debounce saves to every 5 seconds
        private const val AUTO_SAVE_INTERVAL_MS = 30_000L // Auto-save every 30 seconds

        @Volatile
        private var instance: QueuePersistence? = null

        fun getInstance(context: Context): QueuePersistence =
            instance ?: synchronized(this) {
                instance ?: QueuePersistence(context.applicationContext).also { instance = it }
            }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val saveMutex = Mutex()

    private var saveJob: Job? = null
    private var autoSaveJob: Job? = null
    private var lastSaveTime = 0L

    @Volatile
    private var savedState: QueueState? = null

    /**
     * Data class holding all queue state
     */
    data class QueueState(
        val queue: List<MusicTrack>,
        val currentIndex: Int,
        val currentPosition: Long,
        val currentTrackId: String?,
        val shuffleEnabled: Boolean,
        val repeatMode: Int, // 0=OFF, 1=ALL, 2=ONE
        val savedAt: Long,
        val automix: List<MusicTrack> = emptyList(),
    )

    /**
     * Save queue state with debouncing
     */
    fun saveQueueDebounced(
        queue: List<MusicTrack>,
        currentIndex: Int,
        currentPosition: Long,
        currentTrackId: String?,
        shuffleEnabled: Boolean = false,
        repeatMode: Int = 0,
        automix: List<MusicTrack> = emptyList(),
    ) {
        saveJob?.cancel()
        saveJob =
            scope.launch {
                delay(SAVE_DEBOUNCE_MS)
                saveQueueImmediate(queue, currentIndex, currentPosition, currentTrackId, shuffleEnabled, repeatMode, automix)
            }
    }

    /**
     * Immediately save queue state (use for critical moments like app pause)
     */
    suspend fun saveQueueImmediate(
        queue: List<MusicTrack>,
        currentIndex: Int,
        currentPosition: Long,
        currentTrackId: String?,
        shuffleEnabled: Boolean = false,
        repeatMode: Int = 0,
        automix: List<MusicTrack> = emptyList(),
    ) {
        if (queue.isEmpty()) return

        saveMutex.withLock {
            val now = System.currentTimeMillis()
            savedState =
                QueueState(
                    queue = queue,
                    currentIndex = currentIndex,
                    currentPosition = currentPosition,
                    currentTrackId = currentTrackId,
                    shuffleEnabled = shuffleEnabled,
                    repeatMode = repeatMode,
                    savedAt = now,
                    automix = automix,
                )
            lastSaveTime = now
            Log.d(TAG, "Queue saved: ${queue.size} tracks, index=$currentIndex, pos=$currentPosition, automix=${automix.size}")
        }
    }

    /**
     * Restore the queue state held for this session, or null when nothing was saved.
     */
    suspend fun restoreQueue(): QueueState? {
        val state = savedState ?: return null
        if (state.queue.isEmpty()) return null
        Log.d(TAG, "Queue restored: ${state.queue.size} tracks, saved ${(System.currentTimeMillis() - state.savedAt) / 1000}s ago")
        return state.copy(
            queue = state.queue.map { it.withTypedArtists() },
            automix = state.automix.map { it.withTypedArtists() },
        )
    }

    /**
     * Start automatic periodic saving
     */
    fun startAutoSave(getQueueState: () -> QueueState?) {
        autoSaveJob?.cancel()
        autoSaveJob =
            scope.launch {
                while (true) {
                    delay(AUTO_SAVE_INTERVAL_MS)
                    getQueueState()?.let { state ->
                        if (state.queue.isNotEmpty()) {
                            saveQueueImmediate(
                                queue = state.queue,
                                currentIndex = state.currentIndex,
                                currentPosition = state.currentPosition,
                                currentTrackId = state.currentTrackId,
                                shuffleEnabled = state.shuffleEnabled,
                                repeatMode = state.repeatMode,
                                automix = state.automix,
                            )
                        }
                    }
                }
            }
    }

    /**
     * Stop automatic saving (call when service/player is stopped)
     */
    fun stopAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = null
    }

    /**
     * Whether a queue was saved this session. Only non-empty queues are written, so this is
     * true exactly when there is a queue to resume. A media button that cold-starts the music
     * service needs this answer on the main thread.
     */
    fun hasSavedQueueFile(): Boolean = savedState != null
}
