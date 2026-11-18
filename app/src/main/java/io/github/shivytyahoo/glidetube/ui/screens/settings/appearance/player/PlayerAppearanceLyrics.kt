package io.github.shivytyahoo.glidetube.ui.screens.settings.appearance.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.data.local.LyricsAnimationStyle
import io.github.shivytyahoo.glidetube.data.local.LyricsBackgroundStyle
import io.github.shivytyahoo.glidetube.data.local.LyricsGlow
import io.github.shivytyahoo.glidetube.ui.components.musicplayer.lyrics.LyricsAlignments
import io.github.shivytyahoo.glidetube.ui.components.musicplayer.lyrics.lyricsAlignmentLabel
import io.github.shivytyahoo.glidetube.ui.components.musicplayer.lyrics.lyricsAnimationLabel
import io.github.shivytyahoo.glidetube.ui.components.musicplayer.lyrics.lyricsBackgroundLabel
import io.github.shivytyahoo.glidetube.ui.components.musicplayer.lyrics.lyricsGlowLabel
import io.github.shivytyahoo.glidetube.ui.components.settings.SettingsListScope
import io.github.shivytyahoo.glidetube.ui.components.settings.switch
import io.github.shivytyahoo.glidetube.ui.components.settings.toggleGroup
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideToggleOption
import io.github.shivytyahoo.glidetube.ui.screens.settings.index.PlayerAppearanceIndex

/** The choices the lyrics group offers, resolved once per screen. */
@Immutable
internal class LyricsLookOptions(
    val animation: List<GlideToggleOption<LyricsAnimationStyle>>,
    val glow: List<GlideToggleOption<LyricsGlow>>,
    val background: List<GlideToggleOption<LyricsBackgroundStyle>>,
    val alignment: List<GlideToggleOption<String>>,
)

@Composable
internal fun rememberLyricsLookOptions(): LyricsLookOptions =
    LyricsLookOptions(
        animation = LyricsAnimationStyle.entries.map { GlideToggleOption(it, stringResource(lyricsAnimationLabel(it))) },
        glow = LyricsGlow.entries.map { GlideToggleOption(it, stringResource(lyricsGlowLabel(it))) },
        background = LyricsBackgroundStyle.entries.map { GlideToggleOption(it, stringResource(lyricsBackgroundLabel(it))) },
        alignment = LyricsAlignments.map { GlideToggleOption(it, stringResource(lyricsAlignmentLabel(it))) },
    )

/** How synced lyrics look and move, beside the music player's other looks. */
internal fun SettingsListScope.lyricsLookGroup(
    viewModel: PlayerAppearanceViewModel,
    options: LyricsLookOptions,
) = group(key = "player_appearance.lyrics", header = R.string.settings_section_lyrics) {
    toggleGroup(
        PlayerAppearanceIndex.lyricsAnimation,
        options.animation,
        viewModel.lyricsAnimationStyle,
        viewModel::setLyricsAnimationStyle,
    )
    toggleGroup(PlayerAppearanceIndex.lyricsGlow, options.glow, viewModel.lyricsGlow, viewModel::setLyricsGlow)
    switch(PlayerAppearanceIndex.lyricsBlurOtherLines, viewModel.lyricsBlurOtherLines, viewModel::setLyricsBlurOtherLines)
    toggleGroup(PlayerAppearanceIndex.lyricsBackground, options.background, viewModel.lyricsBackground, viewModel::setLyricsBackground)
    toggleGroup(PlayerAppearanceIndex.lyricsAlignment, options.alignment, viewModel.lyricsTextAlign, viewModel::setLyricsTextAlign)
    switch(PlayerAppearanceIndex.lyricsOnLockScreen, viewModel.lyricsOnLockScreen, viewModel::setLyricsOnLockScreen)
}
