package io.github.shivytyahoo.glidetube.player.sabr

/**
 * Decides when the native SABR path should be engaged and preferred over direct-URL playback.
 *
 * The guiding rule is guarded/additive: SABR is only reached for as an *upgrade* over what
 * direct/NewPipe extraction already produced, and preferred for playback only when it actually
 * delivers a higher resolution. A video that plays today via direct URLs can therefore never
 * regress to a SABR session that might be slower or less reliable.
 *
 * The upgrade never gates first frame: the playable direct result ships immediately and the
 * upgrade runs in the background, bounded by [UPGRADE_BUDGET_MS]. A win that lands after the
 * direct result has shipped is recorded, never swapped in mid-playback — Media3 owns the
 * prepared source and this codebase has no safe mechanism for swapping a prepared ladder, so
 * the direct result keeps playing.
 */
object SabrRoutingPolicy {
    /**
     * A direct ladder capped below this height is treated as "quality-incomplete" — YouTube is
     * likely serving the higher rungs as SABR-only, so a WEB+PoToken+SABR upgrade is worth the
     * extra round-trip. Direct ladders at or above this height are accepted as-is (no added
     * latency), keeping the common fast path untouched.
     */
    const val QUALITY_UPGRADE_FLOOR = 720

    /**
     * How long the background SABR upgrade may run. It never delays first frame: the playable
     * direct result ships as soon as it resolves, and the upgrade runs concurrently in the
     * background. The upgrade mints a token and may download the player script, which on a cold
     * start can run past the whole load budget — that cost is paid off the critical path, and a
     * video that was already playable never waits on it.
     */
    const val UPGRADE_BUDGET_MS = 3_000L

    /** True when the best direct video height is low enough to justify attempting a SABR upgrade. */
    fun shouldAttemptSabrUpgrade(directMaxHeight: Int): Boolean = directMaxHeight < QUALITY_UPGRADE_FLOOR

    /**
     * Whether to route playback through SABR. Prefer it when a caller forces escalation (e.g. a
     * 403/expiry reload where direct URLs just failed) or when the resolved SABR ladder is
     * strictly higher-resolution than the best playable direct stream.
     */
    fun shouldPreferSabr(
        forceEscalation: Boolean,
        sabrHeight: Int,
        directMaxHeight: Int,
    ): Boolean = forceEscalation || (sabrHeight > 0 && sabrHeight > directMaxHeight)

    /**
     * Whether an upgrade attempt replaces the direct ladder. [sabrHeight] is null when the attempt
     * resolved nothing or ran out of [UPGRADE_BUDGET_MS]; the direct result then always survives.
     */
    fun upgradeReplacesDirect(
        directMaxHeight: Int,
        sabrHeight: Int?,
    ): Boolean = sabrHeight != null && sabrHeight > directMaxHeight

    /**
     * Whether a background upgrade that finished after the direct result shipped would have won.
     * The answer is recorded (log/diagnostics) only: a late win is never swapped into playback,
     * because Media3 owns the prepared source and there is no safe mid-playback ladder swap.
     * [sabrHeight] is null when the attempt resolved nothing or ran out of [UPGRADE_BUDGET_MS];
     * the direct result then always survives.
     */
    fun backgroundUpgradeWon(
        directMaxHeight: Int,
        sabrHeight: Int?,
    ): Boolean = upgradeReplacesDirect(directMaxHeight, sabrHeight)
}
