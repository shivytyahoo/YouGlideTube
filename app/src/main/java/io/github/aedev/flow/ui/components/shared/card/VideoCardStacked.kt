package io.github.aedev.flow.ui.components.shared.card

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.components.shared.ChannelAvatarStack
import io.github.aedev.flow.ui.components.shared.rememberMediaPalette
import io.github.aedev.flow.ui.theme.videoCardGradient
import io.github.aedev.flow.ui.components.shared.LocalDpadNavigation
import io.github.aedev.flow.ui.components.shared.videoMetadataLine

/** The thumbnail across the full width with the details beneath: feeds and grids. */
@Composable
internal fun VideoCardStacked(
    video: Video,
    onClick: () -> Unit,
    showChannel: Boolean,
    useInternalPadding: Boolean,
    reason: String?,
    whyShown: String?,
    modifier: Modifier = Modifier,
    previewContent: (@Composable BoxScope.() -> Unit)? = null,
) {
    val state = rememberVideoCardState(video)
    val cardPreferences = LocalVideoCardPreferences.current
    val actions = LocalVideoCardActions.current
    // Morphe's cards carry their own color each: a video's is its thumbnail palette
    // base, read cache-only so a tint never downloads a picture. Until the palette
    // resolves the base is the surface fallback, which reads as "no palette" — the
    // card stays tonal instead of flashing a wrong color.
    val palette = rememberMediaPalette(state.thumbnailUrl, animated = false, cacheOnly = true)
    val cardScheme = MaterialTheme.colorScheme
    val hasPalette = palette.base != cardScheme.surface

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (useInternalPadding) Modifier.padding(horizontal = VideoCardDefaults.Inset) else Modifier),
    ) {
        // A floating glass card, not a bordered box: Morphe's cards carry no grey
        // outline — they lift off the background with a light tonal fill and a soft
        // rim. The batch 6 outlineVariant border read as a plain border around every
        // video, which is what the user rejected; a white rim at low alpha plus a
        // soft shadow gives the glass edge instead. Morphe's own gradients still do
        // not belong here — they live on its per-app home cards, not on video
        // thumbnails, and the batch 4 uniform blue-teal gradient is what striped
        // the feed.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .shadow(2.dp, MaterialTheme.shapes.largeIncreased)
                    .clip(MaterialTheme.shapes.largeIncreased)
                    .then(
                        if (hasPalette) {
                            Modifier.background(videoCardGradient(palette.base), MaterialTheme.shapes.largeIncreased)
                        } else {
                            Modifier.background(cardScheme.surfaceContainerHigh, MaterialTheme.shapes.largeIncreased)
                        },
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.12f), MaterialTheme.shapes.largeIncreased)
                    .videoCardClickable(state, onClick),
        ) {
            Column {
                Box(Modifier.fillMaxWidth()) {
                    VideoCardThumbnail(
                        state = state,
                        isUpcoming = video.isUpcoming,
                        shape =
                            MaterialTheme.shapes.largeIncreased.copy(
                                bottomStart = CornerSize(0.dp),
                                bottomEnd = CornerSize(0.dp),
                            ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // A preview surface over the thumbnail, clipped to the same top-rounded
                    // shape; the surface itself crossfades in, so the thumbnail stays visible
                    // until the first preview frame renders.
                    if (previewContent != null) {
                        Box(
                            Modifier
                                .matchParentSize()
                                .clip(
                                    MaterialTheme.shapes.largeIncreased.copy(
                                        bottomStart = CornerSize(0.dp),
                                        bottomEnd = CornerSize(0.dp),
                                    ),
                                ),
                        ) {
                            previewContent()
                        }
                    }
                }

                // The avatar's 48 dp target overhangs its 40 dp image by 4 dp, so the row gives those 4 dp back
                // and the avatar still lines up where it always has.
                CardDetailsRow(
                    spacing = if (showChannel) 8.dp else 12.dp,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                    avatar = {
                        if (showChannel) {
                            ChannelAvatarStack(
                                urls = state.avatarUrls,
                                contentDescription = state.channelName,
                                avatarSize = VideoCardDefaults.AvatarSize,
                                modifier =
                                    Modifier
                                        .minimumInteractiveComponentSize()
                                        .clip(CircleShape)
                                        .videoCardChannelClickable(state),
                            )
                        }
                    },
                    actions = {
                        VideoCardSideActions(
                            state = state,
                            showWatched = false,
                            onWatched = actions.onWatched,
                            alignTo = 12.dp,
                        )
                    },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = state.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (hasPalette) palette.onBase else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )

                        Text(
                            text =
                                videoMetadataLine(
                                    video = video,
                                    isUpcoming = video.isUpcoming,
                                    channelName = state.channelName,
                                    includeChannel = showChannel,
                                ),
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                if (hasPalette) {
                                    palette.onBase.copy(alpha = 0.75f)
                                } else if (video.isUpcoming) {
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
                }

                if (!LocalDpadNavigation.current && (cardPreferences.actionsEnabled || cardPreferences.markWatchedEnabled)) {
                    VideoCardFeedback(
                        state = state,
                        showRating = cardPreferences.actionsEnabled,
                        showWatched = cardPreferences.markWatchedEnabled,
                        actions = actions,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 16.dp),
                    )
                }
            }
        }
    }

    VideoCardSheets(state = state, showChannel = showChannel, whyShown = whyShown)
}

