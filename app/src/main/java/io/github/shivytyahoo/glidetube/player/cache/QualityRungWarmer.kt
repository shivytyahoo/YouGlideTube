package io.github.shivytyahoo.glidetube.player.cache

import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import io.github.shivytyahoo.glidetube.utils.PerformanceDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException

/**
 * Warms a tapped quality rung's first bytes into the video cache, through the same cache the
 * player reads: the rung's init segment, then the first seconds of media at the current
 * position. A switch to that rung then finds its opening bytes local instead of fetching
 * them. One rung at a time — a new tap cancels the previous warm — and bounded to the init
 * segment plus a short window, so warming never downloads a whole rung the viewer may not
 * stay on. Built on the same [CacheWriter] mechanism as [DiskReadAhead], for one stream
 * instead of the playing one.
 */
@UnstableApi
internal class QualityRungWarmer(
    private val cacheManager: PlayerCacheManager,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    fun warm(
        stream: VideoStream,
        positionMs: Long,
    ) {
        cancel()
        job =
            scope.launch(PerformanceDispatcher.networkIO) {
                warmRung(stream, positionMs)
            }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private suspend fun warmRung(
        stream: VideoStream,
        positionMs: Long,
    ) {
        val itag = stream.itagItem ?: return
        val url = stream.content.takeIf { it.isNotBlank() } ?: return
        val uri = Uri.parse(url)
        if (!DiskReadAheadPolicy.isWritableStream(uri.scheme, uri.host)) return
        val source = cacheManager.createWritingDataSource() ?: return
        val contentLength = uri.getQueryParameter("clen")?.toLongOrNull()
        try {
            // The init segment first: the DASH loader needs it before any media segment.
            write(source, uri, itag.initStart.toLong(), (itag.initEnd - itag.initStart + 1).toLong())
            val range =
                DiskReadAheadPolicy.byteRange(positionMs, positionMs + WARM_WINDOW_MS, stream.bitrate, contentLength)
                    ?: return
            val length = minOf(range.last - range.first + 1, MAX_WARM_BYTES)
            write(source, uri, range.first, length)
            Log.d(TAG, "Warmed ${itag.id} at ${positionMs / 1000}s")
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // A superseded warm is cancelled, not failed; anything else just leaves the
            // rung cold, and the switch still plays — it fetches the bytes instead.
            if (currentCoroutineContext()[Job]?.isActive == true) Log.d(TAG, "Rung warm skipped: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun write(
        source: CacheDataSource,
        uri: Uri,
        position: Long,
        length: Long,
    ) {
        if (length <= 0) return
        currentCoroutineContext().ensureActive()
        val spec =
            DataSpec
                .Builder()
                .setUri(uri)
                .setPosition(position)
                .setLength(length)
                .build()
        val writer = CacheWriter(source, spec, null, null)
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { writer.cancel() }
        try {
            writer.cache()
        } finally {
            handle?.dispose()
        }
    }

    private companion object {
        const val TAG = "QualityRungWarmer"

        /** Media time warmed ahead of the current position, per tap. */
        const val WARM_WINDOW_MS = 2_000L

        /** Cap on the media bytes warmed per tap, so a very high-bitrate rung stays bounded. */
        const val MAX_WARM_BYTES = 2L * 1024L * 1024L
    }
}
