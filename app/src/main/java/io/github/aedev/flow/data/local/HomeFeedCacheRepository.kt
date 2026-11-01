package io.github.aedev.flow.data.local

import android.content.Context
import io.github.aedev.flow.data.engagement.FeedExclusions
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.model.VideoCollaborator
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One last-feed video row on disk; the transient/search-only fields of [Video] are left out. */
@Serializable
private data class DiskVideo(
    val id: String,
    val title: String,
    val channelName: String,
    val channelId: String,
    val thumbnailUrl: String,
    val duration: Int,
    val viewCount: Long,
    val likeCount: Long = 0,
    val uploadDate: String,
    val timestamp: Long = System.currentTimeMillis(),
    val timestampIsExact: Boolean = false,
    val description: String = "",
    val channelThumbnailUrl: String = "",
    val tags: List<String> = emptyList(),
    val category: String? = null,
    val isMusic: Boolean = false,
    val isLive: Boolean = false,
    val isShort: Boolean = false,
    val isUpcoming: Boolean = false,
    val isScheduledLive: Boolean = false,
    val membersOnlyText: String? = null,
    val commentCountText: String = "",
    val channelThumbnailUrls: List<String> = emptyList(),
    val collaborators: List<VideoCollaborator> = emptyList(),
    val isVerifiedChannel: Boolean = false,
    val badges: List<String> = emptyList(),
)

@Serializable
private data class DiskFeedRow(
    val video: DiskVideo,
    val orderIndex: Int,
    val cachedAt: Long,
    val expiresAt: Long,
)

private fun Video.toDiskVideo(): DiskVideo =
    DiskVideo(
        id = id,
        title = title,
        channelName = channelName,
        channelId = channelId,
        thumbnailUrl = thumbnailUrl,
        duration = duration,
        viewCount = viewCount,
        likeCount = likeCount,
        uploadDate = uploadDate,
        timestamp = timestamp,
        timestampIsExact = timestampIsExact,
        description = description,
        channelThumbnailUrl = channelThumbnailUrl,
        tags = tags,
        category = category,
        isMusic = isMusic,
        isLive = isLive,
        isShort = isShort,
        isUpcoming = isUpcoming,
        isScheduledLive = isScheduledLive,
        membersOnlyText = membersOnlyText,
        commentCountText = commentCountText,
        channelThumbnailUrls = channelThumbnailUrls,
        collaborators = collaborators,
        isVerifiedChannel = isVerifiedChannel,
        badges = badges,
    )

private fun DiskVideo.toVideo(): Video =
    Video(
        id = id,
        title = title,
        channelName = channelName,
        channelId = channelId,
        thumbnailUrl = thumbnailUrl,
        duration = duration,
        viewCount = viewCount,
        likeCount = likeCount,
        uploadDate = uploadDate,
        timestamp = timestamp,
        timestampIsExact = timestampIsExact,
        description = description,
        channelThumbnailUrl = channelThumbnailUrl,
        tags = tags,
        category = category,
        isMusic = isMusic,
        isLive = isLive,
        isShort = isShort,
        isUpcoming = isUpcoming,
        isScheduledLive = isScheduledLive,
        membersOnlyText = membersOnlyText,
        commentCountText = commentCountText,
        channelThumbnailUrls = channelThumbnailUrls,
        collaborators = collaborators,
        isVerifiedChannel = isVerifiedChannel,
        badges = badges,
    )

data class CachedHomeVideo(
    val video: Video,
    val source: String,
    val relatedSeedId: String? = null,
)

data class HomeFeedCacheFilters(
    val watchedVideoIds: Set<String> = emptySet(),
    val exclusions: FeedExclusions = FeedExclusions.NONE,
)

internal fun filterCachedHomeVideos(
    items: List<CachedHomeVideo>,
    filters: HomeFeedCacheFilters,
): List<CachedHomeVideo> =
    items.filter { item ->
        item.video.id !in filters.watchedVideoIds && !filters.exclusions.hidesFromRecommendations(item.video)
    }

