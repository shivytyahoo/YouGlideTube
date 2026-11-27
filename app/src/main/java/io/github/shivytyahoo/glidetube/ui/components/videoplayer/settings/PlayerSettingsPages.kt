package io.github.shivytyahoo.glidetube.ui.components.videoplayer.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.player.AudioTrackOption
import io.github.shivytyahoo.glidetube.player.QualityOption
import io.github.shivytyahoo.glidetube.player.SubtitleOption
import io.github.shivytyahoo.glidetube.player.state.SubtitleOrigin
import io.github.shivytyahoo.glidetube.player.stream.PlaybackMethod
import io.github.shivytyahoo.glidetube.player.stream.VideoCodecUtils
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideNavRow
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideRowGroup
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideSelectionRow
import io.github.shivytyahoo.glidetube.ui.components.shared.MediaAudioTrackRow
import io.github.shivytyahoo.glidetube.ui.components.shared.MediaPlaybackSpeedPicker
import io.github.shivytyahoo.glidetube.ui.components.shared.MediaQualitySelectorContent
import io.github.shivytyahoo.glidetube.ui.components.shared.MediaQualitySelectorOption
import io.github.shivytyahoo.glidetube.ui.components.shared.audioTrackFallbackLabel
import io.github.shivytyahoo.glidetube.ui.components.shared.flowRowGroupShape

@Composable
internal fun PlayerSettingsQualityPage(
    availableQualities: List<QualityOption>,
    currentQuality: Int,
    currentQualityKey: String?,
    useGroupedQualitySelector: Boolean,
    onQualitySelected: (QualityOption) -> Unit,
) {
    val autoLabel = stringResource(R.string.quality_auto)
    val selectorOptions =
        availableQualities.map { quality ->
            MediaQualitySelectorOption(
                item = quality,
                height = quality.height,
                label = if (quality.height == 0) autoLabel else quality.displayLabel(),
                codecKey = quality.codecKey,
                codecLabel =
                    quality.codecKey
                        .takeIf { it.isNotBlank() }
                        ?.let(VideoCodecUtils::codecLabelFromKey)
                        .orEmpty(),
                selected = quality.isSelected(currentQuality, currentQualityKey),
            )
        }

    MediaQualitySelectorContent(
        options = selectorOptions,
        groupedByResolution = useGroupedQualitySelector,
        onOptionSelected = onQualitySelected,
    )
}

private fun QualityOption.displayLabel(): String = label.takeIf { it.isNotBlank() } ?: "${height}p"

private fun QualityOption.isSelected(
    currentQuality: Int,
    currentQualityKey: String?,
): Boolean =
    if (height == 0) {
        currentQuality == 0
    } else {
        streamKey != null && streamKey == currentQualityKey
    }

/**
 * Which client supplies this video's streams. Picking one saves the preference, clears the
 * video's cached streams and reloads it at its position — a method change is a re-extraction,
 * not a track switch. Under Auto the row shows the client that actually served this video.
 */
@Composable
internal fun PlayerSettingsMethodPage(
    currentMethod: PlaybackMethod,
    extractionClientName: String?,
    onMethodSelected: (PlaybackMethod) -> Unit,
) {
    GlideRowGroup {
        PlaybackMethod.menuOrder.forEachIndexed { index, method ->
            GlideSelectionRow(
                title = stringResource(method.labelRes),
                supportingText =
                    if (method == PlaybackMethod.AUTO) {
                        extractionClientName?.let { usedClientLabel(it) }
                    } else {
                        null
                    },
                selected = method == currentMethod,
                shape = flowRowGroupShape(index, PlaybackMethod.menuOrder.size),
                onClick = { onMethodSelected(method) },
            )
        }
    }
}

/** The method row's trailing text: the method, and under Auto the client that served this video. */
@Composable
internal fun playbackMethodTrailingText(
    method: PlaybackMethod,
    extractionClientName: String?,
): String {
    val label = stringResource(method.labelRes)
    if (method != PlaybackMethod.AUTO) return label
    val used = extractionClientName?.let { usedClientLabel(it) } ?: return label
    return stringResource(R.string.settings_value_pair, label, used)
}

/**
 * The client that served a video, in the method picker's words. The extractor reports raw
 * client names; the SABR clients read as the SABR method, and anything unrecognised is shown
 * as-is rather than hidden.
 */
@Composable
internal fun usedClientLabel(clientName: String): String {
    val labelRes =
        when (clientName) {
            "VISIONOS" -> R.string.player_settings_playback_method_visionos
            "IOS_PRODUCER" -> R.string.player_settings_playback_method_ios_studio
            "ANDROID_VR" -> R.string.player_settings_playback_method_android_vr
            "ANDROID_KIDS" -> R.string.player_settings_playback_method_kids
            "MWEB", "WEB" -> R.string.player_settings_playback_method_sabr
            "TVHTML5" -> R.string.player_settings_playback_method_tv
            else -> null
        }
    return labelRes?.let { stringResource(it) } ?: clientName
}

