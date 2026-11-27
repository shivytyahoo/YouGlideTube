package io.github.shivytyahoo.glidetube.player.sabr

import android.util.Log
import io.github.shivytyahoo.glidetube.utils.potoken.WebPoTokenSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Warms the streaming engine in the background so a video that needs it does not pay the
 * cold-start cost: the first attestation builds the WebView session, and later videos reuse
 * the warm session. Best-effort only — a failed warm is logged and retried at the next
 * trigger, never surfaced to playback.
 */
object SabrPrewarm {
    private const val TAG = "SabrPrewarm"

    // The first public video on the platform; any stable id works — the mint only needs a
    // page answer, not the video itself.
    private const val PROBE_VIDEO_ID = "jNQXAC9IVRw"

    // A warm session stays good for half an hour; warming again inside the window is wasted work.
    private const val WARM_VALID_MS = 30 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val warming = AtomicBoolean(false)

    @Volatile
    private var lastWarmMs = 0L

    /** Fire-and-forget: mints a token for the probe video unless one was warmed recently. */
    fun warmInBackground() {
        if (System.currentTimeMillis() - lastWarmMs < WARM_VALID_MS) return
        if (!warming.compareAndSet(false, true)) return
        scope.launch {
            try {
                if (WebPoTokenSession.mint(PROBE_VIDEO_ID) != null) {
                    lastWarmMs = System.currentTimeMillis()
                    Log.d(TAG, "Streaming session warmed")
                } else {
                    Log.d(TAG, "Prewarm: no token minted")
                }
            } catch (e: Exception) {
                Log.d(TAG, "Prewarm failed: ${e.message}")
            } finally {
                warming.set(false)
            }
        }
    }
}
