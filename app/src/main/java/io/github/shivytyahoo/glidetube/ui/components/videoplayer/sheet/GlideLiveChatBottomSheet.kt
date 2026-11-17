package io.github.shivytyahoo.glidetube.ui.components.videoplayer.sheet

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.data.model.LiveChatMessage
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideBottomSheet
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideMessageComposer
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideSheetHeader
import io.github.shivytyahoo.glidetube.ui.components.shared.defaultSheetExpandedHeight
import io.github.shivytyahoo.glidetube.ui.components.shared.rememberFlowBottomSheetState

// Draggable live-chat bottom sheet (portrait)
@Composable
fun GlideLiveChatBottomSheet(
    messages: List<LiveChatMessage>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    expandedHeight: Dp? = null,
    collapsedHeight: Dp = 0.dp,
    onSheetProgressChange: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
    canSend: Boolean = false,
    onSendMessage: ((String, (Boolean) -> Unit) -> Unit)? = null,
    onDeleteMessage: ((LiveChatMessage, (Boolean) -> Unit) -> Unit)? = null,
) {
    val sheetState = rememberFlowBottomSheetState()
    GlideBottomSheet(
        onDismiss = onDismiss,
        modifier = modifier,
        state = sheetState,
        expandedHeight = expandedHeight ?: defaultSheetExpandedHeight(),
        collapsedHeight = collapsedHeight,
        dismissOnOutsideTap = false,
        shape = RectangleShape,
        containerColor = MaterialTheme.colorScheme.surface,
        onProgressChange = onSheetProgressChange,
        header = { dragModifier ->
            GlideSheetHeader(
                title = stringResource(R.string.live_chat),
                onClose = { sheetState.dismiss() },
                modifier = dragModifier,
            )
        },
    ) {
        LiveChatList(
            messages = messages,
            isLoading = isLoading,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            contentPadding = PaddingValues(vertical = 8.dp),
            onDeleteMessage = onDeleteMessage,
        )
        if (canSend && onSendMessage != null) {
            GlideMessageComposer(
                placeholderRes = R.string.live_chat_message_placeholder,
                failureRes = R.string.live_chat_send_failed,
                sendDescriptionRes = R.string.send_live_chat_message,
                onPost = onSendMessage,
            )
        }
    }
}
