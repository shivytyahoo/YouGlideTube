package io.github.shivytyahoo.glidetube.ui.screens.home

import io.github.shivytyahoo.glidetube.data.local.GuestTasteData
import io.github.shivytyahoo.glidetube.data.local.GuestTasteStore
import io.github.shivytyahoo.glidetube.data.local.HomeFeedCacheFilters
import io.github.shivytyahoo.glidetube.data.local.HomeFeedCacheRepository
import io.github.shivytyahoo.glidetube.data.local.VideoHistoryEntry
import io.github.shivytyahoo.glidetube.data.model.Video
import io.github.shivytyahoo.glidetube.data.repository.YouTubeRepository
import io.github.shivytyahoo.glidetube.innertube.YouTube
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.random.Random

/** One shelf of the guest feed: the videos one source grouped. The heading comes from [source] and [tag]. */
data class HomeShelf(
    val source: FeedSource,
    val tag: String?,
    val videos: List<Video>,
)

/** A guest feed page: the ranked videos, why each card is there, and its shelves. */
data class GuestFeedResult(
    val videos: List<Video>,
    val origins: Map<String, HomeCardOrigin>,
    val shelves: List<HomeShelf>,
)

internal data class Cand(
    val video: Video,
    val source: FeedSource,
    val tag: String? = null,
    /** From a starter-topic seed rather than a liked keyword: sinks once the taste knows enough. */
    val seed: Boolean = false,
)

private const val POOL_TTL_SEC = 3600L
private const val SHORT_MAX_SEC = 180
private const val MATURE_HISTORY_SIZE = 80
private const val FRESH_WINDOW_SEC = 3 * 86400L

/**
 * The guest home feed, built on this device: candidate pools (videos related to what was
 * watched, uploads of channels kept being watched, searches for the words liked, neighbour
 * channels, starter topics when there is little else) ranked by the local taste. Signed-in
 * Home never comes here — the server feed is its feed — so this changes nothing for it.
 */
