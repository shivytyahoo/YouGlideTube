package io.github.aedev.flow.ui.components.shared

import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.aedev.flow.player.preview.FeedPreviewController
import io.github.aedev.flow.ui.components.shared.FocusLiftDefaults
import io.github.aedev.flow.ui.components.shared.focusLift

/**
 * The shared preview player's surface, drawn over a card's thumbnail. It stays transparent
 * until the first preview frame renders, so the thumbnail underneath is the placeholder and
 * there is no black flash — a [TextureView] is used instead of a PlayerView precisely because
 * it honours view alpha, which a SurfaceView would ignore.
 *
 * The speaker button at the bottom-start toggles this preview's sound. It is a separate
 * clickable inside the card, so tapping it never opens the video; tapping the rest of the
 * card opens the full player from the preview's position.
 */
@Composable
fun FeedPreviewSurface(ready: Boolean, showMuteButton: Boolean = true) {
    val isMuted by FeedPreviewController.isMuted.collectAsState()
    Box(
        modifier = Modifier.fillMaxSize().alpha(if (ready) 1f else 0f),
    ) {
        AndroidView(
            factory = { context ->
                TextureView(context).also { view ->
                    FeedPreviewController.currentPlayer?.setVideoTextureView(view)
                }
            },
            update = { view ->
                FeedPreviewController.currentPlayer?.setVideoTextureView(view)
            },
            onRelease = { view ->
                FeedPreviewController.currentPlayer?.clearVideoTextureView(view)
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (showMuteButton) {
            // A focus stop of its own with a lift and a white ring: on TV the remote can
            // reach it from the previewing card, and the ring shows when it has.
            val muteInteraction = remember { MutableInteractionSource() }
            val muteFocused by muteInteraction.collectIsFocusedAsState()
            IconButton(
                onClick = { FeedPreviewController.toggleMute() },
                interactionSource = muteInteraction,
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .focusLift(muteInteraction, FocusLiftDefaults.SMALL_SCALE)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                        // No border at all unfocused — a 0.dp border still draws a 1px
                        // hairline around the button.
                        .then(
                            if (muteFocused) Modifier.border(2.dp, Color.White, CircleShape) else Modifier,
                        ),
            ) {
                Icon(
                    imageVector = if (isMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                    contentDescription = null,
                    tint = Color.White,
                )
            }
        }
    }
}
