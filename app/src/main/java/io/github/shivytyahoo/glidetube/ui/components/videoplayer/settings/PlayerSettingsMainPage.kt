package io.github.shivytyahoo.glidetube.ui.components.videoplayer.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.MoreTime
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.player.EnhancedPlayerState
import io.github.shivytyahoo.glidetube.player.stream.PlaybackMethod
import io.github.shivytyahoo.glidetube.ui.components.equalizer.equalizerSummary
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideNavRow
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideRowGroup
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideSectionHeader
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideSwitchRow
import io.github.shivytyahoo.glidetube.ui.components.shared.flowRowGroupShape
import io.github.shivytyahoo.glidetube.ui.components.shared.playbackSpeedLabel
import io.github.shivytyahoo.glidetube.utils.formatDurationMillis

private const val VIDEO_ROWS = 2
private const val PLAYBACK_ROWS = 3
private const val AUDIO_ROWS = 1
private const val CAPTION_ROWS = 2
private const val OVERLAY_ROWS = 3
private const val EFFECT_ROWS = 3
private const val DISPLAY_ROWS = 1

@Composable
internal fun PlayerSettingsMainPage(
    playerState: EnhancedPlayerState,
    autoplayEnabled: Boolean,
    subtitlesEnabled: Boolean,
    ambientModeEnabled: Boolean,
    playbackMethod: PlaybackMethod = PlaybackMethod.AUTO,
    extractionClientName: String? = null,
    onNavigateToPage: (PlayerSettingsPage) -> Unit,
    onShowSubtitleStyle: () -> Unit,
    onCastClick: () -> Unit,
    onPipClick: () -> Unit,
    onSleepTimerClick: () -> Unit,
    onLoopToggle: (Boolean) -> Unit,
    onAutoplayToggle: (Boolean) -> Unit,
    onSkipSilenceToggle: (Boolean) -> Unit,
    onStableVolumeToggle: (Boolean) -> Unit,
    onAmbientModeToggle: (Boolean) -> Unit,
    sponsorBlockSegmentCount: Int = 0,
    sponsorBlockOffForVideo: Boolean = false,
    onSponsorBlockToggle: (off: Boolean) -> Unit = {},
) {
    val playbackRows = if (sponsorBlockSegmentCount > 0) PLAYBACK_ROWS + 1 else PLAYBACK_ROWS
    GlideSectionHeader(stringResource(R.string.video))
    GlideRowGroup {
        GlideNavRow(
            leadingIcon = Icons.Filled.HighQuality,
            title = stringResource(R.string.quality),
            trailingText =
                if (playerState.currentQuality == 0) {
                    stringResource(R.string.quality_auto)
                } else {
                    "${playerState.currentQuality}p"
                },
            shape = flowRowGroupShape(0, VIDEO_ROWS),
            onClick = { onNavigateToPage(PlayerSettingsPage.Quality) },
        )
        GlideNavRow(
            leadingIcon = Icons.Filled.Tune,
            title = stringResource(R.string.player_settings_playback_method_title),
            trailingText = playbackMethodTrailingText(playbackMethod, extractionClientName),
            shape = flowRowGroupShape(1, VIDEO_ROWS),
            onClick = { onNavigateToPage(PlayerSettingsPage.Method) },
        )
    }

    GlideSectionHeader(stringResource(R.string.playback_header))
    GlideRowGroup {
        GlideNavRow(
            leadingIcon = Icons.Filled.Speed,
            title = stringResource(R.string.playback_speed),
            trailingText = playbackSpeedLabel(playerState.playbackSpeed),
            shape = flowRowGroupShape(0, playbackRows),
            onClick = { onNavigateToPage(PlayerSettingsPage.Speed) },
        )
        GlideSwitchRow(
            leadingIcon = Icons.Rounded.Repeat,
            title = stringResource(R.string.loop_video),
            checked = playerState.isLooping,
            shape = flowRowGroupShape(1, playbackRows),
            onCheckedChange = onLoopToggle,
        )
        GlideSwitchRow(
            leadingIcon = Icons.Filled.SkipNext,
            title = stringResource(R.string.autoplay_next),
            checked = autoplayEnabled,
            enabled = !playerState.isLooping,
            shape = flowRowGroupShape(2, playbackRows),
            onCheckedChange = onAutoplayToggle,
        )
        if (sponsorBlockSegmentCount > 0) {
            GlideSwitchRow(
                leadingIcon = ImageVector.vectorResource(R.drawable.ic_block),
                title = stringResource(R.string.player_settings_sponsorblock_video),
                supportingText =
                    pluralStringResource(R.plurals.sb_video_segments, sponsorBlockSegmentCount, sponsorBlockSegmentCount),
                checked = !sponsorBlockOffForVideo,
                shape = flowRowGroupShape(3, playbackRows),
                onCheckedChange = { on -> onSponsorBlockToggle(!on) },
            )
        }
    }

    GlideSectionHeader(stringResource(R.string.audio_settings_title))
    GlideRowGroup {
        GlideNavRow(
            leadingIcon = Icons.Filled.AudioFile,
            title = stringResource(R.string.audio_track),
            trailingText =
                audioTrackDisplayLabel(
                    playerState.availableAudioTracks.getOrNull(playerState.currentAudioTrack),
                    playerState.currentAudioTrack,
                ),
            shape = flowRowGroupShape(0, AUDIO_ROWS),
            onClick = { onNavigateToPage(PlayerSettingsPage.Audio) },
        )
    }

    GlideSectionHeader(stringResource(R.string.captions))
    GlideRowGroup {
        GlideNavRow(
            leadingIcon = Icons.Filled.Subtitles,
            title = stringResource(R.string.filter_subtitles),
            trailingText =
                if (subtitlesEnabled) stringResource(R.string.on) else stringResource(R.string.off),
            shape = flowRowGroupShape(0, CAPTION_ROWS),
            onClick = { onNavigateToPage(PlayerSettingsPage.Subtitles) },
        )
        GlideNavRow(
            leadingIcon = Icons.Filled.Tune,
            title = stringResource(R.string.subtitle_style),
            shape = flowRowGroupShape(1, CAPTION_ROWS),
            onClick = onShowSubtitleStyle,
        )
    }

    GlideSectionHeader(stringResource(R.string.player_settings_overlay_controls))
    GlideRowGroup {
        GlideNavRow(
            leadingIcon = Icons.Filled.Cast,
            title = stringResource(R.string.cast_to_tv),
            shape = flowRowGroupShape(0, OVERLAY_ROWS),
            onClick = onCastClick,
        )
        GlideNavRow(
            leadingIcon = Icons.Filled.PictureInPicture,
            title = stringResource(R.string.pip_mode),
            shape = flowRowGroupShape(1, OVERLAY_ROWS),
            onClick = onPipClick,
        )
        GlideNavRow(
            leadingIcon = Icons.Filled.Bedtime,
            title = stringResource(R.string.sleep_timer),
            shape = flowRowGroupShape(2, OVERLAY_ROWS),
            onClick = onSleepTimerClick,
        )
    }

    GlideSectionHeader(stringResource(R.string.audio_effects))
    GlideRowGroup {
        GlideNavRow(
            leadingIcon = Icons.Filled.Equalizer,
            title = stringResource(R.string.equalizer),
            trailingText = equalizerSummary(),
            shape = flowRowGroupShape(0, EFFECT_ROWS),
            onClick = { onNavigateToPage(PlayerSettingsPage.Equalizer) },
        )
        GlideSwitchRow(
            leadingIcon = Icons.Rounded.GraphicEq,
            title = stringResource(R.string.player_settings_skip_silence),
            checked = playerState.isSkipSilenceEnabled,
            shape = flowRowGroupShape(1, EFFECT_ROWS),
            onCheckedChange = onSkipSilenceToggle,
        )
        GlideSwitchRow(
            leadingIcon = Icons.AutoMirrored.Rounded.VolumeUp,
            title = stringResource(R.string.player_settings_stable_voice),
            checked = playerState.isStableVolumeEnabled,
            shape = flowRowGroupShape(2, EFFECT_ROWS),
            onCheckedChange = onStableVolumeToggle,
        )
    }

    GlideSectionHeader(stringResource(R.string.player_settings_display))
    GlideRowGroup {
        GlideSwitchRow(
            leadingIcon = ImageVector.vectorResource(R.drawable.ic_ambient_mode),
            title = stringResource(R.string.player_settings_ambient_mode),
            checked = ambientModeEnabled,
            shape = flowRowGroupShape(0, DISPLAY_ROWS),
            onCheckedChange = onAmbientModeToggle,
        )
    }
}
