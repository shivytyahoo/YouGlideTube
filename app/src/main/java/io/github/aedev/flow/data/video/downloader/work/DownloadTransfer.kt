package io.github.aedev.flow.data.video.downloader.work

import android.util.Log
import io.github.aedev.flow.data.local.entity.DownloadItemStatus
import io.github.aedev.flow.data.video.DownloadProgressUpdate
import io.github.aedev.flow.data.video.VideoDownloadManager
import io.github.aedev.flow.data.video.downloader.request.DownloadRequest
import io.github.aedev.flow.data.video.downloader.resolve.DownloadStreamResolver
import io.github.aedev.flow.data.video.downloader.resolve.ResolveOutcome
import io.github.aedev.flow.data.video.downloader.resolve.ResolvedStreams
import io.github.aedev.flow.data.video.downloader.transfer.RangeDownloader
import io.github.aedev.flow.data.video.downloader.transfer.SegmentClipFetcher
import io.github.aedev.flow.data.video.downloader.transfer.SegmentFetchSession
import io.github.aedev.flow.data.video.downloader.transfer.StreamRole
import io.github.aedev.flow.data.video.downloader.transfer.TransferFailure
import io.github.aedev.flow.data.video.downloader.transfer.TransferJob
import io.github.aedev.flow.data.video.downloader.transfer.TransferResult
import io.github.aedev.flow.data.video.downloader.transfer.TransferState
import io.github.aedev.flow.data.video.downloader.transfer.TransferStream
import io.github.aedev.flow.innertube.models.YouTubeClient
import io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format
import io.github.aedev.flow.player.error.StreamDenialClassifier
import io.github.aedev.flow.player.sabr.integration.SabrDownloadEngine
import io.github.aedev.flow.player.stream.ClientGateTracker
import io.github.aedev.flow.player.stream.InnerTubeVideoStreamExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Why a download's bytes could not be fetched. */
enum class FetchFailure {
    UNAVAILABLE,
    NO_COMPATIBLE_AUDIO,
    REFUSED,
    LENGTH_UNKNOWN,
    NETWORK,
    DISK_FULL,
}

sealed interface FetchOutcome {
    data class Fetched(
        val videoPart: File?,
        val audioPart: File,
        val durationMs: Long,
        /** The streams it came from, for the quality the row shows; null after a SABR fallback. */
        val streams: ResolvedStreams? = null,
        /**
         * True when the parts came from the segment fetcher: init + only the clip's segments.
         * Their indexes describe the original stream, not the part files, so the remux must not
         * seek in them.
         */
        val segmentClip: Boolean = false,
        /** The clip's true snapped start in microseconds, from the segment index; null unless segmentClip. */
        val snappedStartUs: Long? = null,
        /** Each stream's own first-selected segment start, from the segment index; empty unless segmentClip. */
        val snappedStartUsByRole: Map<StreamRole, Long> = emptyMap(),
        /** Every audio part file, primary first; a single-audio download is just [audioPart]. */
        val audioParts: List<File> = listOf(audioPart),
        /** Each audio part's own first-selected segment start, aligned with [audioParts]; 0 unless segmentClip. */
        val audioBaseUs: List<Long> = emptyList(),
    ) : FetchOutcome

    data class Failed(
        val reason: FetchFailure,
        val detail: String? = null,
    ) : FetchOutcome
}

/**
 * Gets a request's streams into staging. A refused URL is read the way playback reads it: an
 * expired one is simply re-minted and the transfer continues where it stopped; an unattested or
 * token-refused client is reported to [ClientGateTracker] so the next extraction skips it. A stream
 * refused twice is dropped for another one, and SABR is tried only once direct URLs are exhausted.
 */
