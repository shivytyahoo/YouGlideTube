package io.github.aedev.flow.data.subscriptions

import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.SessionHistory
import io.github.aedev.flow.data.shorts.finishedShortIds
import io.github.aedev.flow.innertube.YouTube
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What counts as watched for the Subscriptions tab and the Shorts shelves. Signed in, that is
 * the account's own YouTube history (server); this session's plays count too, so a video just
 * watched here hides at once instead of waiting for the server list to catch up.
 */
@Singleton
class SubscriptionWatchedVideos
    @Inject
    constructor(
        private val sessionHistory: SessionHistory,
        private val playerPreferences: PlayerPreferences,
    ) {
        /** Ids on the account's history pages, fetched once per collector; empty signed out. */
        private fun serverHistoryIds(): Flow<Set<String>> =
            flow {
                if (!YouTube.isSignedIn()) {
                    emit(emptySet())
                    return@flow
                }
                val ids = HashSet<String>()
                var continuation: String? = null
                var pages = 0
                do {
                    val page = YouTube.serverHistory(continuation).getOrNull() ?: break
                    page.videos.forEach { ids += it.id }
                    page.shorts.forEach { ids += it.id }
                    continuation = page.continuation
                    pages++
                } while (continuation != null && pages < 3)
                emit(ids)
            }

        val ids: Flow<Set<String>> =
            combine(
                sessionHistory.getVideoWatchProgress(),
                playerPreferences.hideWatchedVideosFromSubscriptions,
                playerPreferences.watchedThreshold,
                serverHistoryIds(),
            ) { history, hideWatched, threshold, serverIds ->
                if (!hideWatched) return@combine emptySet<String>()
                val sessionWatched =
                    history
                        .asSequence()
                        .filter { threshold.isWatched(it.position, it.duration) }
                        .map { it.videoId }
                        .toHashSet()
                serverIds + sessionWatched
            }.distinctUntilChanged()

        /** Watched reels, for the Shorts shelf and the Shorts queues; governed by the Shorts setting alone. */
        val shortIds: Flow<Set<String>> =
            combine(
                sessionHistory.getVideoWatchProgress(),
                playerPreferences.hideWatchedShorts,
                serverHistoryIds(),
            ) { history, hideWatched, serverIds ->
                if (hideWatched) serverIds + history.finishedShortIds() else emptySet()
            }.distinctUntilChanged()
    }
