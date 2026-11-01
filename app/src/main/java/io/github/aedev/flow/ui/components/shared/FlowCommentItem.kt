package io.github.aedev.flow.ui.components.shared

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.utils.formatLikeCount

private val ThreadHorizontalPadding = 16.dp
private val ThreadLineWidth = 2.dp

/** Width of the elbow column: the turn, plus the gap before the reply's avatar. */
private val ThreadConnectorWidth = 20.dp
private val ThreadElbowRadius = 8.dp

/** Where the line turns: the vertical centre of a reply's 24 dp avatar under its 8 dp top padding. */
private val ThreadElbowY = 20.dp

/** A reply to a reply starts its own branch just inside its parent's text. */
private val NestedThreadStart = 12.dp

/** Centre of the parent avatar: the row's start padding plus half the 40 dp avatar, less the line. */
private val ThreadLineStart = 35.dp
private val ThreadLineGap = 15.dp

@Composable
fun FlowCommentItem(
    comment: Comment,
    onSeekMs: (Long) -> Unit,
    onLoadReplies: (Comment) -> Unit,
    onLoadMoreReplies: (Comment) -> Unit,
    onAuthorClick: (String) -> Unit = {},
    onAvatarClick: (String) -> Unit = {},
    onToggleLike: (Comment) -> Unit = {},
    onToggleDislike: (Comment) -> Unit = {},
    onPostReply: (Comment, String, (Boolean) -> Unit) -> Unit = { _, _, _ -> },
    onEditComment: (Comment, String, (Boolean) -> Unit) -> Unit = { _, _, _ -> },
    onDeleteComment: (Comment, (Boolean) -> Unit) -> Unit = { _, _ -> },
    tint: MediaArtworkTint? = null,
) {
    var isExpanded by remember { mutableStateOf(false) }
    var showReplyComposer by remember { mutableStateOf(false) }
    var showEditComposer by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isRepliesVisible by remember { mutableStateOf(false) }
    var isOverflowing by remember { mutableStateOf(false) }
    var isLoadingReplies by remember { mutableStateOf(false) }
    var commentTextLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var showFullSizeImage by remember { mutableStateOf(false) }

    val uriHandler = LocalUriHandler.current

    LaunchedEffect(comment.replies) {
        isLoadingReplies = false
    }

    val accent = tint?.accent ?: MaterialTheme.colorScheme.primary
    val chipColor = tint?.container ?: MaterialTheme.colorScheme.surfaceContainerHighest
    val commentText = rememberCommentText(comment, accent)

    if (showFullSizeImage) {
        FullSizeImageDialog(
            imageUrl = toHighQualityAvatarUrl(comment.authorThumbnail),
            onDismiss = { showFullSizeImage = false },
        )
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ThreadHorizontalPadding)
                    .padding(top = 12.dp, bottom = 4.dp),
        ) {
            ChannelAvatarImage(
                url = comment.authorThumbnail,
                contentDescription = null,
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable {
                            onAvatarClick(comment.authorThumbnail)
                            showFullSizeImage = true
                        },
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                if (comment.isPinned) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = stringResource(R.string.pinned_comment),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = comment.pinnedByText?.takeIf { it.isNotBlank() } ?: stringResource(R.string.pinned_by_creator),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                CommentAuthorRow(
                    comment = comment,
                    onAuthorClick = onAuthorClick,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Box(modifier = Modifier.animateContentSize()) {
                    SelectionContainer {
                        BasicText(
                            text = commentText.annotated,
                            inlineContent = commentText.inlineContent,
                            style =
                                MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    lineHeight = 20.sp,
                                ),
                            maxLines = if (isExpanded) Int.MAX_VALUE else 4,
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { result ->
                                commentTextLayoutResult = result
                                if (result.hasVisualOverflow) isOverflowing = true
                            },
                            modifier =
                                Modifier
                                    .richTextHighlights(
                                        text = commentText.annotated,
                                        layoutResult = { commentTextLayoutResult },
                                        color = chipColor,
                                    ).pointerInput(commentText.annotated) {
                                        detectTapGestures(
                                            onTap = { tapOffset ->
                                                val result = commentTextLayoutResult ?: return@detectTapGestures
                                                val offset = result.getOffsetForPosition(tapOffset)
                                                val handled =
                                                    commentText.handleTap(
                                                        offset = offset,
                                                        onSeekMs = onSeekMs,
                                                        onOpenUrl = { url ->
                                                            runCatching { uriHandler.openUri(url) }
                                                        },
                                                        onAuthorClick = onAuthorClick,
                                                    )
                                                if (!handled && !isExpanded && isOverflowing) isExpanded = true
                                            },
                                        )
                                    },
                        )
                    }
                }

                if (isOverflowing && !isExpanded) {
                    Text(
                        text = stringResource(R.string.read_more),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier =
                            Modifier
                                .padding(top = 4.dp)
                                .clickable { isExpanded = true },
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                CommentEngagementRow(
                    comment = comment,
                    onToggleLike = { onToggleLike(comment) },
                    onToggleDislike = { onToggleDislike(comment) },
                    onReplyClick =
                        if (comment.replyParams != null) {
                            { showReplyComposer = true }
                        } else {
                            null
                        },
                    onEditClick =
                        if (comment.isOwn && comment.editParams != null) {
                            { showEditComposer = true }
                        } else {
                            null
                        },
                    onDeleteClick =
                        if (comment.isOwn && comment.deleteAction != null) {
                            { showDeleteDialog = true }
                        } else {
                            null
                        },
                )

                if (showEditComposer) {
                    CommentReplyComposer(
                        initialText = comment.editPrefill ?: comment.text,
                        placeholderText = stringResource(R.string.comment_edit_placeholder),
                        submitText = stringResource(R.string.save),
                        failureText = stringResource(R.string.comment_update_failed),
                        onSubmit = { text, onResult -> onEditComment(comment, text, onResult) },
                        onClose = { showEditComposer = false },
                    )
                }

                if (showDeleteDialog) {
                    CommentDeleteDialog(
                        onConfirm = { onResult -> onDeleteComment(comment, onResult) },
                        onDismiss = { showDeleteDialog = false },
                    )
                }

                if (showReplyComposer) {
                    CommentReplyComposer(
                        initialText = "",
                        onSubmit = { text, onResult ->
                            onPostReply(comment, text) { ok ->
                                if (ok) isRepliesVisible = true
                                onResult(ok)
                            }
                        },
                        onClose = { showReplyComposer = false },
                    )
                }

                if (comment.replyCount > 0) {
                    RepliesToggle(
                        replyCount = comment.replyCount,
                        expanded = isRepliesVisible,
                        loading = isLoadingReplies,
                        onClick = {
                            if (!isRepliesVisible && comment.replies.isEmpty()) {
                                isLoadingReplies = true
                                onLoadReplies(comment)
                            }
                            isRepliesVisible = !isRepliesVisible
                        },
                    )
                }
            }
        }

        if (isRepliesVisible && comment.replies.isNotEmpty()) {
            val tree = remember(comment.replies) { buildCommentReplyTree(comment.replies) }
            ReplyThread(
                nodes = tree,
                hasMore = comment.repliesPage != null || comment.continuationToken != null,
                startPadding = ThreadLineStart,
                tint = tint,
                onSeekMs = onSeekMs,
                onAuthorClick = onAuthorClick,
                onAvatarClick = onAvatarClick,
                onToggleLike = onToggleLike,
                onToggleDislike = onToggleDislike,
                onPostReply = onPostReply,
                onEditComment = onEditComment,
                onDeleteComment = onDeleteComment,
                onLoadMore = {
                    isLoadingReplies = true
                    onLoadMoreReplies(comment)
                },
            )
        }
    }
}

