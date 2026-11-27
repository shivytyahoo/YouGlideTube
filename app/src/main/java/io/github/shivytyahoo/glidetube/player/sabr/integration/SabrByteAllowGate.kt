package io.github.shivytyahoo.glidetube.player.sabr.integration

/**
 * The byte-level allow-gate for the SABR fetch: how far the fetch may run ahead of what the
 * player consumed, in bytes. Engages at 24 MB ahead and stays engaged until the lead drains to
 * 18 MB, so the follow-up loop does not flap a request every poll for a few hundred kilobytes.
 *
 * A pure state machine, so the hysteresis is unit-testable without a session: the orchestrator
 * feeds it the pipes' outstanding bytes and holds the request while it says engaged.
 */
internal class SabrByteAllowGate(
    private val aheadBytes: Long = DEFAULT_AHEAD_BYTES,
    private val nearBytes: Long = DEFAULT_NEAR_BYTES,
) {
    var engaged: Boolean = false
        private set

    fun update(aheadBytesNow: Long): Boolean {
        if (engaged) {
            if (aheadBytesNow < aheadBytes - nearBytes) engaged = false
        } else if (aheadBytesNow >= aheadBytes) {
            engaged = true
        }
        return engaged
    }

    fun reset() {
        engaged = false
    }

    companion object {
        const val DEFAULT_AHEAD_BYTES = 24L * 1024L * 1024L
        const val DEFAULT_NEAR_BYTES = 6L * 1024L * 1024L
    }
}
