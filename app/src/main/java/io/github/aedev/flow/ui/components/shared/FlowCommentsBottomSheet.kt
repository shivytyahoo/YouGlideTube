package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.ui.components.shared.FlowBottomSheet
import io.github.aedev.flow.ui.components.shared.defaultSheetExpandedHeight
import io.github.aedev.flow.ui.components.shared.rememberFlowBottomSheetState

@Composable
fun FlowCommentsBottomSheet(
    comments: List<Comment>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onSeekMs: (Long) -> Unit = {},
    onFilterChanged: (CommentSortFilter) -> Unit = {},
    onLoadReplies: (Comment) -> Unit = {},
    onLoadMoreReplies: (Comment) -> Unit = {},
    selectedFilter: CommentSortFilter = CommentSortFilter.TOP,
    totalText: String? = null,
    artworkUrl: String? = null,
    timedOnly: Boolean = false,
    onTimedChange: ((Boolean) -> Unit)? = null,
    isLoadingMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    hasMore: Boolean = false,
    onAuthorClick: (String) -> Unit = {},
    onAvatarClick: (String) -> Unit = {},
    canPostComment: Boolean = false,
    onPostComment: ((String, (Boolean) -> Unit) -> Unit)? = null,
    onToggleLike: (Comment) -> Unit = {},
    onToggleDislike: (Comment) -> Unit = {},
    onPostReply: (Comment, String, (Boolean) -> Unit) -> Unit = { _, _, _ -> },
    onEditComment: (Comment, String, (Boolean) -> Unit) -> Unit = { _, _, _ -> },
    onDeleteComment: (Comment, (Boolean) -> Unit) -> Unit = { _, _ -> },
    expandedHeight: Dp? = null,
    collapsedHeight: Dp = 0.dp,
    onSheetProgressChange: (Float) -> Unit = {},
    dismissOnOutsideTap: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberFlowBottomSheetState()
    val commentsListState = rememberLazyListState()
    val tint = rememberMediaArtworkTint(artworkUrl)

    LaunchedEffect(selectedFilter) {
        commentsListState.scrollToItem(0)
    }

    FlowBottomSheet(
        onDismiss = onDismiss,
        modifier = modifier,
        state = sheetState,
        expandedHeight = expandedHeight ?: defaultSheetExpandedHeight(),
        collapsedHeight = collapsedHeight,
        dismissOnOutsideTap = dismissOnOutsideTap,
        shape = RectangleShape,
        containerColor = MaterialTheme.colorScheme.surface,
        onProgressChange = onSheetProgressChange,
        header = { dragModifier ->
            // Not FlowSheetHeader: the sort chips sit under the title row, inside the same padding
            // and above the divider, which no header parameter can express.
            CommentsSheetHeader(
                selectedFilter = selectedFilter,
                totalText = totalText,
                timedOnly = timedOnly,
                onTimedChange = onTimedChange,
                onFilterChanged = onFilterChanged,
                onClose = { sheetState.dismiss() },
                dragModifier = dragModifier,
            )
        },
    ) {
        FlowCommentsList(
            comments = comments,
            isLoading = isLoading,
            listState = commentsListState,
            selectedFilter = selectedFilter,
            onSeekMs = onSeekMs,
            onLoadReplies = onLoadReplies,
            onLoadMoreReplies = onLoadMoreReplies,
            onAuthorClick = onAuthorClick,
            onAvatarClick = onAvatarClick,
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
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
        )
        if (canPostComment && onPostComment != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
            CommentsSheetComposer(onPost = onPostComment)
        }
    }
}

/** The add-a-comment bar pinned under the list; only shown when the section allows posting. */
@Composable
private fun CommentsSheetComposer(
    onPost: (String, (Boolean) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }
    var posting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    failed = false
                },
                placeholder = { Text(stringResource(R.string.add_comment_placeholder)) },
                enabled = !posting,
                maxLines = 4,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                enabled = text.isNotBlank() && !posting,
                onClick = {
                    posting = true
                    onPost(text.trim()) { posted ->
                        posting = false
                        if (posted) {
                            text = ""
                        } else {
                            failed = true
                        }
                    }
                },
            ) {
                Icon(
                    Icons.Rounded.Send,
                    contentDescription = stringResource(R.string.post_comment),
                )
            }
        }
        if (failed) {
            Text(
                text = stringResource(R.string.comment_post_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommentsSheetHeader(
    selectedFilter: CommentSortFilter,
    totalText: String?,
    timedOnly: Boolean,
    onTimedChange: ((Boolean) -> Unit)?,
    onFilterChanged: (CommentSortFilter) -> Unit,
    onClose: () -> Unit,
    dragModifier: Modifier,
) {
    Column(modifier = dragModifier) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            BottomSheetDefaults.DragHandle()
        }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.comments),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!totalText.isNullOrBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = totalText,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                }
            }
            CommentSortChips(
                selected = selectedFilter,
                onSelect = onFilterChanged,
                timedOnly = timedOnly,
                onTimedChange = onTimedChange,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        HorizontalDivider(
            color =
                MaterialTheme.colorScheme.outlineVariant
                    .copy(alpha = FlowSheetHeaderDefaults.DividerAlpha),
        )
    }
}
