package io.github.shivytyahoo.glidetube.ui.screens.home

import java.time.DayOfWeek
import java.time.LocalDateTime
import kotlin.math.pow

/**
 * What the guest home feed learns, on this device only: channel and keyword scores from what
 * was watched, how much of it, and what was searched. Nothing here is ever sent anywhere — the
 * server feed stays the signed-in feed, and this is what fills Home while signed out.
 */
internal object GuestTaste {
    private const val DECAY_PER_DAY = 0.98
    private const val CAP = 10.0

    /** Starter topics for an empty taste: the feed seeds from these until watching teaches it. */
    val starterTopics =
        listOf(
            "Music",
            "Gaming",
            "News",
            "Science",
            "Technology",
            "Cooking",
            "Travel",
            "Sports",
            "Comedy",
            "Movies",
            "Podcasts",
            "Documentaries",
            "Fitness",
            "Art",
            "Nature",
            "Cars",
            "Anime",
            "Education",
            "DIY",
            "Finance",
        )

    private val stopWords =
        setOf(
            "shorts",
            "short",
            "youtubeshorts",
            "viral",
            "fyp",
            "trending",
            "video",
            "youtube",
            "new",
            "official",
        )

    /** Words every kind of title uses: never a taste of their own. */
    private val genericWords =
        setOf(
            "the",
            "and",
            "for",
            "with",
            "you",
            "your",
            "this",
            "that",
            "from",
            "are",
            "was",
            "how",
            "what",
            "why",
            "who",
            "when",
            "best",
            "top",
            "full",
            "part",
            "episode",
            "ep",
            "vs",
            "feat",
            "ft",
            "live",
            "music",
            "mv",
            "lyrics",
            "lyric",
            "audio",
            "hd",
            "4k",
            "1080p",
            "2024",
            "2025",
            "2026",
            "2027",
            "day",
            "time",
            "first",
            "last",
            "most",
            "ever",
            "all",
            "can",
            "will",
            "about",
            "just",
            "out",
            "not",
            "but",
            "one",
            "two",
            "my",
            "me",
            "we",
            "our",
            "his",
            "her",
            "they",
            "it",
            "is",
            "in",
            "on",
            "of",
            "to",
            "a",
            "an",
            "at",
            "by",
            "or",
            "be",
            "do",
            "so",
            "no",
            "up",
            "if",
            "as",
        )

    /** Words that describe a video: its tags and the #hashtags in its title. */
    fun tokens(
        title: String,
        keywords: List<String> = emptyList(),
    ): List<String> {
        val tags = Regex("#([^\\s#]{2,40})").findAll(title).map { it.groupValues[1] }.toList()
        return (keywords.take(15) + tags)
            .map { it.trim().lowercase().removePrefix("#") }
            .filter { it.length in 2..40 && it !in stopWords }
            .distinct()
            .take(20)
    }

    /** The runs of letters and digits in [s]. */
    fun letterRuns(s: String): List<String> {
        val out = ArrayList<String>()
        val builder = StringBuilder()
        for (c in s) {
            if (c.isLetterOrDigit()) {
                builder.append(c)
            } else if (builder.isNotEmpty()) {
                out += builder.toString()
                builder.clear()
            }
        }
        if (builder.isNotEmpty()) out += builder.toString()
        return out
    }

    /** A title's own words, for the videos without tags: lower case, everyday words left out. */
    fun words(title: String): List<String> =
        title
            .lowercase()
            .replace(Regex("#[^\\s#]+"), " ")
            .let(::letterRuns)
            .asSequence()
            .filter { it.length >= 2 && it !in genericWords && it !in stopWords && !it.all(Char::isDigit) }
            .distinct()
            .take(12)
            .toList()

    /** How much one viewing says it was liked: skipped < 0 < watched < finished < replayed < liked. */
    fun engagement(
        watchedSec: Double,
        lengthSec: Double,
        liked: Boolean = false,
        disliked: Boolean = false,
    ): Double {
        val len = lengthSec.coerceAtLeast(1.0)
        val ratio = watchedSec.coerceAtLeast(0.0) / len
        var score =
            when {
                watchedSec < 2 || ratio < 0.15 -> -0.8
                ratio < 0.5 -> 0.2
                ratio < 0.85 -> 0.6
                else -> 1.0 + 0.5 * minOf(3, if (ratio >= 2) ratio.toInt() - 1 else 0)
            }
        if (liked) score += 2.0
        if (disliked) score -= 3.0
        return score
    }

    /** The part of the week now: weekday or weekend, morning, afternoon, evening or night. */
    fun bucket(
        hour: Int,
        dayOfWeek: DayOfWeek,
    ): String {
        val weekend = dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY
        val part =
            when (hour) {
                in 6..11 -> "m"
                in 12..17 -> "a"
                in 18..23 -> "e"
                else -> "n"
            }
        return (if (weekend) "we" else "wd") + part
    }

    fun bucketNow(): String {
        val now = LocalDateTime.now()
        return bucket(now.hour, now.dayOfWeek)
    }

    /** One decay step: old tastes fade, tiny ones drop out. */
    fun decayed(
        scores: Map<String, Double>,
        days: Double,
    ): Map<String, Double> {
        if (days < 0.25) return scores
        val factor = DECAY_PER_DAY.pow(days)
        return scores.mapValues { it.value * factor }.filterValues { kotlin.math.abs(it) >= 0.05 }
    }

    fun capped(value: Double): Double = value.coerceIn(-CAP, CAP)

    /** The greeting's time of day. */
    fun greetingPart(hour: Int): Int =
        when (hour) {
            in 5..10 -> 0
            in 11..16 -> 1
            in 17..21 -> 2
            else -> 3
        }
}