@Composable
internal fun PlayerSettingsSpeedPage(
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    onSpeedSelectionFinished: () -> Unit,
) {
    val context = LocalContext.current
    val playerPrefs =
        remember {
            io.github.shivytyahoo.glidetube.data.local
                .PlayerPreferences(context)
        }
    val customSpeedsEnabled by playerPrefs.customSpeedsEnabled.collectAsState(initial = false)
    val customSpeedPresetsRaw by playerPrefs.customSpeedPresets.collectAsState(initial = "")
    val speedSliderEnabled by playerPrefs.speedSliderEnabled.collectAsState(initial = false)

    MediaPlaybackSpeedPicker(
        currentSpeed = currentSpeed,
        sliderEnabled = speedSliderEnabled,
        customSpeedsEnabled = customSpeedsEnabled,
        customSpeedPresetsRaw = customSpeedPresetsRaw,
        onSpeedSelected = onSpeedSelected,
        onSpeedRowSelected = { onSpeedSelectionFinished() },
    )
}

@Composable
internal fun PlayerSettingsAudioPage(
    availableAudioTracks: List<AudioTrackOption>,
    currentAudioTrack: Int,
    onTrackSelected: (Int) -> Unit,
) {
    GlideRowGroup {
        availableAudioTracks.forEachIndexed { index, track ->
            MediaAudioTrackRow(
                label = audioTrackDisplayLabel(track, index),
                supportingText = track.language.takeIf { it.isNotBlank() },
                selected = index == currentAudioTrack,
                shape = flowRowGroupShape(index, availableAudioTracks.size),
                onClick = { onTrackSelected(index) },
            )
        }
    }
}

@Composable
internal fun PlayerSettingsSubtitlesPage(
    availableSubtitles: List<SubtitleOption>,
    selectedSubtitleUrl: String?,
    subtitlesEnabled: Boolean,
    onSubtitleSelected: (Int) -> Unit,
    onDisableSubtitles: () -> Unit,
    onShowStyleCustomizer: () -> Unit,
    onAddSubtitleFile: (() -> Unit)? = null,
    subtitleOffsetMs: Long = 0L,
    onSubtitleOffsetChange: ((Long) -> Unit)? = null,
) {
    val rowCount = availableSubtitles.size + 1
    GlideRowGroup {
        GlideSelectionRow(
            title = stringResource(R.string.off),
            selected = !subtitlesEnabled,
            shape = flowRowGroupShape(0, rowCount),
            onClick = onDisableSubtitles,
        )
        availableSubtitles.forEachIndexed { index, subtitle ->
            GlideSelectionRow(
                title = subtitleRowTitle(subtitle, index),
                supportingText =
                    when (subtitle.origin) {
                        SubtitleOrigin.FILE -> {
                            subtitle.detail?.takeIf { it != subtitle.label }
                                ?: stringResource(R.string.subtitle_origin_file)
                        }

                        SubtitleOrigin.EMBEDDED -> {
                            stringResource(R.string.subtitle_origin_embedded)
                        }

                        SubtitleOrigin.ONLINE -> {
                            subtitle.language.takeIf { it.isNotBlank() }
                        }
                    },
                selected = subtitle.url == selectedSubtitleUrl && subtitlesEnabled,
                shape = flowRowGroupShape(index + 1, rowCount),
                onClick = { onSubtitleSelected(index) },
            )
        }
    }
    if (onSubtitleOffsetChange != null && subtitlesEnabled) {
        Spacer(modifier = Modifier.height(SubtitleStyleRowSpacing))
        SubtitleTimingCard(offsetMs = subtitleOffsetMs, onOffsetChange = onSubtitleOffsetChange)
    }
    Spacer(modifier = Modifier.height(SubtitleStyleRowSpacing))
    val navRows = if (onAddSubtitleFile != null) 2 else 1
    GlideRowGroup {
        if (onAddSubtitleFile != null) {
            GlideNavRow(
                title = stringResource(R.string.subtitle_add_file),
                leadingIcon = Icons.Filled.FileOpen,
                shape = flowRowGroupShape(0, navRows),
                onClick = onAddSubtitleFile,
            )
        }
        GlideNavRow(
            title = stringResource(R.string.subtitle_style),
            leadingIcon = Icons.Filled.Tune,
            shape = flowRowGroupShape(navRows - 1, navRows),
            onClick = onShowStyleCustomizer,
        )
    }
}

/** The track's name, with what kind of track it is when that is not a plain authored one. */
@Composable
private fun subtitleRowTitle(
    subtitle: SubtitleOption,
    index: Int,
): String {
    val name = subtitle.label.ifBlank { stringResource(R.string.subtitle_track_number, index + 1) }
    val kind =
        when {
            subtitle.isTranslated -> stringResource(R.string.subtitle_translated)
            subtitle.isAutoGenerated -> stringResource(R.string.quality_auto)
            subtitle.isForced -> stringResource(R.string.subtitle_forced)
            else -> return name
        }
    return stringResource(R.string.subtitle_auto_generated_template, name, kind)
}

private val SubtitleStyleRowSpacing = 12.dp

@Composable
internal fun audioTrackDisplayLabel(
    track: AudioTrackOption?,
    fallbackIndex: Int,
): String = track?.label?.takeIf { it.isNotBlank() } ?: audioTrackFallbackLabel(fallbackIndex)
