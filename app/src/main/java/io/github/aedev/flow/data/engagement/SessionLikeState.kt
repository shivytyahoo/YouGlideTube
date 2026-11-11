package io.github.aedev.flow.data.engagement

import io.github.aedev.flow.utils.ShivLog

import io.github.aedev.flow.data.account.AccountPlaylistIds
import io.github.aedev.flow.data.local.LikedVideoInfo
import io.github.aedev.flow.data.repository.YouTubePlaylistRepository
import io.github.aedev.flow.innertube.YouTube
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Like/dislike state for this session. Likes are account data now: a like writes through to the
 * account (and an unlike removes it), and on first use the state is seeded from the account's
 * Liked videos / Liked music lists so a video liked on another device shows as liked here.
 * Dislikes have no account endpoint in the InnerTube surface this app uses, so a dislike is
 * session-only. Nothing here touches disk.
 */
@Singleton
class SessionLikeState
    @Inject
    constructor(
        private val youTubePlaylists: YouTubePlaylistRepository,
    ) {
        private val states = MutableStateFlow<Map<String, String>>(emptyMap())
        private val infos = MutableStateFlow<List<LikedVideoInfo>>(emptyList())

        @Volatile
        private var seeded = false

        /** Clears the session's likes so the next use re-seeds from the (new) active account. */
        fun resetForAccountSwitch() {
            states.value = emptyMap()
            infos.value = emptyList()
            seeded = false
        }

        /** Seeds LIKED states from the account's liked lists, once per session. */
        suspend fun ensureSeeded() {
            if (seeded || !YouTube.isSignedIn()) return
            val likedIds = HashSet<String>()
            val videos = youTubePlaylists.complete(AccountPlaylistIds.LIKED_VIDEOS)
            val music = youTubePlaylists.complete(AccountPlaylistIds.LIKED_MUSIC)
            videos?.videos?.forEach { likedIds += it.id }
            music?.videos?.forEach { likedIds += it.id }
            // The like itself is server-accepted (videoLike status=200 on device), so a like
            // that "disappears" on reopen is decided here: what the account's lists hand back.
            ShivLog.i("likeSeed videos=${videos?.videos?.size} music=${music?.videos?.size} total=${likedIds.size}",
            )
            if (likedIds.isNotEmpty()) {
                states.value = states.value + likedIds.associateWith { LIKED }
            }
            // The infos list is what the Liked pages read, and it was never seeded: a fresh
            // process opened Liked music to 0 songs while the Library shelf (which reads the
            // same LM list directly) showed them. Seed it from the same two lists, with the
            // Library's split — LM Shorts/live are liked videos, the rest are liked music —
            // so both surfaces agree. likedAt keeps the server's newest-first order.
            val now = System.currentTimeMillis()
            val videoInfos =
                ((videos?.videos.orEmpty()) + (music?.videos.orEmpty().filter { it.isShort || it.isLive })).distinctBy { it.id }.mapIndexed { index, video ->
                    LikedVideoInfo(
                        videoId = video.id,
                        title = video.title,
                        thumbnail = video.thumbnailUrl,
                        channelName = video.channelName,
                        channelId = video.channelId.takeIf(String::isNotBlank),
                        durationSeconds = video.duration,
                        likedAt = now - index,
                    )
                }
            val musicOffset = videoInfos.size
            val musicInfos =
                (music?.videos.orEmpty().filterNot { it.isShort || it.isLive }).distinctBy { it.id }.mapIndexed { index, video ->
                    LikedVideoInfo(
                        videoId = video.id,
                        title = video.title,
                        thumbnail = video.thumbnailUrl,
                        channelName = video.channelName,
                        channelId = video.channelId.takeIf(String::isNotBlank),
                        durationSeconds = video.duration,
                        likedAt = now - musicOffset - index,
                        isMusic = true,
                    )
                }
            val seededInfos = videoInfos + musicInfos
            if (seededInfos.isNotEmpty()) {
                val known = infos.value.map { it.videoId }.toSet()
                infos.value = infos.value + seededInfos.filterNot { it.videoId in known }
            }
            // Only mark seeded when at least one list arrived: a failed walk must retry on
            // the next open instead of leaving the Liked pages empty for the whole process.
            if (videos != null || music != null) seeded = true
        }

        fun likeState(videoId: String): Flow<String?> = states.map { it[videoId] }

        fun likedInfos(): Flow<List<LikedVideoInfo>> = infos

        /** Ids this session disliked, so a stale server liked list cannot resurrect them. */
        fun dislikedIds(): Flow<Set<String>> = states.map { it.filterValues { v -> v == DISLIKED }.keys }

        fun likedVideoInfos(): Flow<List<LikedVideoInfo>> = infos.map { list -> list.filter { !it.isMusic } }

        fun likedMusicInfos(): Flow<List<LikedVideoInfo>> = infos.map { list -> list.filter { it.isMusic } }

        suspend fun dislikedVideoIds(): Set<String> =
            states.value.filterValues { it == DISLIKED }.keys

        suspend fun like(info: LikedVideoInfo) {
            ensureSeeded()
            states.value = states.value + (info.videoId to LIKED)
            val existing = infos.value.firstOrNull { it.videoId == info.videoId }
            val merged = existing?.let(info::keepingDetailsOf) ?: info
            infos.value = listOf(merged) + infos.value.filterNot { it.videoId == info.videoId }
            if (YouTube.isSignedIn()) YouTube.likeVideo(info.videoId, true)
        }

        suspend fun dislike(videoId: String) {
            states.value = states.value + (videoId to DISLIKED)
            infos.value = infos.value.filterNot { it.videoId == videoId }
            // No account dislike endpoint exists here; disliking at least removes the account like.
            if (YouTube.isSignedIn()) YouTube.likeVideo(videoId, false)
        }

        suspend fun removeLikeState(videoId: String) {
            states.value = states.value - videoId
            infos.value = infos.value.filterNot { it.videoId == videoId }
            if (YouTube.isSignedIn()) YouTube.likeVideo(videoId, false)
        }

        /** Removes [videoIds] from the session likes; returns what [restoreLikes] needs to undo it. */
        suspend fun takeLikes(videoIds: Collection<String>): List<LikedVideoInfo> {
            val ids = videoIds.toSet()
            val taken = infos.value.filter { it.videoId in ids }
            infos.value = infos.value.filterNot { it.videoId in ids }
            states.value = states.value - ids
            return taken
        }

        suspend fun restoreLikes(likes: List<LikedVideoInfo>) {
            likes.forEach { like(it) }
        }

        companion object {
            const val LIKED = "LIKED"
            const val DISLIKED = "DISLIKED"
        }
    }