internal fun selectReservePageFromCache(
    items: List<CachedHomeVideo>,
    maxRelated: Int = 4,
    maxDiscovery: Int = 4,
): List<CachedHomeVideo> {
    val related = items.filter { it.source == HomeFeedCacheRepository.SOURCE_RELATED }.take(maxRelated)
    val discovery = items.filter { it.source == HomeFeedCacheRepository.SOURCE_DISCOVERY }.take(maxDiscovery)
    return related + discovery
}

/**
 * The home/player feed cache. Buckets live in memory with the same caps and TTLs the old
 * Room table had — except the last feed, which is also written to disk, so a signed-out
 * home still has something to show after a restart instead of an empty landing. The disk
 * copy outlives the 8h memory TTL (7 days); the network refresh after hydrate replaces it.
 */
class HomeFeedCacheRepository(
    private val context: Context,
) {
    private val diskJson = Json { ignoreUnknownKeys = true }
    private val diskFile: File
        get() = File(context.filesDir, "guest_home_feed.json")

    private fun readDiskRows(): List<DiskFeedRow> =
        runCatching {
            val file = diskFile
            if (!file.exists()) return emptyList()
            diskJson.decodeFromString<List<DiskFeedRow>>(file.readText())
        }.getOrElse { emptyList() }

    private suspend fun writeDiskRows(rows: List<DiskFeedRow>) {
        runCatching {
            withContext(Dispatchers.IO) {
                if (rows.isEmpty()) {
                    diskFile.delete()
                } else {
                    diskFile.writeText(diskJson.encodeToString(rows))
                }
            }
        }
    }
    private data class Row(
        val cacheKey: String,
        val bucket: String,
        val video: Video,
        val source: String,
        val relatedSeedId: String?,
        val orderIndex: Int,
        val cachedAt: Long,
        val expiresAt: Long,
    )

    private val lock = Any()
    private val rows = ArrayList<Row>()

    private fun Row.toCachedHomeVideo() = CachedHomeVideo(video = video, source = source, relatedSeedId = relatedSeedId)

    private fun keyOf(
        bucket: String,
        source: String,
        relatedSeedId: String?,
        videoId: String,
    ): String = "$bucket|$source|${relatedSeedId.orEmpty()}|$videoId"

    private fun deleteExpiredLocked(now: Long) {
        rows.removeAll { it.expiresAt <= now }
    }

    private fun insertLocked(newRows: List<Row>) {
        val keys = newRows.mapTo(HashSet()) { it.cacheKey }
        rows.removeAll { it.cacheKey in keys }
        rows.addAll(newRows)
    }

    suspend fun loadLastFeed(
        filters: HomeFeedCacheFilters,
        now: Long = System.currentTimeMillis(),
    ): List<Video> {
        val inMemory =
            synchronized(lock) {
                deleteExpiredLocked(now)
                filterCachedHomeVideos(
                    rows
                        .filter { it.bucket == BUCKET_LAST_FEED && it.expiresAt > now }
                        .sortedBy { it.orderIndex }
                        .map { it.toCachedHomeVideo() },
                    filters,
                ).map { it.video }
            }
        if (inMemory.isNotEmpty()) return inMemory
        // Cold start: the memory bucket is empty, so fall back to the disk copy and refill
        // memory from it, keeping the same filtering and delete semantics as a live feed.
        val diskRows =
            withContext(Dispatchers.IO) { readDiskRows() }
                .filter { it.expiresAt > now }
                .sortedBy { it.orderIndex }
        if (diskRows.isEmpty()) return emptyList()
        val videos = diskRows.map { it.video.toVideo() }
        synchronized(lock) {
            insertLocked(
                diskRows.map { row ->
                    Row(
                        cacheKey = keyOf(BUCKET_LAST_FEED, SOURCE_LAST_FEED, null, row.video.id),
                        bucket = BUCKET_LAST_FEED,
                        video = row.video.toVideo(),
                        source = SOURCE_LAST_FEED,
                        relatedSeedId = null,
                        orderIndex = row.orderIndex,
                        cachedAt = row.cachedAt,
                        expiresAt = row.expiresAt,
                    )
                },
            )
        }
        return filterCachedHomeVideos(
            videos.map { CachedHomeVideo(video = it, source = SOURCE_LAST_FEED) },
            filters,
        ).map { it.video }
    }

    suspend fun saveLastFeed(
        videos: List<Video>,
        now: Long = System.currentTimeMillis(),
    ) {
        val capped = videos.take(LAST_FEED_CAP)
        synchronized(lock) {
            rows.removeAll { it.bucket == BUCKET_LAST_FEED }
            insertLocked(
                capped.mapIndexed { index, video ->
                    Row(
                        cacheKey = keyOf(BUCKET_LAST_FEED, SOURCE_LAST_FEED, null, video.id),
                        bucket = BUCKET_LAST_FEED,
                        video = video,
                        source = SOURCE_LAST_FEED,
                        relatedSeedId = null,
                        orderIndex = index,
                        cachedAt = now,
                        expiresAt = now + LAST_FEED_TTL_MS,
                    )
                },
            )
        }
        writeDiskRows(
            capped.mapIndexed { index, video ->
                DiskFeedRow(
                    video = video.toDiskVideo(),
                    orderIndex = index,
                    cachedAt = now,
                    expiresAt = now + DISK_LAST_FEED_TTL_MS,
                )
            },
        )
    }

    suspend fun loadReservePage(
        filters: HomeFeedCacheFilters,
        now: Long = System.currentTimeMillis(),
        maxRelated: Int = 4,
        maxDiscovery: Int = 4,
    ): List<CachedHomeVideo> =
        synchronized(lock) {
            deleteExpiredLocked(now)
            val freshReserve =
                rows
                    .filter { it.bucket == BUCKET_RESERVE && it.expiresAt > now }
                    .sortedWith(compareByDescending<Row> { it.cachedAt }.thenBy { it.orderIndex })
                    .take(RESERVE_CAP)
                    .map { it.toCachedHomeVideo() }
            selectReservePageFromCache(
                filterCachedHomeVideos(freshReserve, filters),
                maxRelated = maxRelated,
                maxDiscovery = maxDiscovery,
            )
        }

    suspend fun saveReserve(
        items: List<CachedHomeVideo>,
        now: Long = System.currentTimeMillis(),
    ) {
        if (items.isEmpty()) return
        synchronized(lock) {
            insertLocked(
                items
                    .distinctBy { it.video.id }
                    .take(RESERVE_CAP)
                    .mapIndexed { index, item ->
                        Row(
                            cacheKey = keyOf(BUCKET_RESERVE, item.source, item.relatedSeedId, item.video.id),
                            bucket = BUCKET_RESERVE,
                            video = item.video,
                            source = item.source,
                            relatedSeedId = item.relatedSeedId,
                            orderIndex = index,
                            cachedAt = now,
                            expiresAt = now + RESERVE_TTL_MS,
                        )
                    },
            )
            val keep =
                rows
                    .filter { it.bucket == BUCKET_RESERVE }
                    .sortedWith(compareByDescending<Row> { it.cachedAt }.thenBy { it.orderIndex })
                    .take(RESERVE_CAP)
                    .mapTo(HashSet()) { it.cacheKey }
            rows.removeAll { it.bucket == BUCKET_RESERVE && it.cacheKey !in keep }
        }
    }

    suspend fun loadRelated(
        seedId: String,
        filters: HomeFeedCacheFilters,
        now: Long = System.currentTimeMillis(),
    ): List<Video> =
        synchronized(lock) {
            deleteExpiredLocked(now)
            filterCachedHomeVideos(
                rows
                    .filter { it.bucket == BUCKET_RELATED && it.relatedSeedId == seedId && it.expiresAt > now }
                    .sortedBy { it.orderIndex }
                    .map { it.toCachedHomeVideo() },
                filters,
            ).map { it.video }
        }

    suspend fun saveRelated(
        seedId: String,
        videos: List<Video>,
        now: Long = System.currentTimeMillis(),
    ) {
        if (seedId.isBlank() || videos.isEmpty()) return
        synchronized(lock) {
            rows.removeAll { it.bucket == BUCKET_RELATED && it.relatedSeedId == seedId }
            insertLocked(
                videos.take(RELATED_PER_SEED_CAP).mapIndexed { index, video ->
                    Row(
                        cacheKey = keyOf(BUCKET_RELATED, SOURCE_RELATED, seedId, video.id),
                        bucket = BUCKET_RELATED,
                        video = video,
                        source = SOURCE_RELATED,
                        relatedSeedId = seedId,
                        orderIndex = index,
                        cachedAt = now,
                        expiresAt = now + RELATED_TTL_MS,
                    )
                },
            )
            // Keep only the newest RELATED_SEED_CAP seeds by their latest cachedAt.
            val seedRecency =
                rows
                    .filter { it.bucket == BUCKET_RELATED && it.relatedSeedId != null }
                    .groupBy { it.relatedSeedId!! }
                    .mapValues { (_, seedRows) -> seedRows.maxOf { it.cachedAt } }
            val keepSeeds =
                seedRecency.entries
                    .sortedByDescending { it.value }
                    .take(RELATED_SEED_CAP)
                    .mapTo(HashSet()) { it.key }
            rows.removeAll { it.bucket == BUCKET_RELATED && it.relatedSeedId !in keepSeeds }
        }
    }

    /**
     * Metadata fetched by id for videos known only from history (views, upload date, avatar), so a
     * card fetched once stays complete for a week without asking again.
     */
    suspend fun loadVideoMetadata(
        ids: Collection<String>,
        now: Long = System.currentTimeMillis(),
    ): Map<String, Video> {
        if (ids.isEmpty()) return emptyMap()
        val wanted = ids.toHashSet()
        return synchronized(lock) {
            rows
                .filter { it.bucket == BUCKET_VIDEO_META && it.expiresAt > now && it.video.id in wanted }
                .associate { it.video.id to it.video }
        }
    }

    suspend fun saveVideoMetadata(
        videos: List<Video>,
        now: Long = System.currentTimeMillis(),
    ) {
        if (videos.isEmpty()) return
        synchronized(lock) {
            insertLocked(
                videos.distinctBy { it.id }.map { video ->
                    Row(
                        cacheKey = keyOf(BUCKET_VIDEO_META, BUCKET_VIDEO_META, null, video.id),
                        bucket = BUCKET_VIDEO_META,
                        video = video,
                        source = BUCKET_VIDEO_META,
                        relatedSeedId = null,
                        orderIndex = 0,
                        cachedAt = now,
                        expiresAt = now + VIDEO_META_TTL_MS,
                    )
                },
            )
            val keep =
                rows
                    .filter { it.bucket == BUCKET_VIDEO_META }
                    .sortedByDescending { it.cachedAt }
                    .take(VIDEO_META_CAP)
                    .mapTo(HashSet()) { it.cacheKey }
            rows.removeAll { it.bucket == BUCKET_VIDEO_META && it.cacheKey !in keep }
        }
    }

    suspend fun deleteVideo(videoId: String) {
        if (videoId.isNotBlank()) {
            synchronized(lock) { rows.removeAll { it.video.id == videoId } }
            val remaining =
                withContext(Dispatchers.IO) { readDiskRows() }.filter { it.video.id != videoId }
            writeDiskRows(remaining)
        }
    }

    /**
     * Removes served reserve rows so a later refresh cannot re-serve them.
     * Without this, reserve rows stayed eligible for their full 12h TTL and
     * reappeared after every pull-to-refresh.
     */
    suspend fun consumeReserve(videoIds: Collection<String>) {
        if (videoIds.isNotEmpty()) {
            val ids = videoIds.toHashSet()
            synchronized(lock) { rows.removeAll { it.bucket == BUCKET_RESERVE && it.video.id in ids } }
        }
    }

    /** The reel feed's unserved lane tails: one bucket, replaced whole on every save. */
    suspend fun saveShortsReserve(
        items: List<CachedHomeVideo>,
        now: Long = System.currentTimeMillis(),
    ) {
        synchronized(lock) {
            rows.removeAll { it.bucket == BUCKET_SHORTS_RESERVE }
            if (items.isEmpty()) return
            insertLocked(
                items
                    .distinctBy { it.video.id }
                    .take(SHORTS_RESERVE_CAP)
                    .mapIndexed { index, item ->
                        Row(
                            cacheKey = keyOf(BUCKET_SHORTS_RESERVE, item.source, item.relatedSeedId, item.video.id),
                            bucket = BUCKET_SHORTS_RESERVE,
                            video = item.video,
                            source = item.source,
                            relatedSeedId = item.relatedSeedId,
                            orderIndex = index,
                            cachedAt = now,
                            expiresAt = now + SHORTS_RESERVE_TTL_MS,
                        )
                    },
            )
        }
    }

    suspend fun loadShortsReserve(
        filters: HomeFeedCacheFilters,
        now: Long = System.currentTimeMillis(),
    ): List<CachedHomeVideo> =
        synchronized(lock) {
            deleteExpiredLocked(now)
            filterCachedHomeVideos(
                rows
                    .filter { it.bucket == BUCKET_SHORTS_RESERVE && it.expiresAt > now }
                    .sortedBy { it.orderIndex }
                    .map { it.toCachedHomeVideo() },
                filters,
            )
        }

    suspend fun deleteChannel(channelId: String) {
        if (channelId.isNotBlank()) {
            synchronized(lock) { rows.removeAll { it.video.channelId == channelId } }
            val remaining =
                withContext(Dispatchers.IO) { readDiskRows() }.filter { it.video.channelId != channelId }
            writeDiskRows(remaining)
        }
    }

    suspend fun clearAll() {
        synchronized(lock) { rows.clear() }
        writeDiskRows(emptyList())
    }

    /** Channel avatar URLs seen on cached videos, for surfaces that only hold a channel id. */
    fun channelAvatars(channelIds: List<String>): Map<String, String> {
        val wanted = channelIds.toHashSet()
        return synchronized(lock) {
            rows
                .filter { it.video.channelId in wanted && it.video.channelThumbnailUrl.isNotBlank() }
                .associate { it.video.channelId to it.video.channelThumbnailUrl }
        }
    }

    companion object {
        const val SOURCE_SUBS = "SUBS"
        const val SOURCE_RELATED = "RELATED"
        const val SOURCE_DISCOVERY = "DISCOVERY"
        const val SOURCE_LAST_FEED = "LAST_FEED"

        private const val BUCKET_LAST_FEED = "LAST_FEED"
        private const val BUCKET_RESERVE = "RESERVE"
        private const val BUCKET_RELATED = "RELATED"
        private const val BUCKET_SHORTS_RESERVE = "SHORTS_RESERVE"
        private const val BUCKET_VIDEO_META = "VIDEO_META"
        private const val VIDEO_META_CAP = 600
        private const val VIDEO_META_TTL_MS = 7L * 24L * 60L * 60L * 1000L

        private const val LAST_FEED_CAP = 60
        private const val RESERVE_CAP = 200
        private const val RELATED_PER_SEED_CAP = 20
        private const val RELATED_SEED_CAP = 50
        private const val SHORTS_RESERVE_CAP = 120

        private const val LAST_FEED_TTL_MS = 8L * 60L * 60L * 1000L
        private const val DISK_LAST_FEED_TTL_MS = 7L * 24L * 60L * 60L * 1000L
        private const val RESERVE_TTL_MS = 12L * 60L * 60L * 1000L
        private const val RELATED_TTL_MS = 90L * 60L * 1000L
        private const val SHORTS_RESERVE_TTL_MS = 12L * 60L * 60L * 1000L
    }
}
