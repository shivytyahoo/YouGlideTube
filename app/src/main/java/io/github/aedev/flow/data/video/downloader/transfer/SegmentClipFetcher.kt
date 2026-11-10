package io.github.aedev.flow.data.video.downloader.transfer

import android.util.Log
import io.github.aedev.flow.data.video.downloader.YouTubeStreamUrls
import io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format
import io.github.aedev.flow.network.ProxyAwareClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** Per-clip-fetch progress and stall/cancel state, owned by the caller (DownloadTransfer). */
class SegmentFetchSession(
    val videoId: String,
) {
    val downloaded = AtomicLong(0L)
    val total = AtomicLong(0L)

    @Volatile
    var stalled = false
        private set

    /**
     * The clip's true snapped start in microseconds: the earliest first-selected segment start
     * across the two streams, from the segment index. The remux uses it as its offset — the
     * extractor's first sample time proved unreadable right after opening a segment-only part
     * (device report 7: trimmedStartMs=0 for a clip starting at 44:30).
     */
    @Volatile
    var snappedStartUs: Long? = null

    /**
     * The same, per stream: each stream's own first-selected segment start. The remux needs
     * these because the two extractors do not share a timebase on segment-only parts — the
     * fragmented-MP4 extractor reports times relative to its first segment (device report 9:
     * audio firstSampleTime=0), while WebM reports absolute stream times.
     */
    @Volatile
    var snappedStartUsByRole: Map<StreamRole, Long> = emptyMap()

    private val activeCalls: MutableList<Call> = Collections.synchronizedList(mutableListOf())

    /** Stops the in-flight requests; workers see [stalled] and end the fetch as stalled. */
    fun cancelForStall() {
        stalled = true
        activeCalls.toList().forEach { it.cancel() }
    }

    internal fun track(call: Call) {
        activeCalls.add(call)
        if (stalled) call.cancel()
    }

    internal fun untrack(call: Call) {
        activeCalls.remove(call)
    }
}

/**
 * Downloads only the DASH segments covering a clip range, instead of the whole stream.
 *
 * For each stream the init segment and the index are fetched first (kilobytes); the index maps
 * every segment to its time range and its byte range in the source file, so only the segments
 * overlapping the clip are fetched, in parallel, and written contiguously after the init segment.
 * The part files then read as one fragmented file, and the runner's remux trims the exact end.
 * The index is the MP4 `sidx` for MP4 streams and the WebM `Cues` element for WebM streams
 * (VP9/AV1) — see [SidxIndex] and [WebmCuesIndex].
 *
 * A stream without an index, a bad index, or a segment that does not start with a media box
 * falls back to the full download: this fetch returns null and the caller runs its normal path.
 * A refused URL still reports the refusal, so the caller's re-resolve logic applies unchanged.
 */
