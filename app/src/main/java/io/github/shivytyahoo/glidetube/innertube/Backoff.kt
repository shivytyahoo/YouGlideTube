package io.github.shivytyahoo.glidetube.innertube

import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.math.min
import kotlin.random.Random

/** Thrown when YouTube answers 429 or 503, so the retry wrapper can pause instead of failing. */
class RateLimitedException(
    val statusCode: Int,
) : IOException("YouTube rate limited the request (HTTP $statusCode)")

/**
 * Jittered retry pauses for the InnerTube client, with a global hold: while one request is
 * cooling down after a 429/503, every other request waits too instead of hammering the same
 * limit. The pauses are jittered so a burst of retries does not land in the same instant.
 */
object Backoff {
    /** How many times a rate-limited request is retried before the error is returned. */
    const val MAX_RETRIES = 2

    // The global hold never exceeds the longest single pause, so a stale hold cannot stall
    // the client long after the limit has lifted.
    private const val MAX_HOLD_MS = 6_000L

    @Volatile
    private var coolUntilMs = 0L

    /** Whether this status means "back off": too many requests, or the service is unavailable. */
    fun isBusy(statusCode: Int): Boolean = statusCode == 429 || statusCode == 503

    /** Waits out the remainder of the global hold, if one is running. */
    suspend fun cool() {
        val remaining = coolUntilMs - System.currentTimeMillis()
        if (remaining > 0) delay(min(remaining, MAX_HOLD_MS))
    }

    /**
     * Pauses after a rate limit: 1–2 s after the first refusal, 3–6 s after the second, and
     * sets the global hold so other requests wait out the same window.
     */
    suspend fun pause(attempt: Int) {
        val baseMs = if (attempt == 0) 1_000L else 3_000L
        val waitMs = baseMs + Random.nextLong(baseMs)
        coolUntilMs = System.currentTimeMillis() + waitMs
        delay(waitMs)
    }
}
