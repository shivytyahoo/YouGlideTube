package io.github.aedev.flow.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * The account's own history as last seen while signed in, kept so a logout does not
 * wipe it: the server list is the base the signed-in History screen renders, and this
 * snapshot is what the signed-out screen merges with the session's own plays.
 * Kept apart from SessionHistory on purpose — its synthetic stamps must never
 * outrank real watch times in the signed-in merge.
 */
@Singleton
class AccountHistoryCapture
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val entries = MutableStateFlow<List<VideoHistoryEntry>>(emptyList())
        val entriesFlow: Flow<List<VideoHistoryEntry>> = entries.asStateFlow()
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val historyJson = Json { ignoreUnknownKeys = true }
        private val captureFile: File
            get() = File(context.filesDir, "account_history.json")

        init {
            ioScope.launch { restore() }
        }

        private suspend fun restore() {
            val decoded =
                runCatching {
                    withContext(Dispatchers.IO) {
                        val file = captureFile
                        if (!file.exists()) return@withContext emptyList()
                        historyJson.decodeFromString<List<VideoHistoryEntry>>(file.readText())
                    }
                }.getOrElse { emptyList() }
            if (decoded.isNotEmpty()) entries.value = decoded
        }

        suspend fun save(serverEntries: List<VideoHistoryEntry>) {
            val unique = serverEntries.distinctBy { it.videoId }
            entries.value = unique
            runCatching {
                withContext(Dispatchers.IO) {
                    if (unique.isEmpty()) {
                        captureFile.delete()
                    } else {
                        captureFile.writeText(historyJson.encodeToString(unique))
                    }
                }
            }
        }
    }
