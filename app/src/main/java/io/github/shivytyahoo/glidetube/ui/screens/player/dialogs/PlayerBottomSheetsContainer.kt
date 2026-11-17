package io.github.shivytyahoo.glidetube.ui.screens.player.dialogs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.data.model.Comment
import io.github.shivytyahoo.glidetube.data.model.Video
import io.github.shivytyahoo.glidetube.player.EnhancedPlayerManager
import io.github.shivytyahoo.glidetube.player.EnhancedPlayerState
import io.github.shivytyahoo.glidetube.innertube.YouTube
import io.github.shivytyahoo.glidetube.player.SleepTimerManager
import io.github.shivytyahoo.glidetube.ui.components.shared.CommentSortFilter
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideAlertDialog
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideCommentsBottomSheet
import io.github.shivytyahoo.glidetube.ui.components.videoplayer.sheet.GlideLiveChatBottomSheet
import io.github.shivytyahoo.glidetube.ui.screens.player.VideoPlayerViewModel
import io.github.shivytyahoo.glidetube.ui.screens.player.state.PlayerCommentsUiState
import io.github.shivytyahoo.glidetube.ui.screens.player.state.PlayerScreenState
import io.github.shivytyahoo.glidetube.ui.screens.player.state.PlayerSheet
import io.github.shivytyahoo.glidetube.ui.screens.player.state.VideoPlayerUiState
import io.github.shivytyahoo.glidetube.ui.screens.player.state.transcriptTrackUrl
import io.github.shivytyahoo.glidetube.ui.screens.player.state.visibleComments

