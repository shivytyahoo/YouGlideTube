package io.github.aedev.flow.ui.components.shared

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.pages.VideoCommentSort
import io.github.aedev.flow.utils.formatTimeAgo
import io.github.aedev.flow.utils.parseTimestampMs
import io.github.aedev.flow.utils.relativedate.RelativeUploadDateParser

enum class CommentSortFilter(
    @param:StringRes val labelRes: Int,
) {
    TOP(R.string.filter_top),
    NEWEST(R.string.filter_newest),
    OLDEST(R.string.filter_oldest),
}

/**
 * The continuation that serves [filter], out of the orders the section offered.
 *
 * The menu is matched by position rather than by title, because the titles arrive in the user's
 * language. YouTube serves two orders and no third: Oldest reads the chronological one and reverses
 * what it has, which is why it maps to the same continuation as Newest.
 */
fun videoCommentSortFor(
    options: List<VideoCommentSort>,
    filter: CommentSortFilter,
): VideoCommentSort? =
    when (filter) {
        CommentSortFilter.TOP -> options.getOrNull(0)
        CommentSortFilter.NEWEST, CommentSortFilter.OLDEST -> options.getOrNull(1)
    }

/**
 * What a video's comment list shows for the current chips.
 *
 * Top and Newest are already the order the server returned, so they are left alone; only Oldest
 * re-orders, and only over the pages loaded so far.
 */
fun applyVideoCommentFilters(
    comments: List<Comment>,
    filter: CommentSortFilter,
    timedOnly: Boolean,
): List<Comment> {
    val filtered = if (timedOnly) comments.filter { it.richText?.hasTimestamp == true } else comments
    return if (filter == CommentSortFilter.OLDEST) {
        sortCommentsByFilter(filtered, filter)
    } else {
        filtered
    }
}

/**
 * When a comment was posted, as epoch millis, from its relative "3 days ago" text — in the
 * request's own language, via the same parser the feeds use. Null when the text carries no
 * parsable age; callers must never read that as "now", or a stale comment sorts as new.
 */
private fun commentPostedMs(comment: Comment): Long? = RelativeUploadDateParser.parse(comment.publishedTime, YouTube.locale.hl)

/**
 * Sorts comments for the given filter, keeping pinned comments first.
 *
 * The loaded list always arrives newest first (the chronological continuation, or the
 * community feed), so Oldest sorts by posted time ascending and — within one coarse bucket
 * such as "2 days ago", where every comment shares one timestamp — keeps the input reversed,
 * oldest of the bucket first. Newest does the same in reverse: ties keep the server's order.
 */
fun sortCommentsByFilter(
    comments: List<Comment>,
    filter: CommentSortFilter,
): List<Comment> {
    val pinned = comments.filter { it.isPinned }
    val unpinned = comments.filterNot { it.isPinned }
    val sortedUnpinned =
        when (filter) {
            CommentSortFilter.TOP -> unpinned.sortedByDescending { it.likeCount }
            CommentSortFilter.NEWEST -> unpinned.sortedByDescending { commentPostedMs(it) ?: Long.MIN_VALUE }
            CommentSortFilter.OLDEST -> unpinned.asReversed().sortedBy { commentPostedMs(it) ?: Long.MAX_VALUE }
        }
    return pinned + sortedUnpinned
}

/** Converts a "H:MM:SS" / "MM:SS" comment timestamp into milliseconds, or 0 when it is not one. */
fun commentTimestampToMs(timestamp: String): Long = parseTimestampMs(timestamp) ?: 0L

fun formatAuthorName(author: String): String {
    val trimmed = author.trim()
    return if (trimmed.startsWith("@")) {
        trimmed
    } else {
        "@$trimmed"
    }
}

/**
 * Channel reference for a comment author, in one of the forms `youtubeChannelUrl` accepts:
 * a `UC…` channel id, an `@handle`, or a bare handle.
 *
 * Callers must forward the value unchanged — re-prefixing it with `@` turns a channel id into a
 * handle that does not exist, which is why comment authors used to open a 404 page.
 */
fun commentAuthorChannelRef(comment: Comment): String = comment.authorChannelId.trim().ifBlank { comment.author.trim().removePrefix("@") }

internal fun toHighQualityAvatarUrl(url: String): String {
    if (url.isBlank()) return url

    return url
        .replace(Regex("=s\\d+"), "=s1024")
        .replace(Regex("/s\\d+-"), "/s1024-")
        .replace(Regex("=w\\d+-h\\d+"), "=w1024-h1024")
}

@Composable
internal fun localizedCommentPublishedTime(publishedTime: String): String {
    val editedSuffix = Regex("\\s*\\(?edited\\)?\\s*$", RegexOption.IGNORE_CASE)
    val isEdited = editedSuffix.containsMatchIn(publishedTime)
    val time = formatTimeAgo(publishedTime.replace(editedSuffix, "").trim())
    return if (isEdited) {
        stringResource(R.string.comment_time_edited_template, time, stringResource(R.string.comment_edited))
    } else {
        time
    }
}
