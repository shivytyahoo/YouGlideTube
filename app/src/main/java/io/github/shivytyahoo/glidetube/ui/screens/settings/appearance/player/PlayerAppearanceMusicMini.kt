package io.github.shivytyahoo.glidetube.ui.screens.settings.appearance.player

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.data.local.CoverShape
import io.github.shivytyahoo.glidetube.data.local.MiniBarBackground
import io.github.shivytyahoo.glidetube.data.local.MiniBarProgress
import io.github.shivytyahoo.glidetube.data.local.MiniCoverMotion
import io.github.shivytyahoo.glidetube.data.local.MiniCoverShape
import io.github.shivytyahoo.glidetube.data.local.MiniCoverTap
import io.github.shivytyahoo.glidetube.data.local.MiniLongPress
import io.github.shivytyahoo.glidetube.ui.components.musicplayer.common.rememberMusicPlayerAppearance
import io.github.shivytyahoo.glidetube.ui.components.musicplayer.mini.MiniBarProgressLine
import io.github.shivytyahoo.glidetube.ui.components.musicplayer.mini.MiniBarSurface
import io.github.shivytyahoo.glidetube.ui.components.musicplayer.mini.coverShapeLabel
import io.github.shivytyahoo.glidetube.ui.components.musicplayer.mini.isLine
import io.github.shivytyahoo.glidetube.ui.components.musicplayer.mini.labelRes
import io.github.shivytyahoo.glidetube.ui.components.musicplayer.mini.miniBarProgress
import io.github.shivytyahoo.glidetube.ui.components.settings.SettingsListScope
import io.github.shivytyahoo.glidetube.ui.components.settings.SettingsPreviewSheet
import io.github.shivytyahoo.glidetube.ui.components.settings.nav
import io.github.shivytyahoo.glidetube.ui.components.settings.switch
import io.github.shivytyahoo.glidetube.ui.components.settings.toggleGroup
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideToggleOption
import io.github.shivytyahoo.glidetube.ui.components.shared.MediaMiniBarDefaults
import io.github.shivytyahoo.glidetube.ui.screens.settings.index.PlayerAppearanceIndex

internal enum class MusicMiniSheet { COVER_SHAPES, COVER_PAUSED, BAR_PROGRESS, BUTTONS, BACKGROUND }

/**
 * What the music mini player group shows and which of its sheets is open. Every field is state, so
 * the settings list reads them as it builds and rebuilds when one changes.
 */
@Stable
internal class MusicMiniSettings {
    var coverShape by mutableStateOf(MiniCoverShape.CIRCLE)
    var coverShapes by mutableStateOf(emptyList<CoverShape>())
    var pausedShape by mutableStateOf<CoverShape?>(CoverShape.CIRCLE)
    var coverShapeOptions by mutableStateOf(emptyList<GlideToggleOption<MiniCoverShape>>())
    var coverMotionOptions by mutableStateOf(emptyList<GlideToggleOption<MiniCoverMotion>>())
    var coverShapesLabel by mutableStateOf("")
    var pausedShapeLabel by mutableStateOf("")
    var barProgressLabel by mutableStateOf("")
    var buttonsLabel by mutableStateOf("")
    var backgroundLabel by mutableStateOf("")
    var coverTapOptions by mutableStateOf(emptyList<GlideToggleOption<MiniCoverTap>>())
    var longPressOptions by mutableStateOf(emptyList<GlideToggleOption<MiniLongPress>>())
    var sheet by mutableStateOf<MusicMiniSheet?>(null)
}