@Singleton
class SegmentClipFetcher internal constructor(
    private val client: () -> OkHttpClient,
    private val transferDispatcher: CoroutineDispatcher,
) {
    @Inject
    constructor() : this(
        ProxyAwareClient {
            connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .retryOnConnectionFailure(true)
        }::get,
        Dispatchers.IO.limitedParallelism(MAX_PARALLEL_READS),
    )

    /**
     * Fetches the clip's segments for both streams into [videoPart] and [audioPart] ([video] may
     * be null for audio-only). Null when either stream has no usable segment index: the caller
     * falls back to the full download. Otherwise a [TransferResult] handled exactly like a
     * range-download result.
     */
    suspend fun fetch(
        videoId: String,
        video: Format?,
        audio: Format,
        videoPart: File,
        audioPart: File,
        clipStartMs: Long,
        clipEndMs: Long,
        threads: Int,
        userAgent: String,
        session: SegmentFetchSession,
    ): TransferResult? =
        withContext(transferDispatcher) {
            val clipStartUs = clipStartMs * 1000L
            val clipEndUs = clipEndMs * 1000L
            val jobs = mutableListOf<Pair<StreamPlan, File>>()
            if (video != null) {
                when (val plan = planStream(video, StreamRole.VIDEO, clipStartUs, clipEndUs, userAgent, session)) {
                    is Plan.Ok -> jobs += plan.plan to videoPart
                    Plan.NotSegmented -> return@withContext null
                    is Plan.Denied -> return@withContext TransferResult.Denied(plan.url)
                    is Plan.Failed -> return@withContext TransferResult.Failed(plan.reason, plan.detail)
                    Plan.Stalled -> return@withContext TransferResult.Stalled
                }
            }
            when (val plan = planStream(audio, StreamRole.AUDIO, clipStartUs, clipEndUs, userAgent, session)) {
                is Plan.Ok -> jobs += plan.plan to audioPart
                Plan.NotSegmented -> return@withContext null
                is Plan.Denied -> return@withContext TransferResult.Denied(plan.url)
                is Plan.Failed -> return@withContext TransferResult.Failed(plan.reason, plan.detail)
                Plan.Stalled -> return@withContext TransferResult.Stalled
            }
            // The earliest first-selected segment start across the streams: the clip's true
            // snapped start, straight from the index — the remux offsets by this.
            session.snappedStartUs = jobs.minOfOrNull { it.first.segments.first().startUs }
            session.snappedStartUsByRole = jobs.associate { it.first.role to it.first.segments.first().startUs }
            try {
                downloadSegments(jobs, threads, userAgent, session)
            } catch (e: BadSegmentIndexException) {
                Log.w(TAG, "$videoId: a ${e.role} segment does not start with a media box, falling back to the full download")
                return@withContext null
            }
        }

    private suspend fun planStream(
        format: Format,
        role: StreamRole,
        clipStartUs: Long,
        clipEndUs: Long,
        userAgent: String,
        session: SegmentFetchSession,
    ): Plan {
        val url = format.url?.takeIf { it.isNotBlank() } ?: return Plan.NotSegmented
        val initSpan = format.initRange?.let(::rangeSpan) ?: return Plan.NotSegmented
        val indexSpan = format.indexRange?.let(::rangeSpan) ?: return Plan.NotSegmented
        val initBytes =
            when (val init = fetchBytes(url, initSpan.first, initSpan.first + initSpan.second - 1, userAgent, session)) {
                is BytesOutcome.Bytes -> init.bytes
                is BytesOutcome.Denied -> return Plan.Denied(init.url)
                is BytesOutcome.Failed -> return Plan.Failed(init.reason, init.detail)
                BytesOutcome.Stalled -> return Plan.Stalled
            }
        val indexBytes =
            when (val index = fetchBytes(url, indexSpan.first, indexSpan.first + indexSpan.second - 1, userAgent, session)) {
                is BytesOutcome.Bytes -> index.bytes
                is BytesOutcome.Denied -> return Plan.Denied(index.url)
                is BytesOutcome.Failed -> return Plan.Failed(index.reason, index.detail)
                BytesOutcome.Stalled -> return Plan.Stalled
            }
        val segments =
            if (format.mimeType?.contains("webm", ignoreCase = true) == true) {
                WebmCuesIndex.parse(initBytes, indexBytes, format.contentLength)
            } else {
                SidxIndex.parse(indexBytes, indexSpan.first)
            } ?: return Plan.NotSegmented
        val selected = segments.filter { it.startUs < clipEndUs && it.endUs > clipStartUs }
        if (selected.isEmpty()) return Plan.NotSegmented
        return Plan.Ok(StreamPlan(role, url, initBytes, selected, format.mimeType?.contains("webm", ignoreCase = true) == true))
    }

    /** Start and length of an inclusive byte range. */
    private fun rangeSpan(range: Format.Range): Pair<Long, Long>? {
        val start = range.start?.toLongOrNull() ?: return null
        val end = range.end?.toLongOrNull() ?: return null
        return if (end >= start) start to end - start + 1 else null
    }

    private suspend fun downloadSegments(
        jobs: List<Pair<StreamPlan, File>>,
        threads: Int,
        userAgent: String,
        session: SegmentFetchSession,
    ): TransferResult {
        for ((plan, file) in jobs) {
            preparePart(file, plan)
            session.downloaded.addAndGet(plan.initBytes.size.toLong())
        }
        session.total.set(jobs.sumOf { it.first.totalBytes })
        val failure = AtomicReference<TransferResult?>(null)
        coroutineScope {
            jobs
                .flatMap { (plan, file) ->
                    val workers =
                        if (plan.role == StreamRole.AUDIO && jobs.size > 1) {
                            audioWorkerCount(threads)
                        } else {
                            videoWorkerCount(threads)
                        }
                    List(workers) { async { segmentWorker(plan, file, userAgent, session, failure) } }
                }.awaitAll()
        }
        failure.get()?.let { return it }
        if (session.stalled) return TransferResult.Stalled
        currentCoroutineContext().ensureActive()
        return TransferResult.Completed
    }

    private fun preparePart(
        file: File,
        plan: StreamPlan,
    ) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            if (raf.length() != plan.totalBytes) raf.setLength(plan.totalBytes)
            raf.seek(0)
            raf.write(plan.initBytes)
        }
    }

    private suspend fun segmentWorker(
        plan: StreamPlan,
        file: File,
        userAgent: String,
        session: SegmentFetchSession,
        failure: AtomicReference<TransferResult?>,
    ) {
        while (failure.get() == null && !session.stalled) {
            currentCoroutineContext().ensureActive()
            val index = plan.nextSegment.getAndIncrement()
            if (index >= plan.segments.size) return
            val segment = plan.segments[index]
            when (val outcome = fetchSegment(plan.url, segment, file, plan.destOffset(index), userAgent, session, plan.isWebm, plan.role)) {
                is RangeOutcome.Done -> session.downloaded.addAndGet(outcome.bytes)
                RangeOutcome.Stalled -> return
                is RangeOutcome.Denied -> failure.compareAndSet(null, TransferResult.Denied(outcome.url))
                is RangeOutcome.Failed -> failure.compareAndSet(null, TransferResult.Failed(outcome.reason, outcome.detail))
                is RangeOutcome.Retry -> failure.compareAndSet(null, TransferResult.Failed(TransferFailure.BLOCK_FAILED, outcome.reason))
            }
        }
    }

    private suspend fun fetchSegment(
        url: String,
        segment: SidxIndex.Segment,
        file: File,
        destOffset: Long,
        userAgent: String,
        session: SegmentFetchSession,
        isWebm: Boolean,
        role: StreamRole,
    ): RangeOutcome =
        fetchRange(url, segment.byteStart, segment.byteEnd, userAgent, session) { input, expected ->
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(destOffset)
                val buffer = ByteArray(BUFFER_SIZE)
                var written = 0L
                var peeked = 0
                val peek = ByteArray(8)
                while (written < expected) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), expected - written).toInt())
                    if (read < 0) break
                    currentCoroutineContext().ensureActive()
                    if (session.stalled) break
                    if (peeked < 8) {
                        val take = minOf(8 - peeked, read)
                        buffer.copyInto(peek, peeked, 0, take)
                        peeked += take
                        if (peeked == 8) {
                            if (isWebm) {
                                // A WebM segment starts with the Cluster element ID.
                                if (peek[0] != 0x1F.toByte() || peek[1] != 0x43.toByte() ||
                                    peek[2] != 0xB6.toByte() || peek[3] != 0x75.toByte()
                                ) {
                                    throw BadSegmentIndexException(role)
                                }
                            } else {
                                val type = String(peek, 4, 4, Charsets.ISO_8859_1)
                                if (type != "moof" && type != "styp") throw BadSegmentIndexException(role)
                            }
                        }
                    }
                    raf.write(buffer, 0, read)
                    written += read
                }
                written
            }
        }

    private suspend fun fetchBytes(
        url: String,
        from: Long,
        to: Long,
        userAgent: String,
        session: SegmentFetchSession,
    ): BytesOutcome {
        val out = ByteArrayOutputStream()
        val outcome = fetchRange(url, from, to, userAgent, session) { input, expected ->
            val buffer = ByteArray(BUFFER_SIZE)
            var written = 0L
            while (written < expected) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), expected - written).toInt())
                if (read < 0) break
                currentCoroutineContext().ensureActive()
                if (session.stalled) break
                out.write(buffer, 0, read)
                written += read
            }
            written
        }
        return when (outcome) {
            is RangeOutcome.Done -> BytesOutcome.Bytes(out.toByteArray())
            RangeOutcome.Stalled -> BytesOutcome.Stalled
            is RangeOutcome.Denied -> BytesOutcome.Denied(outcome.url)
            is RangeOutcome.Failed -> BytesOutcome.Failed(outcome.reason, outcome.detail)
            is RangeOutcome.Retry -> BytesOutcome.Failed(TransferFailure.BLOCK_FAILED, outcome.reason)
        }
    }

    private suspend fun fetchRange(
        url: String,
        from: Long,
        to: Long,
        userAgent: String,
        session: SegmentFetchSession,
        write: suspend (InputStream, Long) -> Long,
    ): RangeOutcome {
        var lastError: String? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            currentCoroutineContext().ensureActive()
            if (session.stalled) return RangeOutcome.Stalled
            when (val outcome = fetchRangeOnce(url, from, to, userAgent, session, write)) {
                is RangeOutcome.Done -> return outcome
                RangeOutcome.Stalled -> return RangeOutcome.Stalled
                is RangeOutcome.Denied -> return outcome
                is RangeOutcome.Failed -> return outcome
                is RangeOutcome.Retry -> {
                    lastError = outcome.reason
                    if (outcome.diskFull) return RangeOutcome.Failed(TransferFailure.DISK_FULL, outcome.reason)
                    if (attempt + 1 < MAX_ATTEMPTS) delay(RangeDownloader.retryDelayMs(attempt, outcome.retryAfterMs))
                }
            }
        }
        return RangeOutcome.Failed(TransferFailure.BLOCK_FAILED, lastError)
    }

    private suspend fun fetchRangeOnce(
        url: String,
        from: Long,
        to: Long,
        userAgent: String,
        session: SegmentFetchSession,
        write: suspend (InputStream, Long) -> Long,
    ): RangeOutcome {
        val call = client().newCall(blockRequest(url, from, to, userAgent))
        session.track(call)
        val response =
            try {
                call.execute()
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                session.untrack(call)
                return if (session.stalled) RangeOutcome.Stalled else RangeOutcome.Retry(e.message ?: e.javaClass.simpleName)
            }
        try {
            if (!response.isSuccessful) return failedResponse(response, url)
            // Only googlevideo's query range may answer 200; anywhere else a 200 means the
            // server ignored the Range header and is sending the file from its first byte.
            if (response.code != 206 && !YouTubeStreamUrls.isYouTubeStreamUrl(url)) {
                return RangeOutcome.Retry("range ignored")
            }
            val expected = to - from + 1
            val written = write(response.body.byteStream(), expected)
            if (session.stalled) return RangeOutcome.Stalled
            if (written < expected) return RangeOutcome.Retry("short range: $written of $expected bytes")
            return RangeOutcome.Done(written)
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            return if (session.stalled) {
                RangeOutcome.Stalled
            } else {
                RangeOutcome.Retry(e.message ?: e.javaClass.simpleName, diskFull = RangeDownloader.isDiskFull(e))
            }
        } finally {
            response.close()
            session.untrack(call)
        }
    }

    private fun failedResponse(
        response: Response,
        url: String,
    ): RangeOutcome =
        when (response.code) {
            403 -> RangeOutcome.Denied(url)
            429, 500, 502, 503, 504 -> RangeOutcome.Retry("HTTP ${response.code}", retryAfterMs = retryAfterMs(response))
            else -> RangeOutcome.Retry("HTTP ${response.code}")
        }

    private fun retryAfterMs(response: Response): Long? =
        response
            .header("Retry-After")
            ?.trim()
            ?.toLongOrNull()
            ?.times(1000L)

    private class StreamPlan(
        val role: StreamRole,
        val url: String,
        val initBytes: ByteArray,
        val segments: List<SidxIndex.Segment>,
        val isWebm: Boolean,
    ) {
        val totalBytes: Long = initBytes.size.toLong() + segments.sumOf { it.byteEnd - it.byteStart + 1 }
        val nextSegment = AtomicInteger(0)
        private val destOffsets: LongArray =
            LongArray(segments.size).apply {
                var offset = initBytes.size.toLong()
                segments.forEachIndexed { index, segment ->
                    this[index] = offset
                    offset += segment.byteEnd - segment.byteStart + 1
                }
            }

        fun destOffset(index: Int): Long = destOffsets[index]
    }

    private sealed interface Plan {
        data class Ok(
            val plan: StreamPlan,
        ) : Plan

        data object NotSegmented : Plan

        data class Denied(
            val url: String,
        ) : Plan

        data class Failed(
            val reason: TransferFailure,
            val detail: String?,
        ) : Plan

        data object Stalled : Plan
    }

    private sealed interface RangeOutcome {
        data class Done(
            val bytes: Long,
        ) : RangeOutcome

        data object Stalled : RangeOutcome

        data class Denied(
            val url: String,
        ) : RangeOutcome

        data class Retry(
            val reason: String,
            val retryAfterMs: Long? = null,
            val diskFull: Boolean = false,
        ) : RangeOutcome

        data class Failed(
            val reason: TransferFailure,
            val detail: String?,
        ) : RangeOutcome
    }

    private sealed interface BytesOutcome {
        data class Bytes(
            val bytes: ByteArray,
        ) : BytesOutcome

        data object Stalled : BytesOutcome

        data class Denied(
            val url: String,
        ) : BytesOutcome

        data class Failed(
            val reason: TransferFailure,
            val detail: String?,
        ) : BytesOutcome
    }

    /** A fetched segment does not start with a media box, so the index arithmetic is wrong. */
    private class BadSegmentIndexException(
        val role: StreamRole,
    ) : Exception()

    private companion object {
        const val TAG = "SegmentClipFetcher"
        const val MAX_ATTEMPTS = 5
        const val BUFFER_SIZE = 256 * 1024
        const val MAX_PARALLEL_READS = 16
    }
}
