package io.github.shivytyahoo.glidetube.utils.potoken

/**
 * One BotGuard WebView, shared by the video, music and NewPipe mint paths through
 * [PoTokenGenerator]. The two generators differ in how they obtain the challenge, not in what
 * callers need from them.
 *
 * Threading: [attest] and [generatePoToken] are safe to call from any thread — each
 * implementation dispatches its main-thread and blocking work internally, and neither may run a
 * mint on the main thread. [close] is the exception: it drives WebView teardown and must run on
 * the main thread.
 */
interface BotGuardGenerator {
    /** Runs (or re-runs) the full BotGuard attestation and suspends until the minter exists. */
    suspend fun attest()

    /** Mints one PoToken for [identifier] from the attested minter. */
    suspend fun generatePoToken(identifier: String): String

    /** Whether the integrity token is expired (or the generator is already closed). */
    val isExpired: Boolean

    /** Whether [close] has run; a closed generator can never mint again. */
    val isDestroyed: Boolean

    /** Secret-free fields describing how this generator got its challenge; "" when unknown. */
    fun diagnostics(): String = ""

    /** Tears the WebView down. Main thread only. */
    fun close()
}