@Composable
internal fun rememberMusicMiniSettings(viewModel: PlayerAppearanceViewModel): MusicMiniSettings {
    val settings = remember { MusicMiniSettings() }
    val coverShape by viewModel.miniCoverShape.collectAsStateWithLifecycle()
    val coverShapes by viewModel.miniCoverShapes.collectAsStateWithLifecycle()
    val pausedShape by viewModel.miniCoverPausedShape.collectAsStateWithLifecycle()
    settings.coverShape = coverShape
    settings.coverShapes = coverShapes
    settings.pausedShape = pausedShape
    settings.coverShapeOptions =
        listOf(
            GlideToggleOption(MiniCoverShape.MORPHING, stringResource(R.string.player_appearance_mini_cover_morphing)),
            GlideToggleOption(MiniCoverShape.CIRCLE, stringResource(R.string.player_appearance_mini_cover_circle)),
        )
    settings.coverMotionOptions =
        listOf(
            GlideToggleOption(MiniCoverMotion.ON_PLAY, stringResource(R.string.player_appearance_mini_cover_motion_on_play)),
            GlideToggleOption(MiniCoverMotion.ALWAYS, stringResource(R.string.player_appearance_mini_cover_motion_always)),
            GlideToggleOption(MiniCoverMotion.STILL, stringResource(R.string.player_appearance_mini_cover_motion_still)),
        )
    settings.coverShapesLabel = pluralStringResource(R.plurals.player_appearance_cover_shapes_count, coverShapes.size, coverShapes.size)
    settings.pausedShapeLabel = stringResource(pausedShape?.let { coverShapeLabel(it) } ?: R.string.player_appearance_cover_paused_keep)
    val barProgress by viewModel.miniBarProgress.collectAsStateWithLifecycle()
    settings.barProgressLabel = stringResource(barProgressLabel(barProgress))
    val background by viewModel.miniBarBackground.collectAsStateWithLifecycle()
    settings.backgroundLabel = stringResource(miniBackgroundLabel(background))
    val buttons by viewModel.miniBarButtons.collectAsStateWithLifecycle()
    val buttonNames = buttons.map { stringResource(it.labelRes()) }
    settings.buttonsLabel = buttonNames.joinToString(", ").ifEmpty { stringResource(R.string.player_appearance_mini_buttons_none) }
    settings.coverTapOptions =
        listOf(
            GlideToggleOption(MiniCoverTap.OPEN, stringResource(R.string.player_appearance_mini_cover_tap_open)),
            GlideToggleOption(MiniCoverTap.PLAY_PAUSE, stringResource(R.string.player_appearance_mini_cover_tap_play)),
        )
    settings.longPressOptions =
        listOf(
            GlideToggleOption(MiniLongPress.NOTHING, stringResource(R.string.player_appearance_mini_long_press_nothing)),
            GlideToggleOption(MiniLongPress.OPTIONS, stringResource(R.string.player_appearance_mini_long_press_options)),
            GlideToggleOption(MiniLongPress.LIKE, stringResource(R.string.player_appearance_mini_long_press_like)),
            GlideToggleOption(MiniLongPress.QUEUE, stringResource(R.string.player_appearance_mini_long_press_queue)),
        )
    return settings
}

/** The music mini player's own rows: its cover, and how that cover moves. */
internal fun SettingsListScope.musicMiniGroup(
    settings: MusicMiniSettings,
    viewModel: PlayerAppearanceViewModel,
) = group(key = "player_appearance.music_mini", header = R.string.settings_section_music_mini_player) {
    toggleGroup(PlayerAppearanceIndex.miniCoverShape, settings.coverShapeOptions, settings.coverShape, viewModel::setMiniCoverShape)
    if (settings.coverShape == MiniCoverShape.MORPHING) {
        toggleGroup(
            PlayerAppearanceIndex.miniCoverMotion,
            settings.coverMotionOptions,
            viewModel.miniCoverMotion,
            viewModel::setMiniCoverMotion,
        )
        nav(PlayerAppearanceIndex.miniCoverShapes, value = settings.coverShapesLabel, onClick = {
            settings.sheet =
                MusicMiniSheet.COVER_SHAPES
        })
        nav(
            PlayerAppearanceIndex.miniCoverPausedShape,
            value = settings.pausedShapeLabel,
            onClick = { settings.sheet = MusicMiniSheet.COVER_PAUSED },
        )
    }
    nav(
        PlayerAppearanceIndex.miniBarProgress,
        value = settings.barProgressLabel,
        onClick = { settings.sheet = MusicMiniSheet.BAR_PROGRESS },
    )
    switch(PlayerAppearanceIndex.miniCoverRing, viewModel.miniCoverRing, viewModel::setMiniCoverRing)
    nav(PlayerAppearanceIndex.miniBackground, value = settings.backgroundLabel, onClick = { settings.sheet = MusicMiniSheet.BACKGROUND })
    nav(PlayerAppearanceIndex.miniButtons, value = settings.buttonsLabel, onClick = { settings.sheet = MusicMiniSheet.BUTTONS })
    toggleGroup(PlayerAppearanceIndex.miniCoverTap, settings.coverTapOptions, viewModel.miniCoverTap, viewModel::setMiniCoverTap)
    toggleGroup(PlayerAppearanceIndex.miniLongPress, settings.longPressOptions, viewModel.miniLongPress, viewModel::setMiniLongPress)
}