@Composable
internal fun PlayerBottomSheetsContainer(
    screenState: PlayerScreenState,
    uiState: VideoPlayerUiState,
    video: Video,
    completeVideo: Video,
    disableShortsPlayer: Boolean,
    showShortsPlayerPrompt: Boolean,
    viewModel: VideoPlayerViewModel,
    playerState: EnhancedPlayerState,
    commentsUiState: PlayerCommentsUiState,
    commentsEnabled: Boolean = true,
    onLoadMoreComments: (videoId: String) -> Unit = {},
    onSelectCommentSort: (CommentSortFilter) -> Unit = {},
    mediaSheetExpandedHeight: Dp? = null,
    mediaSheetCollapsedHeight: Dp = 0.dp,
    onPlayAsShort: (String) -> Unit,
    onLoadReplies: (Comment) -> Unit = {},
    onLoadMoreReplies: (Comment) -> Unit = {},
    onNavigateToChannel: ((String) -> Unit)? = null,
    hostedInSidePanel: Boolean = false,
    onMediaSheetProgressChange: (Float) -> Unit = {},
) {
    val visibleComments = commentsUiState.visibleComments(screenState)

    val handleSeek: (Long) -> Unit =
        remember {
            { positionMs -> EnhancedPlayerManager.getInstance().seekTo(positionMs) }
        }

    LaunchedEffect(Unit) {
        SleepTimerManager.attachToPlayer(
            player = EnhancedPlayerManager.getInstance().getPlayer(),
        ) {
            EnhancedPlayerManager.getInstance().pause()
        }
    }

    // Comments Bottom Sheet
    if (screenState.activeSheet == PlayerSheet.Comments() && commentsEnabled && !hostedInSidePanel) {
        GlideCommentsBottomSheet(
            comments = visibleComments,
            isLoading = commentsUiState.isLoading,
            selectedFilter = screenState.commentSortFilter,
            totalText = commentsUiState.totalText,
            artworkUrl = video.thumbnailUrl,
            timedOnly = screenState.commentsTimedOnly,
            onTimedChange = { screenState.commentsTimedOnly = it },
            onFilterChanged = onSelectCommentSort,
            onLoadReplies = onLoadReplies,
            onLoadMoreReplies = onLoadMoreReplies,
            onSeekMs = handleSeek,
            isLoadingMore = commentsUiState.isLoadingMore,
            hasMore = commentsUiState.hasMore,
            onLoadMore = { onLoadMoreComments(video.id) },
            onAuthorClick = { authorChannelRef ->
                screenState.closeSheet()
                onNavigateToChannel?.invoke(authorChannelRef)
            },
            expandedHeight = mediaSheetExpandedHeight,
            collapsedHeight = mediaSheetCollapsedHeight,
            onSheetProgressChange = onMediaSheetProgressChange,
            onDismiss = { screenState.closeSheet() },
            canPostComment = commentsUiState.canPostComment,
            onPostComment = { text, onResult -> viewModel.postComment(text, onResult) },
            onToggleLike = { viewModel.toggleCommentLike(it) },
            onToggleDislike = { viewModel.toggleCommentDislike(it) },
            onPostReply = { comment, text, onResult -> viewModel.postCommentReply(comment, text, onResult) },
            onEditComment = { comment, text, onResult -> viewModel.editComment(comment, text, onResult) },
            onDeleteComment = { comment, onResult -> viewModel.deleteComment(comment, onResult) },
        )
    }

    if (screenState.activeSheet == PlayerSheet.LiveChat() && uiState.isLiveChatAvailable) {
        GlideLiveChatBottomSheet(
            messages = uiState.liveChatMessages,
            isLoading = uiState.isLiveChatLoading,
            expandedHeight = mediaSheetExpandedHeight,
            collapsedHeight = mediaSheetCollapsedHeight,
            onSheetProgressChange = onMediaSheetProgressChange,
            onDismiss = { screenState.closeSheet() },
            canSend = YouTube.isSignedIn(),
            onSendMessage = { text, onResult -> viewModel.sendLiveChatMessage(text, onResult) },
            onDeleteMessage = { message, onResult -> viewModel.deleteLiveChatMessage(message, onResult) },
        )
    }

    // Description Bottom Sheet
    if (screenState.activeSheet == PlayerSheet.Description && !hostedInSidePanel) {
        PlayerDescriptionSheetHost(
            video = video,
            uiState = uiState,
            viewModel = viewModel,
            asSidePanel = false,
            expandedHeight = mediaSheetExpandedHeight,
            onDismiss = { screenState.closeSheet() },
            hasTranscriptTrack = transcriptTrackUrl(playerState, screenState) != null,
            onChaptersClick = { screenState.open(PlayerSheet.Chapters) },
            onTranscriptClick = { screenState.open(PlayerSheet.Transcript) },
            onChannelClick = { channelId ->
                screenState.closeSheet()
                onNavigateToChannel?.invoke(channelId)
            },
            collapsedHeight = mediaSheetCollapsedHeight,
            onSheetProgressChange = onMediaSheetProgressChange,
        )
    }

    if (screenState.activeSheet == PlayerSheet.Transcript && !hostedInSidePanel) {
        PlayerTranscriptSheetHost(
            viewModel = viewModel,
            screenState = screenState,
            trackUrl = transcriptTrackUrl(playerState, screenState),
            tracks = playerState.availableSubtitles,
            chapters = uiState.chapters,
            artworkUrl = video.thumbnailUrl,
            asSidePanel = false,
            expandedHeight = mediaSheetExpandedHeight,
            onDismiss = { screenState.closeSheet() },
            collapsedHeight = mediaSheetCollapsedHeight,
            onSheetProgressChange = onMediaSheetProgressChange,
        )
    }

    // Chapters Bottom Sheet
    if (screenState.activeSheet == PlayerSheet.Chapters && !hostedInSidePanel) {
        PlayerChaptersSheetHost(
            screenState = screenState,
            chapters = uiState.chapters,
            thumbnailUrl = video.thumbnailUrl,
            asSidePanel = false,
            expandedHeight = mediaSheetExpandedHeight,
            onDismiss = { screenState.closeSheet() },
            collapsedHeight = mediaSheetCollapsedHeight,
            onSheetProgressChange = onMediaSheetProgressChange,
        )
    }

    if (screenState.activeSheet == PlayerSheet.Queue && !hostedInSidePanel) {
        PlayerQueueSheetHost(
            asSidePanel = false,
            expandedHeight = mediaSheetExpandedHeight,
            onDismiss = { screenState.closeSheet() },
            collapsedHeight = mediaSheetCollapsedHeight,
            onSheetProgressChange = onMediaSheetProgressChange,
        )
    }

    if (screenState.activeSheet == PlayerSheet.SleepTimer && !hostedInSidePanel) {
        PlayerSleepTimerSheetHost(
            asSidePanel = false,
            expandedHeight = mediaSheetExpandedHeight,
            onDismiss = { screenState.closeSheet() },
            collapsedHeight = mediaSheetCollapsedHeight,
            onSheetProgressChange = onMediaSheetProgressChange,
        )
    }

    // Shorts Suggestion Dialog
    if (screenState.showShortsPrompt && !disableShortsPlayer && showShortsPlayerPrompt) {
        ShortsSuggestionDialog(
            onPlayAsShort = {
                screenState.showShortsPrompt = false
                onPlayAsShort(completeVideo.id)
            },
            onDismiss = { screenState.showShortsPrompt = false },
        )
    }
}

/**
 * Dialog suggesting to play a short video in the Shorts player.
 */
@Composable
private fun ShortsSuggestionDialog(
    onPlayAsShort: () -> Unit,
    onDismiss: () -> Unit,
) {
    GlideAlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.SmartDisplay, null) },
        title = {
            Text(
                text = stringResource(R.string.play_mode_suggestion_title),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Text(stringResource(R.string.play_mode_suggestion_body))
        },
        confirmButton = {
            TextButton(onClick = onPlayAsShort) {
                Text(stringResource(R.string.shorts_player))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}
