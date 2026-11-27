package io.github.shivytyahoo.glidetube.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.shivytyahoo.glidetube.data.model.Video
import io.github.shivytyahoo.glidetube.ui.screens.home.GuestTaste
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** The taste the guest home feed learns from, kept on this device and never sent anywhere. */
@Serializable
data class GuestTasteData(
    val ch: Map<String, Double> = emptyMap(),
    val kw: Map<String, Double> = emptyMap(),
    val kwTime: Map<String, Map<String, Double>> = emptyMap(),
    val names: Map<String, String> = emptyMap(),
    /** Search queries already learned from, so a later home load never counts one twice. */
    val learnedSearches: Set<String> = emptySet(),
    /** Video id to the position already learned, so a settled view is graded once per position. */
    val learnedPositions: Map<String, Long> = emptyMap(),
    /** Epoch seconds the scores were last decayed. */
    val decayed: Long = 0L,
)

private const val TASTE_PERSIST_DEBOUNCE_MS = 3000L
private const val CHANNEL_TRIM = 400
private const val KEYWORD_TRIM = 900
private const val BUCKET_TRIM = 150

/**
 * Channel and keyword scores learned from local watching and searching. The scores are what
 * the guest home feed ranks with; the store itself holds no videos and phones nothing home.
 * Callers gate learning on [PlayerPreferences.isWatchHistorySavingBlocked] — the same rule
 * that blocks history saves — so paused history or Deep Glide learns nothing.
 */
