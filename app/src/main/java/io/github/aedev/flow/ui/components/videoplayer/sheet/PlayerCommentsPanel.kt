package io.github.aedev.flow.ui.components.videoplayer.sheet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.ui.components.shared.CommentSortChips
import io.github.aedev.flow.ui.components.shared.CommentSortFilter
import io.github.aedev.flow.ui.components.shared.FlowCommentsList
import io.github.aedev.flow.ui.components.shared.FlowMessageComposer
import io.github.aedev.flow.ui.components.shared.rememberMediaArtworkTint

/**
 * Comments rendered as an inline panel rather than a modal sheet, so the video stays visible.
 * Shared by the fullscreen side drawer and the tablet landscape split layout.
 */
@Composable
fun PlayerCommentsPanel(
    comments: List<Comment>,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    selectedFilter: CommentSortFilter,
    onFilterChanged: (CommentSortFilter) -> Unit,
    onSeekMs: (Long) -> Unit,
    onLoadReplies: (Comment) -> Unit,
    onLoadMoreReplies: (Comment) -> Unit,
    onAuthorClick: (String) -> Unit,
    onLoadMore: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    totalText: String? = null,
    artworkUrl: String? = null,
    timedOnly: Boolean = false,
    onTimedChange: ((Boolean) -> Unit)? = null,
    canPostComment: Boolean = false,
    onPostComment: ((String, (Boolean) -> Unit) -> Unit)? = null,
    onToggleLike: (Comment) -> Unit = {},
    onToggleDislike: (Comment) -> Unit = {},
    onPostReply: (Comment, String, (Boolean) -> Unit) -> Unit = { _, _, _ -> },
    onEditComment: (Comment, String, (Boolean) -> Unit) -> Unit = { _, _, _ -> },
    onDeleteComment: (Comment, (Boolean) -> Unit) -> Unit = { _, _ -> },
) {
    val listState = rememberLazyListState()
    val tint = rememberMediaArtworkTint(artworkUrl)
    LaunchedEffect(selectedFilter) {
        listState.scrollToItem(0)
    }

    Column(modifier.fillMaxSize()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.comments),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!totalText.isNullOrBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = totalText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.close),
                )
            }
        }
        CommentSortChips(
            selected = selectedFilter,
            onSelect = onFilterChanged,
            timedOnly = timedOnly,
            onTimedChange = onTimedChange,
            modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
        FlowCommentsList(
            comments = comments,
            isLoading = isLoading,
            listState = listState,
            selectedFilter = selectedFilter,
            onSeekMs = onSeekMs,
            onLoadReplies = onLoadReplies,
            onLoadMoreReplies = onLoadMoreReplies,
            onAuthorClick = onAuthorClick,
            onAvatarClick = {},
            onToggleLike = onToggleLike,
            onToggleDislike = onToggleDislike,
            onPostReply = onPostReply,
            onEditComment = onEditComment,
            onDeleteComment = onDeleteComment,
            isLoadingMore = isLoadingMore,
            onLoadMore = onLoadMore,
            hasMore = hasMore,
            emptyMessageRes = if (timedOnly) R.string.no_timed_comments else R.string.no_comments_yet,
            tint = tint,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        if (canPostComment && onPostComment != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
            FlowMessageComposer(
                placeholderRes = R.string.add_comment_placeholder,
                failureRes = R.string.comment_post_failed,
                sendDescriptionRes = R.string.post_comment,
                onPost = onPostComment,
            )
        }
    }
}

