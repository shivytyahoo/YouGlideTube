package io.github.aedev.flow.ui.components.shared.card

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.components.shared.rememberMediaPalette
import io.github.aedev.flow.ui.components.shared.videoMetadataLine
import io.github.aedev.flow.ui.theme.videoCardGradient

/** Thumbnail on the left, details on the right: lists, side panes and search rows. */
@Composable
internal fun VideoCardRow(
    video: Video,
    onClick: () -> Unit,
    showChannel: Boolean,
    thumbnailWidth: Dp,
    reason: String?,
    whyShown: String?,
    modifier: Modifier = Modifier,
) {
    val state = rememberVideoCardState(video)
    val cardPreferences = LocalVideoCardPreferences.current
    val actions = LocalVideoCardActions.current
    // A negative count is the older "no count reported" sentinel; a row that declares itself
    // upcoming counts too. The badge and the metadata line read the same answer.
    val isUpcomingRow = video.isUpcoming || video.viewCount < 0L

    // A bare row is not a card: the related list, search rows and side panes drew
    // thumbnail + text straight on the page background, which is why they never read
    // as Morphe cards. The row gets the same container as a stacked card, with the
    // same per-video gradient — thumbnail left, text on the gradient right, exactly
    // Morphe's icon-left-text-right card shape.
    val palette = rememberMediaPalette(state.thumbnailUrl, animated = false, cacheOnly = true)
    val rowScheme = MaterialTheme.colorScheme
    val hasPalette = palette.base != rowScheme.surface
    val ink = if (hasPalette) palette.onBase else rowScheme.onSurface
    val inkSoft = if (hasPalette) palette.onBase.copy(alpha = 0.75f) else rowScheme.onSurfaceVariant
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = VideoCardDefaults.Inset, vertical = 6.dp)
                .shadow(2.dp, MaterialTheme.shapes.largeIncreased)
                .clip(MaterialTheme.shapes.largeIncreased)
                .then(
                    if (hasPalette) {
                        Modifier.background(videoCardGradient(palette.base), MaterialTheme.shapes.largeIncreased)
                    } else {
                        Modifier.background(rowScheme.surfaceContainerHigh, MaterialTheme.shapes.largeIncreased)
                    },
                )
                .border(1.dp, Color.White.copy(alpha = 0.12f), MaterialTheme.shapes.largeIncreased)
                .videoCardClickable(state, onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        ) {
            VideoCardThumbnail(
                state = state,
                isUpcoming = isUpcomingRow,
                shape = MaterialTheme.shapes.medium,
                width = thumbnailWidth,
            )

            Spacer(modifier = Modifier.width(VideoCardDefaults.Inset))

            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(6.dp))

                if (showChannel) {
                    Text(
                        text = state.channelName,
                        style = MaterialTheme.typography.bodySmall,
                        color = inkSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.videoCardChannelClickable(state),
                    )
                }

                Text(
                    text =
                        videoMetadataLine(
                            video = video,
                            isUpcoming = isUpcomingRow,
                            channelName = state.channelName,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (hasPalette) {
                            inkSoft
                        } else if (isUpcomingRow) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                MembersOnlyLabel(video)
                reason?.let { CardReasonLabel(it) }
            }

            VideoCardSideActions(
                state = state,
                showWatched = cardPreferences.markWatchedEnabled,
                onWatched = actions.onWatched,
                alignTo = 14.dp,
            )
        }
    }

    VideoCardSheets(state = state, showChannel = showChannel, whyShown = whyShown)
}
