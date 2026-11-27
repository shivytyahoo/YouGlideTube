package io.github.shivytyahoo.glidetube.player.stream

import android.util.Log
import io.github.shivytyahoo.glidetube.data.localmedia.LocalMediaIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Starts stream extraction the moment a video is tapped — or long-pressed on its card, which
 * opens the quick actions and may lead straight into playback — ahead of the player screen
 * composing and the ViewModel running its load. A press held still for 90 ms warms the same way,
 * so dwelling on a card while scrolling pays the extraction cost before the tap lands.
 *
 * This buys latency without buying network: the player's load path calls
 * [InnerTubeVideoStreamExtractor.extract] with the same arguments, so [InFlightRequestCoalescer]
 * makes that call join the request started here instead of opening a second client ladder. The
 * work simply began a few hundred milliseconds earlier — navigation, composition and the player
 * screen's own setup now overlap with the extraction rather than following it. A prefetch that
 * already finished is not lost either: the extractor keeps plain results for a few minutes, so
 * a tap that lands after the prefetch finished still skips the ladder.
 */
object PlaybackPrefetcher {
    private const val TAG = "PlaybackPrefetcher"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private var inFlight: Job? = null
    private var inFlightVideoId: String? = null

    /**
     * Warms the watch response (description, chapters, related) the player screen reads through
     * the repository's cache. Bound once from the Application, so this object stays free of the
     * repository; unbound it is a no-op and only stream extraction warms.
     */
    @Volatile
    private var detailsWarmer: (suspend (String) -> Unit)? = null

    fun bindDetailsWarmer(warmer: suspend (String) -> Unit) {
        detailsWarmer = warmer
    }

    private var detailsInFlight: Job? = null
    private var detailsInFlightVideoId: String? = null

    /**
     * Warms extraction for [videoId]. Safe to call from the main thread; returns immediately.
     *
     * Local media plays from a `content://` URI and never touches extraction, so those ids are
     * ignored rather than sent through a client ladder that would certainly fail.
     *
     * Direct-only, like the player load: the guarded SABR upgrade runs in the background once
     * the direct result has shipped, so warming a full extraction here would start work the
     * load never waits on — and, worse, under a different coalescer key, so the load could
     * not join it.
     */
    fun prefetch(videoId: String) {
        if (videoId.isBlank() || LocalMediaIds.isLocal(videoId)) return
        synchronized(lock) {
            if (inFlightVideoId == videoId && inFlight?.isActive == true) return
            // Only the most recently tapped video is worth warming. Leaving an abandoned one
            // running would have it compete for bandwidth with the video actually being opened.
            inFlight?.cancel()
            inFlightVideoId = videoId
            inFlight =
                scope.launch {
                    runCatching { InnerTubeVideoStreamExtractor.extract(videoId, skipSabrUpgrade = true) }
                        .onFailure { Log.d(TAG, "prefetch for $videoId ended: ${it.javaClass.simpleName}") }
                }
        }
    }

    /**
     * Warms the watch response for [videoId] through the repository, so the description,
     * chapters and related list the player screen fetches join this request instead of opening
     * their own. Same most-recent-only rule as [prefetch]: a newer card replaces the old warm.
     */
    fun prefetchDetails(videoId: String) {
        val warmer = detailsWarmer ?: return
        if (videoId.isBlank() || LocalMediaIds.isLocal(videoId)) return
        synchronized(lock) {
            if (detailsInFlightVideoId == videoId && detailsInFlight?.isActive == true) return
            detailsInFlight?.cancel()
            detailsInFlightVideoId = videoId
            detailsInFlight =
                scope.launch {
                    runCatching { warmer(videoId) }
                        .onFailure { Log.d(TAG, "details prefetch for $videoId ended: ${it.javaClass.simpleName}") }
                }
        }
    }
}