/**
 * The replies under one comment, and the replies to those.
 *
 * Each reply hangs off the thread line on an elbow, the way a conversation branches: the line runs
 * down from the parent's avatar, turns into each reply, and carries on past it to the next one. A
 * reply that answered another reply sits one branch deeper, which is the only thing that tells the
 * two apart — YouTube stores a single flat level and marks the nesting with an `@handle` alone.
 */
@Composable
private fun ReplyThread(
    nodes: List<CommentReplyNode>,
    hasMore: Boolean,
    startPadding: Dp,
    tint: MediaArtworkTint?,
    onSeekMs: (Long) -> Unit,
    onAuthorClick: (String) -> Unit,
    onAvatarClick: (String) -> Unit,
    onToggleLike: (Comment) -> Unit,
    onToggleDislike: (Comment) -> Unit,
    onPostReply: (Comment, String, (Boolean) -> Unit) -> Unit,
    onEditComment: (Comment, String, (Boolean) -> Unit) -> Unit,
    onDeleteComment: (Comment, (Boolean) -> Unit) -> Unit,
    onLoadMore: () -> Unit,
) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = startPadding, end = ThreadHorizontalPadding, bottom = 4.dp),
    ) {
        nodes.forEachIndexed { index, node ->
            val continues = index != nodes.lastIndex || hasMore
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                ThreadConnector(
                    color = lineColor,
                    continuesBelow = continues,
                    modifier = Modifier.fillMaxHeight(),
                )
                Column(modifier = Modifier.weight(1f)) {
                    FlowReplyItem(
                        reply = node.comment,
                        onSeekMs = onSeekMs,
                        onAuthorClick = onAuthorClick,
                        onAvatarClick = onAvatarClick,
                        onToggleLike = onToggleLike,
                        onToggleDislike = onToggleDislike,
                        onPostReply = onPostReply,
                        onEditComment = onEditComment,
                        onDeleteComment = onDeleteComment,
                        tint = tint,
                    )
                    if (node.children.isNotEmpty()) {
                        ReplyThread(
                            nodes = node.children,
                            hasMore = false,
                            startPadding = NestedThreadStart,
                            tint = tint,
                            onSeekMs = onSeekMs,
                            onAuthorClick = onAuthorClick,
                            onAvatarClick = onAvatarClick,
                            onToggleLike = onToggleLike,
                            onToggleDislike = onToggleDislike,
                            onPostReply = onPostReply,
                            onEditComment = onEditComment,
                            onDeleteComment = onDeleteComment,
                            onLoadMore = onLoadMore,
                        )
                    }
                }
            }
        }

        if (hasMore) {
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                ThreadConnector(
                    color = lineColor,
                    continuesBelow = false,
                    modifier = Modifier.fillMaxHeight(),
                )
                Text(
                    text = stringResource(R.string.load_more_replies),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier =
                        Modifier
                            .padding(start = ThreadLineGap)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(onClick = onLoadMore)
                            .padding(vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * The line into one reply: down from above, a quarter turn towards the text, and on down to the
 * next reply when there is one.
 */
@Composable
private fun ThreadConnector(
    color: Color,
    continuesBelow: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.width(ThreadConnectorWidth)) {
        val stroke = ThreadLineWidth.toPx()
        val elbowY = ThreadElbowY.toPx()
        val radius = ThreadElbowRadius.toPx()
        val x = stroke / 2f

        drawLine(
            color = color,
            start = Offset(x, 0f),
            end = Offset(x, (elbowY - radius).coerceAtLeast(0f)),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = -90f,
            useCenter = false,
            topLeft = Offset(x, elbowY - 2 * radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawLine(
            color = color,
            start = Offset(x + radius, elbowY),
            end = Offset(size.width, elbowY),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        if (continuesBelow) {
            drawLine(
                color = color,
                start = Offset(x, (elbowY - radius).coerceAtLeast(0f)),
                end = Offset(x, size.height),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** "12 replies" with the chevron that says it opens, in place of the rule that used to sit here. */
@Composable
private fun RepliesToggle(
    replyCount: Int,
    expanded: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .padding(top = 4.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Icon(
            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text =
                if (expanded) {
                    stringResource(R.string.hide_replies)
                } else {
                    pluralStringResource(R.plurals.view_replies_template, replyCount, replyCount)
                },
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        if (loading) {
            Spacer(modifier = Modifier.width(8.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
internal fun CommentAuthorRow(
    comment: Comment,
    onAuthorClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        CommentAuthorName(
            comment = comment,
            onAuthorClick = onAuthorClick,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (comment.isVerified || comment.isArtist) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Rounded.Verified,
                contentDescription = stringResource(R.string.verified),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp),
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = localizedCommentPublishedTime(comment.publishedTime),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CommentAuthorName(
    comment: Comment,
    onAuthorClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = formatAuthorName(comment.author)
    val clickable =
        modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = ripple(),
            onClick = { onAuthorClick(commentAuthorChannelRef(comment)) },
        )
    if (comment.isCreator) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = CircleShape,
            modifier = clickable,
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        return
    }
    Text(
        text = name,
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = clickable,
    )
}

/**
 * The like/dislike votes, and the heart the creator left on this comment.
 *
 * The votes are live when the comment's toolbar surface carried its own perform-action
 * endpoints (a signed-in section); without them the row stays the old read-only display,
 * because there is no action this app can honestly perform.
 */
@Composable
internal fun CommentEngagementRow(
    comment: Comment,
    modifier: Modifier = Modifier,
    onToggleLike: () -> Unit = {},
    onToggleDislike: () -> Unit = {},
    onReplyClick: (() -> Unit)? = null,
    onEditClick: (() -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null,
) {
    val canVote =
        comment.likeAction != null || comment.unlikeAction != null ||
            comment.dislikeAction != null || comment.undislikeAction != null
    val likeText =
        (if (comment.isLiked) comment.likeCountLikedText else "").takeIf { it.isNotBlank() }
            ?: comment.likeCountText.takeIf { it.isNotBlank() }
            ?: comment.likeCount.takeIf { it > 0 }?.let(::formatLikeCount)
    val likeContent: @Composable () -> Unit = {
        Icon(
            imageVector = if (comment.isLiked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
            contentDescription = stringResource(R.string.like),
            tint =
                if (comment.isLiked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.size(14.dp),
        )
        if (likeText != null) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = likeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (canVote) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onToggleLike)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                likeContent()
            }
            Spacer(modifier = Modifier.width(4.dp))
            Box(
                modifier =
                    Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onToggleDislike)
                        .padding(4.dp),
            ) {
                Icon(
                    imageVector = if (comment.isDisliked) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                    contentDescription = stringResource(R.string.dislike),
                    tint =
                        if (comment.isDisliked) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    modifier = Modifier.size(14.dp),
                )
            }
        } else {
            likeContent()
        }
        if (onReplyClick != null) {
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = stringResource(R.string.reply),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onReplyClick)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        if (comment.isHearted) {
            Spacer(modifier = Modifier.width(14.dp))
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = comment.heartedByText ?: stringResource(R.string.comment_hearted_by_creator),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(14.dp),
            )
        }
        if (onEditClick != null || onDeleteClick != null) {
            Spacer(modifier = Modifier.width(6.dp))
            CommentOptionsMenu(onEditClick = onEditClick, onDeleteClick = onDeleteClick)
        }
    }
}

/** The overflow menu on the viewer's own comment: exactly the writes its menu offered. */
@Composable
private fun CommentOptionsMenu(
    onEditClick: (() -> Unit)?,
    onDeleteClick: (() -> Unit)?,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.comment_options),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (onEditClick != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.comment_edit)) },
                    onClick = {
                        open = false
                        onEditClick()
                    },
                )
            }
            if (onDeleteClick != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete)) },
                    onClick = {
                        open = false
                        onDeleteClick()
                    },
                )
            }
        }
    }
}

/** Delete asks once before it fires; a refused delete leaves the comment where it was. */
@Composable
internal fun CommentDeleteDialog(
    onConfirm: ((Boolean) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var deleting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    // A delete whose answer never arrives must not trap the reader behind this dialog:
    // after a bounded wait the dialog unlocks and reports the failure instead.
    LaunchedEffect(deleting) {
        if (deleting) {
            delay(12_000)
            if (deleting) {
                deleting = false
                failed = true
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = { Text(stringResource(R.string.comment_delete_title)) },
        text = {
            Text(
                text =
                    if (failed) {
                        stringResource(R.string.comment_delete_failed)
                    } else {
                        stringResource(R.string.comment_delete_body)
                    },
            )
        },
        confirmButton = {
            TextButton(
                enabled = !deleting,
                onClick = {
                    deleting = true
                    failed = false
                    onConfirm { ok ->
                        deleting = false
                        if (ok) onDismiss() else failed = true
                    }
                },
            ) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deleting) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * The inline reply box under a comment. [onSubmit] reports through its callback; success
 * closes the box (via [onClose]), failure keeps the text and shows why it stayed.
 */
@Composable
internal fun CommentReplyComposer(
    initialText: String,
    onSubmit: (String, (Boolean) -> Unit) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    placeholderText: String = stringResource(R.string.add_reply_placeholder),
    submitText: String = stringResource(R.string.reply),
    failureText: String = stringResource(R.string.comment_post_failed),
) {
    var text by remember { mutableStateOf(initialText) }
    var posting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    // Same trap guard as the delete dialog: an unanswered submit unlocks after a
    // bounded wait and shows the failure, instead of disabling the box forever.
    LaunchedEffect(posting) {
        if (posting) {
            delay(12_000)
            if (posting) {
                posting = false
                failed = true
            }
        }
    }
    Column(modifier = modifier.padding(top = 8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                failed = false
            },
            placeholder = { Text(placeholderText) },
            enabled = !posting,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (failed) {
                Text(
                    text = failureText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
            }
            TextButton(onClick = onClose, enabled = !posting) {
                Text(stringResource(R.string.cancel))
            }
            TextButton(
                enabled = text.isNotBlank() && !posting,
                onClick = {
                    posting = true
                    onSubmit(text.trim()) { ok ->
                        posting = false
                        if (ok) onClose() else failed = true
                    }
                },
            ) {
                Text(submitText)
            }
        }
    }
}