@Composable
internal fun MusicMiniSheets(
    settings: MusicMiniSettings,
    viewModel: PlayerAppearanceViewModel,
) {
    when (settings.sheet) {
        MusicMiniSheet.COVER_SHAPES -> {
            CoverShapeSheet(
                title = stringResource(R.string.player_appearance_cover_shapes_title),
                subtitle = stringResource(R.string.player_appearance_cover_shapes_subtitle),
                selected = settings.coverShapes.toSet(),
                multiple = true,
                onPick = { it?.let(viewModel::toggleMiniCoverShape) },
                onDismiss = { settings.sheet = null },
            )
        }

        MusicMiniSheet.COVER_PAUSED -> {
            CoverShapeSheet(
                title = stringResource(R.string.player_appearance_cover_paused_title),
                subtitle = stringResource(R.string.player_appearance_cover_paused_subtitle),
                selected = setOf(settings.pausedShape),
                multiple = false,
                onPick = viewModel::setMiniCoverPausedShape,
                onDismiss = { settings.sheet = null },
                keepTrackShape = true,
            )
        }

        MusicMiniSheet.BUTTONS -> {
            val buttons by viewModel.miniBarButtons.collectAsStateWithLifecycle()
            MiniButtonsSheet(chosen = buttons, onChange = viewModel::setMiniBarButtons, onDismiss = { settings.sheet = null })
        }

        MusicMiniSheet.BACKGROUND -> {
            val selected by viewModel.miniBarBackground.collectAsStateWithLifecycle()
            SettingsPreviewSheet(
                title = stringResource(R.string.player_appearance_mini_background_title),
                subtitle = stringResource(R.string.player_appearance_mini_background_subtitle),
                options = MiniBarBackground.entries,
                selected = selected,
                label = { stringResource(miniBackgroundLabel(it)) },
                onSelect = viewModel::setMiniBarBackground,
                onDismiss = { settings.sheet = null },
                preview = { MiniBackgroundPreview(it) },
            )
        }

        MusicMiniSheet.BAR_PROGRESS -> {
            val selected by viewModel.miniBarProgress.collectAsStateWithLifecycle()
            SettingsPreviewSheet(
                title = stringResource(R.string.player_appearance_bar_progress_title),
                subtitle = stringResource(R.string.player_appearance_bar_progress_subtitle),
                options = MiniBarProgress.entries,
                selected = selected,
                label = { stringResource(barProgressLabel(it)) },
                onSelect = viewModel::setMiniBarProgress,
                onDismiss = { settings.sheet = null },
                preview = { BarProgressPreview(it) },
            )
        }

        null -> {
            Unit
        }
    }
}

@StringRes
private fun barProgressLabel(style: MiniBarProgress): Int =
    when (style) {
        MiniBarProgress.NONE -> R.string.player_appearance_bar_progress_none
        MiniBarProgress.FILL -> R.string.player_appearance_bar_progress_fill
        MiniBarProgress.EDGE -> R.string.player_appearance_bar_progress_edge
        MiniBarProgress.LINE -> R.string.player_appearance_bar_progress_line
        MiniBarProgress.WAVY_LINE -> R.string.player_appearance_bar_progress_wavy
    }

private const val PREVIEW_PROGRESS = 0.4f

/** A mini bar's shape at 40 % in [style], still: the line styles show their resting look. */
@Composable
private fun BarProgressPreview(style: MiniBarProgress) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(MediaMiniBarDefaults.Height)
                .clip(RoundedCornerShape(MediaMiniBarDefaults.CornerRadius))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .miniBarProgress(style, { PREVIEW_PROGRESS }, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onSurface),
    ) {
        if (style.isLine()) {
            MiniBarProgressLine(
                style = style,
                progress = { PREVIEW_PROGRESS },
                isPlaying = false,
                animationsEnabled = false,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 72.dp, end = 120.dp, top = 30.dp),
            )
        }
    }
}

/** A mini bar in [background], tinted from the app icon as the real bar is from a cover. */
@Composable
internal fun MiniBackgroundPreview(background: MiniBarBackground) {
    val context = LocalContext.current
    val artwork = remember { "android.resource://${context.packageName}/${R.drawable.ic_launcher_foreground}" }
    val appScheme = MaterialTheme.colorScheme
    val appearance = rememberMusicPlayerAppearance(artwork)
    MaterialTheme(colorScheme = appearance.playerScheme) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(MediaMiniBarDefaults.Height)
                    .clip(RoundedCornerShape(MediaMiniBarDefaults.CornerRadius))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            MiniBarSurface(background, appearance.palette.base, appearance.controlScheme, appScheme) {
                Row(
                    modifier = Modifier.fillMaxHeight().padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(MiniPreviewCover).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PreviewTextBar(MiniPreviewTitleWidth, MaterialTheme.colorScheme.onSurface)
                        PreviewTextBar(MiniPreviewArtistWidth, MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(
                        modifier = Modifier.size(MiniPreviewButton).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    Icon(Icons.Rounded.SkipNext, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PreviewTextBar(
    width: Dp,
    color: Color,
) {
    Box(
        Modifier
            .width(width)
            .height(MiniPreviewTextHeight)
            .clip(CircleShape)
            .background(color),
    )
}

private val MiniPreviewCover = 44.dp
private val MiniPreviewButton = 40.dp
private val MiniPreviewTitleWidth = 120.dp
private val MiniPreviewArtistWidth = 72.dp
private val MiniPreviewTextHeight = 8.dp
