package io.github.shivytyahoo.glidetube.ui.components.shared.card

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.data.model.Video
import io.github.shivytyahoo.glidetube.data.model.VideoCollaborator
import io.github.shivytyahoo.glidetube.player.stream.PlaybackPrefetcher
import io.github.shivytyahoo.glidetube.ui.components.layout.navigation.LocalMediaNavigator
import io.github.shivytyahoo.glidetube.ui.components.shared.FocusLiftDefaults
import io.github.shivytyahoo.glidetube.ui.components.shared.LocalDpadNavigation
import io.github.shivytyahoo.glidetube.ui.components.shared.focusLift
import io.github.shivytyahoo.glidetube.ui.components.shared.onMenuKey
import io.github.shivytyahoo.glidetube.ui.components.shared.pressScale
import io.github.shivytyahoo.glidetube.ui.theme.extendedColors
import io.github.shivytyahoo.glidetube.utils.NetworkState
import io.github.shivytyahoo.glidetube.utils.avatarImageIdentityKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long a press must be held still before the card warms the video behind it. */
private const val STILL_PRESS_WARM_MS = 90L

internal fun Video.channelAvatarUrls(collaborators: List<VideoCollaborator> = emptyList()): List<String> {
    if (collaborators.size <= 1) {
        return (listOf(channelThumbnailUrl) + channelThumbnailUrls)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.avatarImageIdentityKey() }
            .take(1)
    }

    return collaborators
        .map { it.thumbnailUrl }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.avatarImageIdentityKey() }
        .take(3)
}

/**
 * YouTube omits the view count on members-only uploads and shows this badge instead, so the row above
 * it legitimately reads "2 days ago" with no views.
 */
@Composable
internal fun MembersOnlyLabel(video: Video) {
    val label = video.membersOnlyText ?: return
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = MaterialTheme.extendedColors.success,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.extendedColors.success,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Why the feed shows this video, such as "From a channel you watch". */
@Composable
internal fun CardReasonLabel(reason: String) {
    Text(
        text = reason,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The whole card opens the video, and a long press opens its quick actions. TalkBack announces the
 * long press by name, since the gesture alone is undiscoverable. On a D-pad the card lifts on focus
 * and the Menu key opens the quick actions too. Either gesture also warms stream extraction for
 * the video: the quick actions can lead straight into playback, and a tap from there then starts
 * from the in-flight extraction — or its cached result — instead of a cold ladder.
 */
@Composable
internal fun Modifier.videoCardClickable(
    state: VideoCardState,
    onClick: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val context = LocalContext.current
    val videoId = state.video.id
    // A press held still warms the video before the tap lands: a scroll cancels the press, and a
    // tap shorter than the hold releases first, so only a real dwell pays for the prefetch — and
    // only off a metered connection, where a warm the viewer never opens would cost data.
    LaunchedEffect(interactionSource, videoId) {
        var pendingWarm: Job? = null
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    pendingWarm?.cancel()
                    pendingWarm =
                        launch {
                            delay(STILL_PRESS_WARM_MS)
                            if (!NetworkState.isMetered(context)) {
                                PlaybackPrefetcher.prefetch(videoId)
                                PlaybackPrefetcher.prefetchDetails(videoId)
                            }
                        }
                }

                is PressInteraction.Release,
                is PressInteraction.Cancel,
                -> {
                    pendingWarm?.cancel()
                }
            }
        }
    }
    return pressScale(interactionSource)
        .focusLift(interactionSource)
        // The lift alone is too quiet across a room: on TV the focused card also gets a
        // primary-colour ring, so the remote's pointer is unmistakable. No border at all
        // off TV — a 0.dp border still draws a 1px hairline, which is how every card on
        // the phone got an outline it never asked for.
        .then(
            if (isFocused && LocalDpadNavigation.current) {
                Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.large)
            } else {
                Modifier
            },
        ).onMenuKey {
            PlaybackPrefetcher.prefetch(state.video.id)
            if (!NetworkState.isMetered(context)) PlaybackPrefetcher.prefetchDetails(state.video.id)
            state.sheets.showQuickActions = true
        }.combinedClickable(
            interactionSource = interactionSource,
            indication = ripple(focusRingShape = MaterialTheme.shapes.large),
            onClickLabel = null,
            onLongClickLabel = stringResource(R.string.more_options),
            onLongClick = {
                PlaybackPrefetcher.prefetch(state.video.id)
                if (!NetworkState.isMetered(context)) PlaybackPrefetcher.prefetchDetails(state.video.id)
                state.sheets.showQuickActions = true
            },
            onClick = onClick,
        )
}

/**
 * The channel as a tap target: the avatar on a stacked card, the name on a row. It is a focus
 * stop of its own on TV too, so the remote can reach the channel — the old skip kept one stop
 * per card, which left the remote with no way to a channel at all. On a phone nothing changes:
 * this is still just a tap.
 */
@Composable
internal fun Modifier.videoCardChannelClickable(state: VideoCardState): Modifier {
    val navigator = LocalMediaNavigator.current
    val interactionSource = remember { MutableInteractionSource() }
    return focusLift(interactionSource, FocusLiftDefaults.SMALL_SCALE)
        .clickable(
            interactionSource = interactionSource,
            indication = ripple(),
            role = Role.Button,
            onClickLabel = stringResource(R.string.go_to_channel),
        ) { state.openChannel(navigator) }
}

/**
 * The ⋮ button and, on a row whose thumbnail leaves the height for it, the watched toggle beneath,
 * both at the full 48 dp target. [alignTo] pulls them up so the ⋮ icon sits on the title's first line.
 * A stacked card keeps its watched toggle in the feedback group instead, so its text keeps the width.
 * On a D-pad the card's long press and the Menu key open the same menu, so the title takes the room.
 */
@Composable
internal fun VideoCardSideActions(
    state: VideoCardState,
    showWatched: Boolean,
    onWatched: (Video) -> Unit,
    alignTo: Dp,
    modifier: Modifier = Modifier,
) {
    if (LocalDpadNavigation.current) return
    Column(modifier = modifier.offset(y = -alignTo)) {
        IconButton(onClick = { state.sheets.showQuickActions = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.more_options),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showWatched) {
            val isWatched = state.isWatched
            IconToggleButton(
                checked = isWatched,
                onCheckedChange = { if (!isWatched) onWatched(state.video) },
            ) {
                Icon(
                    imageVector = if (isWatched) Icons.Filled.Visibility else Icons.Outlined.Visibility,
                    contentDescription = stringResource(R.string.mark_as_watched),
                    tint = if (isWatched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