@Singleton
class GuestTasteStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val taste = MutableStateFlow(GuestTasteData())
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val tasteJson = Json { ignoreUnknownKeys = true }
        private val tasteFile: File
            get() = File(context.filesDir, "guest_taste.json")
        private var persistJob: Job? = null

        init {
            ioScope.launch { restore() }
        }

        private suspend fun restore() {
            val decoded =
                runCatching {
                    withContext(Dispatchers.IO) {
                        val file = tasteFile
                        if (!file.exists()) return@withContext GuestTasteData()
                        tasteJson.decodeFromString<GuestTasteData>(file.readText())
                    }
                }.getOrElse { GuestTasteData() }
            taste.value = decoded
        }

        private fun schedulePersist() {
            persistJob?.cancel()
            persistJob =
                ioScope.launch {
                    delay(TASTE_PERSIST_DEBOUNCE_MS)
                    runCatching {
                        withContext(Dispatchers.IO) {
                            tasteFile.writeText(tasteJson.encodeToString(taste.value))
                        }
                    }
                }
        }

        fun snapshot(): GuestTasteData = taste.value

        /** A settled view's position already graded at this position. */
        fun isLearned(
            videoId: String,
            positionMs: Long,
        ): Boolean = taste.value.learnedPositions[videoId] == positionMs

        /** The strongest keywords, for the feed's searches and the interests row. */
        fun topKeywords(limit: Int): List<String> =
            taste.value.kw.entries
                .filter { it.value > 0.5 }
                .sortedByDescending { it.value }
                .take(limit)
                .map { it.key }

        /** Channels the viewer keeps watching, strongest first, with their display names. */
        fun likedChannels(limit: Int): List<Pair<String, String>> =
            taste.value.ch.entries
                .filter { it.value > 1.5 }
                .sortedByDescending { it.value }
                .take(limit)
                .map { it.key to (taste.value.names[it.key].orEmpty()) }

        /** The interests row: the strongest keywords, one that only repeats a stronger one left out. */
        fun interests(limit: Int = 8): List<String> {
            val out = ArrayList<String>()
            for (word in topKeywords(KEYWORD_TRIM)) {
                if (out.any { other -> word.contains(other) || other.contains(word) }) continue
                out += word
                if (out.size == limit) break
            }
            return out
        }

        /** Learn from one settled viewing: channel and keyword scores, by how much it was watched. */
        suspend fun learnWatch(
            video: Video,
            watchedSec: Double,
            lengthSec: Double,
            liked: Boolean = false,
            disliked: Boolean = false,
        ) {
            val engagement = GuestTaste.engagement(watchedSec, lengthSec, liked, disliked)
            val tokens = GuestTaste.tokens(video.title, video.tags)
            val titleWords = GuestTaste.words(video.title).filter { it !in tokens }
            val bucket = GuestTaste.bucketNow()
            update { current ->
                val decayed = decay(current)
                val ch = decayed.ch.toMutableMap()
                val names = decayed.names.toMutableMap()
                if (video.channelId.startsWith("UC")) {
                    ch[video.channelId] = GuestTaste.capped((ch[video.channelId] ?: 0.0) + engagement)
                    names[video.channelId] = video.channelName.take(100)
                }
                val kw = decayed.kw.toMutableMap()
                for (word in tokens) kw[word] = GuestTaste.capped((kw[word] ?: 0.0) + engagement * 0.5)
                for (word in titleWords) kw[word] = GuestTaste.capped((kw[word] ?: 0.0) + engagement * 0.2)
                val bucketKw = (decayed.kwTime[bucket].orEmpty()).toMutableMap()
                for (word in tokens + titleWords) {
                    bucketKw[word] = GuestTaste.capped((bucketKw[word] ?: 0.0) + engagement * 0.4)
                }
                val learned =
                    decayed.learnedPositions + (video.id to (watchedSec * 1000).toLong())
                decayed.copy(
                    ch = trim(ch, CHANNEL_TRIM),
                    kw = trim(kw, KEYWORD_TRIM),
                    kwTime = decayed.kwTime + (bucket to trim(bucketKw, BUCKET_TRIM)),
                    names = names.filterKeys { it in ch },
                    learnedPositions = learned,
                )
            }
        }

        /** A search counts a little as an interest, now and at this time of day. */
        suspend fun learnSearch(query: String) {
            val normalized = query.trim().lowercase()
            if (normalized.isEmpty() || normalized in taste.value.learnedSearches) return
            val words = GuestTaste.words(query).take(5)
            if (words.isEmpty()) {
                update { it.copy(learnedSearches = (it.learnedSearches + normalized).toList().takeLast(50).toSet()) }
                return
            }
            val bucket = GuestTaste.bucketNow()
            update { current ->
                val decayed = decay(current)
                val kw = decayed.kw.toMutableMap()
                for (word in words) kw[word] = minOf(4.0, maxOf(0.0, kw[word] ?: 0.0) + 0.6)
                val bucketKw = (decayed.kwTime[bucket].orEmpty()).toMutableMap()
                for (word in words) bucketKw[word] = minOf(4.0, (bucketKw[word] ?: 0.0) + 0.4)
                decayed.copy(
                    kw = trim(kw, KEYWORD_TRIM),
                    kwTime = decayed.kwTime + (bucket to trim(bucketKw, BUCKET_TRIM)),
                    learnedSearches = (decayed.learnedSearches + normalized).toList().takeLast(50).toSet(),
                )
            }
        }

        private fun update(transform: (GuestTasteData) -> GuestTasteData) {
            taste.update(transform)
            schedulePersist()
        }

        private fun decay(current: GuestTasteData): GuestTasteData {
            val nowSec = System.currentTimeMillis() / 1000
            val last = if (current.decayed == 0L) nowSec else current.decayed
            val days = (nowSec - last) / 86400.0
            if (days < 0.25) return if (current.decayed == 0L) current.copy(decayed = nowSec) else current
            return current.copy(
                ch = GuestTaste.decayed(current.ch, days),
                kw = GuestTaste.decayed(current.kw, days),
                kwTime = current.kwTime.mapValues { GuestTaste.decayed(it.value, days) },
                decayed = nowSec,
            )
        }

        private fun trim(
            scores: Map<String, Double>,
            keep: Int,
        ): Map<String, Double> =
            if (scores.size > keep) {
                scores.entries
                    .sortedByDescending { kotlin.math.abs(it.value) }
                    .take(keep)
                    .associate { it.key to it.value }
            } else {
                scores
            }
    }