/** Whether [width] holds the avatar, the menu and a title of at least [minTitleWidth] side by side. */
internal fun showsAvatarBesideTitle(
    width: Int,
    avatarWidth: Int,
    actionsWidth: Int,
    gap: Int,
    minTitleWidth: Int,
): Boolean = avatarWidth > 0 && width - actionsWidth - gap - avatarWidth - gap >= minTitleWidth

/** Narrower than this, a title beside the avatar wraps to a few letters a line. */
private val MinTitleWidth = 80.dp

/**
 * The details under the thumbnail: avatar, text, then the menu. A card too narrow for a readable
 * title beside the avatar, such as three cards across a phone, leaves the avatar out; the channel
 * name is still in the line under the title.
 */
@Composable
private fun CardDetailsRow(
    spacing: Dp,
    avatar: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    text: @Composable () -> Unit,
) {
    Layout(
        contents = listOf(avatar, text, actions),
        modifier = modifier,
    ) { (avatarMeasurables, textMeasurables, actionMeasurables), constraints ->
        val width = constraints.maxWidth
        val gap = spacing.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val actionPlaceables = actionMeasurables.map { it.measure(loose) }
        val actionsWidth = actionPlaceables.maxOfOrNull { it.width } ?: 0
        val avatarPlaceables = avatarMeasurables.map { it.measure(loose) }
        val avatarWidth = avatarPlaceables.maxOfOrNull { it.width } ?: 0
        val showAvatar = showsAvatarBesideTitle(width, avatarWidth, actionsWidth, gap, MinTitleWidth.roundToPx())
        val textX = if (showAvatar) avatarWidth + gap else 0
        val textWidth = (width - actionsWidth - gap - textX).coerceAtLeast(0)
        val textPlaceables = textMeasurables.map { it.measure(loose.copy(minWidth = textWidth, maxWidth = textWidth)) }
        val height =
            maxOf(
                textPlaceables.maxOfOrNull { it.height } ?: 0,
                if (showAvatar) avatarPlaceables.maxOfOrNull { it.height } ?: 0 else 0,
                actionPlaceables.maxOfOrNull { it.height } ?: 0,
            ).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            if (showAvatar) avatarPlaceables.forEach { it.placeRelative(0, 0) }
            textPlaceables.forEach { it.placeRelative(textX, 0) }
            actionPlaceables.forEach { it.placeRelative(width - actionsWidth, 0) }
        }
    }
}
