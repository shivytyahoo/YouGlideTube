package io.github.shivytyahoo.glidetube.utils.potoken

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * This is based and ported from Metrolist,
 * see https://github.com/MetrolistGroup/Metrolist for the original code and license.
 */
class PoTokenException(message: String) : Exception(message)

// to be thrown if the WebView provided by the system is broken
class BadWebViewException(message: String) : Exception(message)

fun buildExceptionForJsError(error: String): Exception {
    return if (error.contains("SyntaxError"))
        BadWebViewException(error)
    else
        PoTokenException(error)
}

/**
 * Waits for a WebView callback and fails with a [PoTokenException] when it does not come, so a
 * WebView that lost its content cannot hold a mint (or a build) indefinitely.
 */
fun awaitOrThrow(latch: CountDownLatch, timeoutMs: Long, what: String) {
    if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
        throw PoTokenException("$what: no answer within $timeoutMs ms")
    }
}