@Singleton
class DownloadTransfer
    @Inject
    constructor(
        private val resolver: DownloadStreamResolver,
        private val rangeDownloader: RangeDownloader,
        private val segmentClipFetcher: SegmentClipFetcher,
        private val downloads: VideoDownloadManager,
        private val notifier: DownloadNotifier,
    ) {
        internal suspend fun fetch(
            request: DownloadRequest,
            staging: DownloadStaging,
            itemId: Int,
            threads: Int,
        ): FetchOutcome {
            var avoid = emptySet<Int>()
            val refusals = mutableMapOf<Int, Int>()
            repeat(MAX_RESOLVES) {
                val streams =
                    when (val resolved = resolver.resolve(request, avoid)) {
                        is ResolveOutcome.Resolved -> resolved.streams
                        ResolveOutcome.Unavailable -> return FetchOutcome.Failed(FetchFailure.UNAVAILABLE)
                        ResolveOutcome.NoCompatibleAudio -> return FetchOutcome.Failed(FetchFailure.NO_COMPATIBLE_AUDIO)
                    }
                val video = streams.video
                // Multi-audio: every track the resolver kept, primary first; a single-audio
                // download is just the pick. The parts and bases below are aligned with it.
                val audioTracks = streams.audioTracks.ifEmpty { listOf(streams.audio) }
                val audioParts = staging.audioParts(audioTracks.size)
                var segmentUsed = false
                var snappedStartUs: Long? = null
                var snappedStartUsByRole: Map<StreamRole, Long> = emptyMap()
                var audioBaseUs: List<Long> = emptyList()
                val result =
                    if (request.isClip) {
                        val attempt = runClipAttempt(request, streams, audioTracks, staging, itemId, threads)
                        segmentUsed = attempt.result != null
                        if (segmentUsed) {
                            snappedStartUs = attempt.snappedStartUs
                            snappedStartUsByRole = attempt.snappedStartUsByRole
                            audioBaseUs = attempt.audioBaseUs
                        }
                        attempt.result ?: runFullAttempt(request, streams, audioTracks, staging, itemId, threads)
                    } else {
                        runFullAttempt(request, streams, audioTracks, staging, itemId, threads)
                    }
                when (result) {
                    TransferResult.Completed -> {
                        return FetchOutcome.Fetched(
                            video?.let { staging.videoPart },
                            staging.audioPart,
                            streams.durationMs,
                            streams,
                            segmentUsed,
                            snappedStartUs,
                            snappedStartUsByRole,
                            audioParts,
                            audioBaseUs,
                        )
                    }

                    TransferResult.Stopped -> {
                        currentCoroutineContext().ensureActive()
                        return FetchOutcome.Failed(FetchFailure.NETWORK)
                    }

                    TransferResult.Stalled -> {
                        Log.w(TAG, "${request.videoId}: no bytes for ${DIRECT_STALL_TICKS}s, resolving again")
                    }

                    is TransferResult.Denied -> {
                        val itag = StreamDenialClassifier.itagOf(result.url)?.toIntOrNull()
                        ClientGateTracker.reportDenied(result.url)
                        if (itag != null && (refusals.merge(itag, 1, Int::plus) ?: 0) >= 2) avoid = avoid + itag
                    }

                    is TransferResult.Failed -> {
                        return FetchOutcome.Failed(
                            when (result.reason) {
                                TransferFailure.DISK_FULL -> FetchFailure.DISK_FULL
                                TransferFailure.LENGTH_UNKNOWN -> FetchFailure.LENGTH_UNKNOWN
                                TransferFailure.BLOCK_FAILED -> FetchFailure.NETWORK
                            },
                            result.detail,
                        )
                    }
                }
            }
            return viaSabr(request, staging, itemId)
        }

        /**
         * The outcome of one segment-only clip attempt, including each audio track's own
         * first-selected segment start: the remux shifts every track by its own base, so a
         * second language's part lands on the same absolute timeline as the first.
         */
        private data class ClipAttemptResult(
            val result: TransferResult?,
            val snappedStartUs: Long?,
            val snappedStartUsByRole: Map<StreamRole, Long>,
            val audioBaseUs: List<Long>,
        )

        /**
         * A clip attempt that fetches only the segments covering the clip range. Null when any
         * stream carries no segment index: the caller runs the full download instead, which
         * the runner then trims the same way as before. The snapped start is the clip's true
         * start from the segment index, for the remux offset — null unless segments were
         * planned. The per-audio bases are each track's own first-selected segment start,
         * for the remux timebase shift — empty unless segments were planned.
         */
        private suspend fun runClipAttempt(
            request: DownloadRequest,
            streams: ResolvedStreams,
            audioTracks: List<Format>,
            staging: DownloadStaging,
            itemId: Int,
            threads: Int,
        ): ClipAttemptResult {
            val startMs = request.clipStartMs ?: return ClipAttemptResult(null, null, emptyMap(), emptyList())
            val endMs = request.clipEndMs ?: return ClipAttemptResult(null, null, emptyMap(), emptyList())
            val video = streams.video
            if (video != null && !hasSegmentIndex(video)) return ClipAttemptResult(null, null, emptyMap(), emptyList())
            if (audioTracks.any { !hasSegmentIndex(it) }) return ClipAttemptResult(null, null, emptyMap(), emptyList())
            // A segment file is init + selected segments, not the whole stream, so no saved block
            // map from a full attempt fits it: start from fresh part files.
            staging.clearParts()
            // One session per fetcher call: the fetcher overwrites the snapped starts each
            // call, so each audio track's own base has to come from its own session. The
            // progress sums all of them, so the bar covers every language, not just the first.
            val sessions = audioTracks.map { SegmentFetchSession(request.videoId) }
            val result =
                reportingProgress(
                    request,
                    itemId,
                    { sessions.sumOf { it.downloaded.get() } },
                    { sessions.sumOf { it.total.get() } },
                    null,
                    StallLimit(DIRECT_STALL_TICKS) { sessions.forEach { it.cancelForStall() } },
                ) {
                    var outcome: TransferResult? = null
                    for ((index, audio) in audioTracks.withIndex()) {
                        // The video rides along with the first audio; the rest fetch alone.
                        outcome =
                            segmentClipFetcher.fetch(
                                request.videoId,
                                if (index == 0) video else null,
                                audio,
                                staging.videoPart,
                                staging.audioPart(index),
                                startMs,
                                endMs,
                                threads,
                                YouTubeClient.USER_AGENT_WEB,
                                sessions[index],
                            )
                        if (outcome != null && outcome !is TransferResult.Completed) break
                        if (outcome == null) break
                    }
                    outcome
                }
            if (result !is TransferResult.Completed) return ClipAttemptResult(result, null, emptyMap(), emptyList())
            val snappedStartUs = sessions.mapNotNull { it.snappedStartUs }.minOrNull()
            val snappedStartUsByRole =
                sessions.firstOrNull()?.snappedStartUsByRole.orEmpty()
                    .filterKeys { it == StreamRole.VIDEO }
            val audioBaseUs = sessions.map { it.snappedStartUsByRole[StreamRole.AUDIO] ?: 0L }
            return ClipAttemptResult(result, snappedStartUs, snappedStartUsByRole, audioBaseUs)
        }

        private fun hasSegmentIndex(format: Format): Boolean =
            !format.url.isNullOrBlank() && format.initRange != null && format.indexRange != null

        /** The full-file attempt: the existing transfer, unchanged, one part per audio track. */
        private suspend fun runFullAttempt(
            request: DownloadRequest,
            streams: ResolvedStreams,
            audioTracks: List<Format>,
            staging: DownloadStaging,
            itemId: Int,
            threads: Int,
        ): TransferResult {
            val video = streams.video
            val parts =
                listOfNotNull(
                    video?.let {
                        TransferStream(
                            StreamRole.VIDEO,
                            it.url.orEmpty(),
                            staging.videoPart,
                            it.itag,
                            it.contentLength ?: 0L,
                        )
                    },
                ) + audioTracks.mapIndexed { index, audio ->
                    TransferStream(
                        StreamRole.AUDIO,
                        audio.url.orEmpty(),
                        staging.audioPart(index),
                        audio.itag,
                        audio.contentLength ?: 0L,
                    )
                }
            if (TransferState.read(staging.state)?.restoreInto(parts) == true) Log.d(TAG, "${request.videoId}: resuming saved blocks")
            val job = TransferJob(request.videoId, parts, threads, YouTubeClient.USER_AGENT_WEB)
            return try {
                reportingProgress(
                    request,
                    itemId,
                    { job.downloadedBytes },
                    { job.totalBytes },
                    { saveState(staging, job) },
                    StallLimit(DIRECT_STALL_TICKS) { job.fail(TransferResult.Stalled) },
                ) {
                    rangeDownloader.run(job)
                }
            } finally {
                saveState(staging, job)
            }
        }

        /**
         * The last resort once every direct URL was refused. What it writes still has to pass the
         * runner's remux and playability checks, so a session that never sends an init segment ends
         * as a failure rather than a finished file that cannot play.
         */
        private suspend fun viaSabr(
            request: DownloadRequest,
            staging: DownloadStaging,
            itemId: Int,
        ): FetchOutcome {
            staging.clearParts()
            val info =
                try {
                    InnerTubeVideoStreamExtractor.resolveSabrDownload(
                        videoId = request.videoId,
                        targetHeight = if (request.wantsAudioOnly) 0 else request.targetHeight ?: 0,
                        preferredCodec = request.videoCodec,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "${request.videoId}: SABR resolve failed", e)
                    null
                } ?: return FetchOutcome.Failed(FetchFailure.REFUSED)
            val engine = SabrDownloadEngine()
            var estimated = 0L
            val done =
                try {
                    reportingProgress(
                        request,
                        itemId,
                        { engine.downloadedVideoBytes.get() + engine.downloadedAudioBytes.get() },
                        { estimated },
                        null,
                        StallLimit(SABR_STALL_TICKS) {
                            Log.w(TAG, "${request.videoId}: SABR sent nothing new for ${SABR_STALL_TICKS}s")
                            engine.cancel()
                        },
                    ) {
                        engine.download(
                            streamingUrl = info.streamingUrl,
                            videoId = request.videoId,
                            audioItag = info.audioItag,
                            audioLmt = info.audioLmt,
                            videoItag = info.videoItag,
                            videoLmt = info.videoLmt,
                            audioXtags = info.audioXtags,
                            videoXtags = info.videoXtags,
                            poToken = info.poToken,
                            visitorId = info.visitorId,
                            ustreamerConfig = info.ustreamerConfig,
                            durationMs = info.durationMs,
                            videoOutputPath = staging.videoPart.path,
                            audioOutputPath = staging.audioPart.path,
                            audioOnly = request.wantsAudioOnly,
                        ) { _, total -> if (total > 0) estimated = total }
                    }
                } catch (e: CancellationException) {
                    engine.cancel()
                    throw e
                }
            if (!done) return FetchOutcome.Failed(FetchFailure.REFUSED)
            return FetchOutcome.Fetched(staging.videoPart.takeUnless { request.wantsAudioOnly }, staging.audioPart, info.durationMs)
        }

        private suspend fun <T> reportingProgress(
            request: DownloadRequest,
            itemId: Int,
            downloaded: () -> Long,
            total: () -> Long,
            persist: (() -> Unit)?,
            stall: StallLimit,
            block: suspend () -> T,
        ): T =
            coroutineScope {
                val ticker =
                    launch {
                        var ticks = 0
                        while (isActive) {
                            delay(PROGRESS_INTERVAL_MS)
                            val done = downloaded()
                            val all = total()
                            if (stall.stillAt(done)) stall.onStall()
                            downloads.emitProgress(
                                DownloadProgressUpdate(request.videoId, itemId, done, all, DownloadItemStatus.DOWNLOADING),
                            )
                            notifier.show(request.videoId, request.tags.title, DownloadPhase.Transferring(done, all))
                            if (++ticks % PERSIST_EVERY_TICKS == 0) persist?.invoke()
                        }
                    }
                try {
                    block()
                } finally {
                    ticker.cancel()
                }
            }

        private fun saveState(
            staging: DownloadStaging,
            job: TransferJob,
        ) {
            runCatching { TransferState.write(staging.state, TransferState.of(job)) }
                .onFailure { Log.w(TAG, "${job.videoId}: could not save resume state", it) }
        }

        private companion object {
            const val TAG = "DownloadTransfer"
            const val MAX_RESOLVES = 4
            const val PROGRESS_INTERVAL_MS = 1_000L
            const val PERSIST_EVERY_TICKS = 5
            const val DIRECT_STALL_TICKS = 45
            const val SABR_STALL_TICKS = 60
        }
    }
