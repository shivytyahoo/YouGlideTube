package io.github.shivytyahoo.glidetube.data.video

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.shivytyahoo.glidetube.data.local.dao.DownloadDao
import io.github.shivytyahoo.glidetube.data.local.entity.DownloadItemStatus
import io.github.shivytyahoo.glidetube.data.subscriptions.SubscriptionFeedTimestamps
import io.github.shivytyahoo.glidetube.innertube.YouTube
import io.github.shivytyahoo.glidetube.innertube.pages.channel.ChannelTabKind
import io.github.shivytyahoo.glidetube.innertube.pages.renderer.FeedItemOwner
import io.github.shivytyahoo.glidetube.innertube.pages.renderer.videoOrNull
import io.github.shivytyahoo.glidetube.utils.NetworkState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * The per-channel "Save new videos" runner: shortly after launch, then every 30 minutes, it
 * reads each opted-in channel's Videos tab and queues what was published after the opt-in —
 * at most three per channel per pass, never a Short, a live stream or a members-only upload, and
 * never anything already saved or downloading. A metered connection skips the whole pass.
 */
class ChannelAutoDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    /** What the worker needs from the app graph; it is created by WorkManager, not by Hilt. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun channelAutoDownloadStore(): ChannelAutoDownloadStore

        fun backgroundDownloadQueuer(): BackgroundDownloadQueuer

        fun downloadDao(): DownloadDao
    }

    private val dependencies by lazy { EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java) }

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            if (NetworkState.isMetered(applicationContext)) {
                Log.d(TAG, "Metered connection, skipping channel auto-download pass")
                return@withContext Result.success()
            }
            try {
                val optedIn = dependencies.channelAutoDownloadStore().optedIn.first()
                if (optedIn.isEmpty()) {
                    cancel(applicationContext)
                    return@withContext Result.success()
                }
                val queuer = dependencies.backgroundDownloadQueuer()
                val dao = dependencies.downloadDao()
                val now = System.currentTimeMillis()
                var queued = 0
                for ((channelId, epoch) in optedIn) {
                    runCatching { downloadNewForChannel(channelId, epoch, now, queuer, dao) }
                        .onSuccess { queued += it }
                        .onFailure { Log.w(TAG, "$channelId: channel pass failed", it) }
                }
                Log.d(TAG, "Channel auto-download pass queued $queued")
                Result.success()
            } catch (e: Exception) {
                Log.w(TAG, "Channel auto-download pass failed", e)
                Result.retry()
            }
        }

    private suspend fun downloadNewForChannel(
        channelId: String,
        optInEpochMs: Long,
        now: Long,
        queuer: BackgroundDownloadQueuer,
        dao: DownloadDao,
    ): Int {
        val landing = YouTube.channelLanding(channelId).getOrNull() ?: return 0
        val videosTab = landing.tabs.firstOrNull { it.kind == ChannelTabKind.Videos } ?: return 0
        val params = videosTab.params ?: ChannelTabKind.Videos.defaultParams ?: return 0
        val content =
            YouTube
                .channelTab(channelId, params, FeedItemOwner(id = channelId), ChannelTabKind.Videos)
                .getOrNull() ?: return 0
        val candidates =
            content.items
                .mapNotNull { it.videoOrNull() }
                .filter { video ->
                    !video.isShort &&
                        !video.isLive &&
                        !video.isUpcoming &&
                        video.membersOnlyText == null &&
                        SubscriptionFeedTimestamps.effectiveUploadTimestamp(video, now) > optInEpochMs
                }.sortedByDescending { SubscriptionFeedTimestamps.effectiveUploadTimestamp(it, now) }
                .take(MAX_PER_CHANNEL_PER_PASS)
        var queued = 0
        for (video in candidates) {
            val existing = dao.getDownloadWithItems(video.id)?.overallStatus
            if (existing != null && existing != DownloadItemStatus.FAILED && existing != DownloadItemStatus.CANCELLED) continue
            if (queuer.queue(video) == QueueOutcome.QUEUED) queued++
        }
        return queued
    }

    companion object {
        const val WORK_NAME = "channel_auto_download_work"
        private const val TAG = "ChannelAutoDownloadWorker"

        /** At most this many new videos per channel per pass, so one prolific channel cannot flood the queue. */
        private const val MAX_PER_CHANNEL_PER_PASS = 3
        private const val INTERVAL_MINUTES = 30L
        private const val INITIAL_DELAY_SECONDS = 20L

        /** Schedules the pass; KEEP, so re-scheduling never resets the timer. */
        fun schedule(context: Context) {
            val constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            val workRequest =
                PeriodicWorkRequestBuilder<ChannelAutoDownloadWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                    .setInitialDelay(INITIAL_DELAY_SECONDS, TimeUnit.SECONDS)
                    .setConstraints(constraints)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        WorkRequest.MIN_BACKOFF_MILLIS,
                        TimeUnit.MILLISECONDS,
                    ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest,
            )
            Log.d(TAG, "Scheduled channel auto-download every $INTERVAL_MINUTES minutes")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled channel auto-download")
        }
    }
}