@Singleton
class GuestHomeFeed
    @Inject
    constructor(
        private val repository: YouTubeRepository,
        private val tasteStore: GuestTasteStore,
        private val relatedCache: HomeFeedCacheRepository,
    ) {
        private val pools = ConcurrentHashMap<String, Pair<Long, List<Cand>>>()
        private val impressions = ConcurrentHashMap<String, Int>()
        private val random = Random(System.currentTimeMillis())

        private fun shown(list: List<Video>) {
            for (video in list) impressions[video.id] = (impressions[video.id] ?: 0) + 1
        }

        private suspend fun pool(
            key: String,
            ttlSec: Long,
            fetch: suspend () -> List<Cand>,
        ): List<Cand> {
            val nowSec = System.currentTimeMillis() / 1000
            pools[key]?.let { (at, value) -> if (nowSec - at < ttlSec) return value }
            val items =
                try {
                    fetch()
                } catch (_: Exception) {
                    pools[key]?.second.orEmpty()
                }
            pools[key] = nowSec to items
            return items
        }

        private suspend fun search(
            query: String,
            source: FeedSource,
            tag: String?,
            seed: Boolean,
        ): List<Cand> =
            repository
                .searchVideos(query)
                .first
                .filter { !it.isShort }
                .map { Cand(it, source, tag, seed) }

        /** The guest feed: ranked local candidates, with origins and shelves for the first page. */
        suspend fun buildGuestFeed(
            n: Int = 40,
            skip: Set<String> = emptySet(),
            history: List<VideoHistoryEntry> = emptyList(),
            subscribed: Set<String> = emptySet(),
            filters: suspend () -> HomeFeedCacheFilters = { HomeFeedCacheFilters() },
        ): GuestFeedResult =
            coroutineScope {
                val taste = tasteStore.snapshot()
                val historyById = history.associateBy { it.videoId }
                val bucket = GuestTaste.bucketNow()
                val cacheFilters = filters()

                val relatedBySeed = ConcurrentHashMap<String, List<Video>>()
                val relatedJob =
                    async {
                        val seeds = history.filter { !it.isShort }.take(20)
                        val lists =
                            seeds.map { entry ->
                                async {
                                    runCatching { relatedCache.loadRelated(entry.videoId, cacheFilters) }
                                        .getOrElse { emptyList() }
                                        .also { relatedBySeed[entry.videoId] = it }
                                }
                            }
                        lists.map { it.await() }.flatten()
                    }
                val likedChannels =
                    tasteStore.likedChannels(4).filter { it.first !in subscribed }
                val channelJob =
                    async {
                        likedChannels
                            .map { (channelId, name) ->
                                async {
                                    pool("ch:$channelId", POOL_TTL_SEC) {
                                        YouTube
                                            .channelVideos(channelId, name, "")
                                            .getOrNull()
                                            ?.videos
                                            .orEmpty()
                                            .filter { !it.isShort }
                                            .take(10)
                                            .map { Cand(it, FeedSource.CHANNEL_MEMORY) }
                                    }
                                }
                            }.map { it.await() }
                            .flatten()
                    }
                val topKeywords =
                    taste.kw.entries
                        .filter { it.value > 0.5 }
                        .sortedByDescending { it.value }
                        .take(15)
                        .toMutableList()
                val drawn = ArrayList<String>()
                while (drawn.size < 5 && topKeywords.isNotEmpty()) {
                    var roll = random.nextDouble() * topKeywords.sumOf { sqrt(it.value) }
                    val index =
                        topKeywords
                            .indexOfFirst {
                                roll -= sqrt(it.value)
                                roll <= 0
                            }.let { if (it < 0) topKeywords.size - 1 else it }
                    drawn += topKeywords.removeAt(index).key
                }
                val timeKeywords =
                    taste.kwTime[bucket]
                        .orEmpty()
                        .entries
                        .filter { it.value > 0.8 && it.key !in drawn }
                        .sortedByDescending { it.value }
                        .take(2)
                        .map { it.key }
                val likedWords = drawn + timeKeywords
                val keywordJob =
                    async {
                        likedWords
                            .map { word ->
                                async {
                                    pool("kw:$word", POOL_TTL_SEC) {
                                        search(word, FeedSource.DISCOVERY, word, seed = false)
                                    }
                                }
                            }.map { it.await() }
                            .flatten()
                    }

                val relatedVideos = relatedJob.await()
                val relatedCands = relatedVideos.filter { !it.isShort }.map { Cand(it, FeedSource.RELATED) }
                val neighbourChannels =
                    neighbourChannels(historyById, relatedBySeed, taste, subscribed, likedChannels.map { it.first }.toSet())
                val neighbourJob =
                    async {
                        neighbourChannels
                            .map { (channelId, _) ->
                                async {
                                    pool("near:$channelId", POOL_TTL_SEC) {
                                        YouTube
                                            .channelVideos(channelId, "", "")
                                            .getOrNull()
                                            ?.videos
                                            .orEmpty()
                                            .filter { !it.isShort }
                                            .take(6)
                                            .map { Cand(it, FeedSource.NEIGHBOURS) }
                                    }
                                }
                            }.map { it.await() }
                            .flatten()
                    }

                val candidates = ArrayList<Cand>()
                candidates += relatedCands
                candidates += channelJob.await()
                candidates += keywordJob.await()
                candidates += neighbourJob.await()
                if (candidates.size < 30) {
                    val seeds = (likedWords + GuestTaste.starterTopics.shuffled(random)).distinct().take(4)
                    candidates +=
                        seeds
                            .map { query ->
                                async {
                                    pool("seed:$query", POOL_TTL_SEC) {
                                        search(query, FeedSource.DISCOVERY, query, seed = true)
                                    }
                                }
                            }.map { it.await() }
                            .flatten()
                }

                val seedTitles = HashMap<String, String>()
                for ((seedId, list) in relatedBySeed) {
                    val seedTitle = historyById[seedId]?.title.orEmpty()
                    if (seedTitle.isEmpty()) continue
                    for (video in list) seedTitles.putIfAbsent(video.id, seedTitle)
                }
                val queries =
                    candidates
                        .filter { it.source == FeedSource.DISCOVERY && it.tag != null }
                        .associate { it.video.id to it.tag!! }
                val neighbourPointers = neighbourPointerTexts(neighbourChannels)
                val ranked =
                    rankCandidates(
                        candidates = candidates,
                        n = n,
                        skip = skip,
                        taste = taste,
                        history = historyById,
                        exclusions = cacheFilters.exclusions,
                        impressions = impressions,
                        bucket = bucket,
                        nowSec = System.currentTimeMillis() / 1000,
                        random = random,
                    )
                val sources =
                    candidates
                        .distinctBy { it.video.id }
                        .associate { it.video.id to (it.source to it.tag) }
                val origins =
                    pageOrigins(
                        videos = ranked,
                        sourceOf = { id -> sources[id]?.first },
                        seedTitles = seedTitles,
                        queries = queries,
                        followed = subscribed,
                        neighbourPointers = neighbourPointers,
                    )
                val shelves = groupShelves(ranked, sources)
                shown(ranked)
                GuestFeedResult(videos = ranked, origins = origins, shelves = shelves)
            }

        /** Discover: popular videos from topics not yet liked, ranked without the taste. */
        suspend fun buildDiscover(
            n: Int = 40,
            skip: Set<String> = emptySet(),
            history: List<VideoHistoryEntry> = emptyList(),
            filters: suspend () -> HomeFeedCacheFilters = { HomeFeedCacheFilters() },
        ): List<Video> =
            coroutineScope {
                val taste = tasteStore.snapshot()
                val liked =
                    taste.kw.keys
                        .map { it.lowercase() }
                        .toSet()
                val fresh =
                    GuestTaste.starterTopics
                        .filter { it.lowercase() !in liked }
                        .shuffled(random)
                        .take(6)
                val cacheFilters = filters()
                val candidates =
                    fresh
                        .map { query ->
                            async {
                                pool("disc:$query", POOL_TTL_SEC) {
                                    search(query, FeedSource.DISCOVERY, query, seed = true)
                                }
                            }
                        }.map { it.await() }
                        .flatten()
                val ranked =
                    rankCandidates(
                        candidates = candidates,
                        n = n,
                        skip = skip,
                        taste = GuestTasteData(),
                        history = history.associateBy { it.videoId },
                        exclusions = cacheFilters.exclusions,
                        impressions = impressions,
                        bucket = GuestTaste.bucketNow(),
                        nowSec = System.currentTimeMillis() / 1000,
                        random = random,
                    )
                shown(ranked)
                ranked
            }

        /**
         * Neighbour channels: ones the related lists of two or more different watched channels
         * agree on — several of the viewer's channels agreeing on one means the same audience.
         */
        private fun neighbourChannels(
            historyById: Map<String, VideoHistoryEntry>,
            relatedBySeed: Map<String, List<Video>>,
            taste: GuestTasteData,
            subscribed: Set<String>,
            likedChannelIds: Set<String>,
        ): List<Pair<String, List<String>>> {
            val near = HashMap<String, MutableSet<String>>()
            for ((seedId, list) in relatedBySeed) {
                val seedChannel = historyById[seedId]?.channelId?.takeIf { it.isNotEmpty() } ?: continue
                for (video in list) {
                    if (video.channelId.isEmpty() || video.channelId == seedChannel) continue
                    near.getOrPut(video.channelId) { HashSet() } += seedChannel
                }
            }
            val channelNames =
                historyById.values.associate { it.channelId to it.channelName }
            return near.entries
                .filter { (channelId, from) ->
                    from.size >= 2 && channelId !in subscribed && channelId !in likedChannelIds &&
                        (taste.ch[channelId] ?: 0.0) > -1.0
                }.sortedByDescending { it.value.size }
                .take(3)
                .map { (channelId, from) ->
                    channelId to from.mapNotNull { channelNames[it] }.filter { it.isNotEmpty() }.distinct()
                }
        }

        private fun neighbourPointerTexts(neighbours: List<Pair<String, List<String>>>): Map<String, String> =
            neighbours
                .mapNotNull { (channelId, names) ->
                    when {
                        names.isEmpty() -> null
                        names.size == 1 -> channelId to names[0]
                        else -> channelId to "${names[0]} and ${names[1]}"
                    }
                }.toMap()

        /**
         * The first batch grouped by where it came from, so the feed reads as shelves
         * ("More like what you watched", "Picked for ...") instead of one flat list.
         */
        internal fun groupShelves(
            videos: List<Video>,
            sources: Map<String, Pair<FeedSource, String?>>,
        ): List<HomeShelf> {
            val batch = videos.take(45)
            val groups = LinkedHashMap<Pair<FeedSource, String?>, MutableList<Video>>()
            for (video in batch) {
                val (source, tag) = sources[video.id] ?: continue
                val key = source to if (source == FeedSource.DISCOVERY) tag else null
                groups.getOrPut(key) { ArrayList() } += video
            }
            return groups.filter { it.value.size >= 4 }.entries.take(6).map { (key, list) ->
                val (source, tag) = key
                HomeShelf(source, tag, list.take(12))
            }
        }

        /**
         * Scores every candidate by the taste — channel and keyword scores, the words favoured
         * at this time of the week, freshness and views — then deals them out with a per-channel
         * and per-topic cap, near-duplicate titles left out, and every fifth slot something new.
         */
        internal fun rankCandidates(
            candidates: List<Cand>,
            n: Int,
            skip: Set<String>,
            taste: GuestTasteData,
            history: Map<String, VideoHistoryEntry>,
            exclusions: io.github.shivytyahoo.glidetube.data.engagement.FeedExclusions,
            impressions: Map<String, Int>,
            bucket: String,
            nowSec: Long,
            random: Random,
        ): List<Video> {
            val hidden = skip
            val nowKw = taste.kwTime[bucket].orEmpty()
            val mature = history.size >= MATURE_HISTORY_SIZE
            val recent =
                history.values
                    .take(10)
                    .groupingBy { it.channelId }
                    .eachCount()
            val topicOf = HashMap<String, String?>()
            val best = HashMap<String, Pair<Double, Cand>>()
            val bonus =
                mapOf(
                    FeedSource.SUBS to 0.6,
                    FeedSource.RELATED to 0.5,
                    FeedSource.CHANNEL_MEMORY to 0.4,
                    FeedSource.NEIGHBOURS to 0.35,
                    FeedSource.DISCOVERY to 0.25,
                )
            for (candidate in candidates) {
                val video = candidate.video
                if (video.id in hidden || exclusions.hidesFromRecommendations(video)) continue
                var lower = 0.0
                val entry = history[video.id]
                if (entry != null) {
                    if (entry.duration > 0 && entry.position > entry.duration * 0.8) continue
                    if (entry.position <= 0) continue
                    if (entry.duration > 0 && entry.position > entry.duration * 0.15) lower += 0.2
                }
                val seenCount = impressions[video.id] ?: 0
                if (seenCount >= 5) continue
                lower +=
                    when {
                        seenCount >= 3 -> 0.8
                        seenCount >= 1 -> 0.3
                        else -> 0.0
                    }
                val recentCount = recent[video.channelId] ?: 0
                if (video.channelId.isNotEmpty()) {
                    if (recentCount >= 5) {
                        lower += 0.1
                    } else if (recentCount >= 2) {
                        lower -= 0.15
                    }
                }
                val tokens = GuestTaste.tokens(video.title, video.tags)
                val titleWords = GuestTaste.words(video.title).filter { it !in tokens }
                val count = (tokens.size + titleWords.size).coerceAtLeast(1).toDouble()
                val keywordScore =
                    (tokens.sumOf { taste.kw[it] ?: 0.0 } + 0.5 * titleWords.sumOf { taste.kw[it] ?: 0.0 }) / sqrt(count)
                val contextScore = (tokens + titleWords).sumOf { nowKw[it] ?: 0.0 } / sqrt(count)
                if (mature && candidate.seed && keywordScore <= 0.05 && (taste.ch[video.channelId] ?: 0.0) <= 0.0) {
                    lower += 0.35
                }
                topicOf[video.id] =
                    candidate.tag?.lowercase()
                        ?: (tokens + titleWords)
                            .filter { (taste.kw[it] ?: 0.0) > 0.5 }
                            .maxByOrNull { taste.kw[it] ?: 0.0 }
                val fresh =
                    video.timestampIsExact && video.timestamp > 0 &&
                        nowSec - video.timestamp / 1000 < FRESH_WINDOW_SEC
                val score =
                    (taste.ch[video.channelId] ?: 0.0) * 0.5 + 0.6 * keywordScore +
                        0.3 * contextScore.coerceIn(-2.0, 3.0) + (bonus[candidate.source] ?: 0.0) +
                        0.1 * log10(1.0 + video.viewCount) / 7 + (if (fresh) 0.25 else 0.0) +
                        gauss(random) * 0.2 - lower
                val old = best[video.id]
                best[video.id] =
                    if (old == null) {
                        score to candidate
                    } else {
                        (maxOf(old.first, score) + 0.15) to (if (score > old.first) candidate else old.second)
                    }
            }
            val ranked = best.values.sortedByDescending { it.first }
            val explore =
                ranked
                    .filter { it.second.source == FeedSource.DISCOVERY }
                    .map { it.second }
                    .shuffled(random)
                    .toMutableList()
            val out = ArrayList<Video>()
            val perChannel = HashMap<String, Int>()
            val ids = HashSet<String>()
            val titleWordsSeen = ArrayList<Set<String>>()
            val perTopic = HashMap<String, Int>()

            fun wordsOf(title: String) = GuestTaste.letterRuns(title.lowercase()).filter { it.length >= 2 }.toSet()

            fun alike(
                a: Set<String>,
                b: Set<String>,
            ): Boolean {
                if (a.size < 3 || b.size < 3) return false
                val both = a.count { it in b }
                return both.toDouble() / (a.size + b.size - both) >= 0.6
            }

            fun take(candidate: Cand): Boolean {
                val video = candidate.video
                if (video.id in ids || ((perChannel[video.channelId] ?: 0) >= 3 && video.channelId.isNotEmpty())) return false
                if ((taste.ch[video.channelId] ?: 0.0) <= -3) return false
                val words = wordsOf(video.title)
                if (titleWordsSeen.any { alike(it, words) }) return false
                val topic = topicOf[video.id]
                if (topic != null && out.size < 20 && (perTopic[topic] ?: 0) >= 3) return false
                if (topic != null) perTopic[topic] = (perTopic[topic] ?: 0) + 1
                titleWordsSeen += words
                out += video
                ids += video.id
                perChannel[video.channelId] = (perChannel[video.channelId] ?: 0) + 1
                return true
            }

            for ((_, candidate) in ranked) {
                if (out.size >= n) break
                if (out.size % 5 == 4) {
                    while (explore.isNotEmpty()) if (take(explore.removeAt(explore.size - 1))) break
                    if (out.size >= n) break
                }
                take(candidate)
            }
            return out
        }

        private fun gauss(random: Random): Double {
            val u = random.nextDouble().coerceAtLeast(1e-9)
            val v = random.nextDouble()
            return sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2 * PI * v)
        }
    }
