package io.github.shivytyahoo.glidetube.player.stream

import java.io.IOException

private val StatusPattern = Regex("""status=(\w+)""")
private val GoneStatuses = setOf("ERROR", "UNPLAYABLE")
private val BotWallReason = Regex("""not a bot|confirm you.re""", RegexOption.IGNORE_CASE)

// Clients the ladder skipped without asking say nothing about the video.
private val NotAskedReason = Regex("""demoted|no visitorData|PoToken unavailable|not asked""")

/** Why YouTube refused a video to this viewer, when the refusal is about the viewer and not the video. */
enum class PlaybackBlock {
    /** "Sign in to confirm you're not a bot" on every client: this visitor is flagged. */
    BOT_WALL,

    /** The wall held for a fresh visitor too, so the network is the likelier cause. */
    NETWORK_WALL,

    /** The video needs a signed-in account, for its age or its audience. */
    LOGIN_REQUIRED,
}

/** A failed extraction whose [block] explains it better than a generic error. */
class PlaybackBlockedException(
    val block: PlaybackBlock,
) : Exception("YouTube refused playback: $block")

/** Every client said the video itself is gone, so retrying it can only fail the same way. */
class VideoUnavailableException(
    videoId: String,
) : IOException("$videoId is unavailable on every client")

/**
 * Whether a failed extraction means the video itself is gone (removed, unavailable here), as
 * opposed to a failure worth retrying. Only playability statuses count: reasons are localized.
 * A bot check, a timeout or an exception anywhere means the answer is not known, so it is false.
 * Any other reason without a status came from a client that answered OK, so the video exists:
 * label and kids videos are UNPLAYABLE on the app clients but served, ciphered, on the web ones.
 */
internal object PlayabilityVerdict {
    fun isGone(failureReasons: List<String>): Boolean {
        if (failureReasons.any { "BOT_WALL" in it || "timeout" in it || "exception=" in it }) return false
        val statuses =
            failureReasons
                .filterNot { NotAskedReason.containsMatchIn(it) }
                .map { StatusPattern.find(it)?.groupValues?.get(1) }
        return statuses.isNotEmpty() && statuses.all { it in GoneStatuses }
    }

    /**
     * Every client that gave a verdict about the viewer was bot-walled, so the refusal is
     * about this viewer only. Only verdicts count: a bot-wall tag, a bot-wall reason, or a
     * playability status. Timeouts, transport errors and the decoder's "0/N formats solved"
     * say nothing about the visitor, and letting one veto the wall is what left a fully
     * bot-walled visitor with no recovery — the re-roll never fired.
     */
    fun everyClientWalled(failureReasons: List<String>): Boolean {
        val verdicts = failureReasons.filter { "BOT_WALL" in it || "status=" in it || isBotWall(it) }
        return verdicts.isNotEmpty() && verdicts.all { "BOT_WALL" in it || isBotWall(it) }
    }

    /** Extraction runs in en-US, so the reason is English; "confirm your age" is not a bot wall. */
    fun isBotWall(reason: String?): Boolean = reason != null && BotWallReason.containsMatchIn(reason)

    /** [freshVisitorWalled]: the wall also held after the visitor was replaced for it. */
    fun block(
        failureReasons: List<String>,
        freshVisitorWalled: Boolean = false,
    ): PlaybackBlock? =
        when {
            everyClientWalled(failureReasons) -> if (freshVisitorWalled) PlaybackBlock.NETWORK_WALL else PlaybackBlock.BOT_WALL
            failureReasons.any { StatusPattern.find(it)?.groupValues?.get(1) == "LOGIN_REQUIRED" } -> PlaybackBlock.LOGIN_REQUIRED
            else -> null
        }
}
