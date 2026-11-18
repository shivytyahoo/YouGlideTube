package io.github.shivytyahoo.glidetube.ui.screens.settings.appearance.player

import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.shivytyahoo.glidetube.data.local.CoverShape
import io.github.shivytyahoo.glidetube.data.local.DEFAULT_FULLSCREEN_SEEKBAR_PADDING_DP
import io.github.shivytyahoo.glidetube.data.local.DEFAULT_PORTRAIT_SEEKBAR_PADDING_DP
import io.github.shivytyahoo.glidetube.data.local.DefaultCoverShapes
import io.github.shivytyahoo.glidetube.data.local.DefaultMiniBarButtons
import io.github.shivytyahoo.glidetube.data.local.GestureOverlayStyle
import io.github.shivytyahoo.glidetube.data.local.LYRICS_ALIGN_CENTER
import io.github.shivytyahoo.glidetube.data.local.LyricsAnimationStyle
import io.github.shivytyahoo.glidetube.data.local.LyricsBackgroundStyle
import io.github.shivytyahoo.glidetube.data.local.LyricsGlow
import io.github.shivytyahoo.glidetube.data.local.MiniBarBackground
import io.github.shivytyahoo.glidetube.data.local.MiniBarButton
import io.github.shivytyahoo.glidetube.data.local.MiniBarProgress
import io.github.shivytyahoo.glidetube.data.local.MiniBarSwipeAction
import io.github.shivytyahoo.glidetube.data.local.MiniCoverMotion
import io.github.shivytyahoo.glidetube.data.local.MiniCoverShape
import io.github.shivytyahoo.glidetube.data.local.MiniCoverTap
import io.github.shivytyahoo.glidetube.data.local.MiniLongPress
import io.github.shivytyahoo.glidetube.data.local.MusicPlainControlColors
import io.github.shivytyahoo.glidetube.data.local.MusicPlayerBackgroundStyle
import io.github.shivytyahoo.glidetube.data.local.PlayerOverlayPreferences
import io.github.shivytyahoo.glidetube.data.local.PlayerPreferences
import io.github.shivytyahoo.glidetube.data.local.QueueSwipeAction
import io.github.shivytyahoo.glidetube.data.local.ScrubPreviewStyle
import io.github.shivytyahoo.glidetube.data.local.SeekbarPaddingMode
import io.github.shivytyahoo.glidetube.data.local.ShortsPlayerUiMode
import io.github.shivytyahoo.glidetube.data.local.SliderStyle
import io.github.shivytyahoo.glidetube.ui.screens.settings.SettingsViewModel
import javax.inject.Inject

@HiltViewModel
class PlayerAppearanceViewModel
    @Inject
    constructor(
        private val preferences: PlayerPreferences,
    ) : SettingsViewModel() {
        private val overlayDefaults = PlayerOverlayPreferences()

        val sliderStyle = preferences.sliderStyle.asState(SliderStyle.COMPACT)
        val portraitSeekbarMode = preferences.portraitSeekbarPaddingMode.asState(SeekbarPaddingMode.FULL_WIDTH)
        val portraitSeekbarCustom = preferences.portraitSeekbarCustomPaddingDp.asState(DEFAULT_PORTRAIT_SEEKBAR_PADDING_DP)
        val fullscreenSeekbarMode = preferences.fullscreenSeekbarPaddingMode.asState(SeekbarPaddingMode.DEFAULT)
        val fullscreenSeekbarCustom = preferences.fullscreenSeekbarCustomPaddingDp.asState(DEFAULT_FULLSCREEN_SEEKBAR_PADDING_DP)
        val scrubPreview = preferences.scrubPreviewStyle.asState(overlayDefaults.scrubPreviewStyle)
        val frameStepButtons = preferences.frameStepButtonsEnabled.asState(overlayDefaults.frameStepButtonsEnabled)
        val musicBackground = preferences.musicPlayerBackgroundStyle.asState(MusicPlayerBackgroundStyle.BLUR_GRADIENT)
        val lyricsAnimationStyle = preferences.lyricsAnimationStyle.asState(LyricsAnimationStyle.CADENCE)
        val lyricsGlow = preferences.lyricsGlow.asState(LyricsGlow.NORMAL)
        val lyricsBlurOtherLines = preferences.lyricsBlurOtherLines.asState(true)
        val lyricsBackground = preferences.lyricsBackground.asState(LyricsBackgroundStyle.SOLID)
        val lyricsTextAlign = preferences.lyricsTextAlign.asState(LYRICS_ALIGN_CENTER)
        val lyricsOnLockScreen = preferences.lyricsOnLockScreen.asState(false)
        val hideMusicArtwork = preferences.hideMusicPlayerArtwork.asState(false)
        val followLightTheme = preferences.musicPlayerFollowLightTheme.asState(false)
        val artworkControlColors = preferences.musicArtworkControlColors.asState(true)
        val animatedArtwork = preferences.animatedArtwork.asState(false)
        val animatedArtworkWifiOnly = preferences.animatedArtworkWifiOnly.asState(true)
        val plainControlColors = preferences.musicPlainControlColors.asState(MusicPlainControlColors.MONOCHROME)
        val sleepTimerButton = preferences.musicSleepTimerButton.asState(false)
        val adaptivePlayerSize = preferences.adaptivePlayerSizeEnabled.asState(true)
        val ambientMode = preferences.videoAmbientModeEnabled.asState(false)
        val groupedQualitySelector = preferences.groupedQualitySelectorEnabled.asState(false)
        val gestureOverlay = preferences.gestureOverlayStyle.asState(GestureOverlayStyle.CIRCULAR)
        val shortsUiMode = preferences.shortsPlayerUiMode.asState(ShortsPlayerUiMode.DEFAULT)
        val miniPlayerScale = preferences.miniPlayerScale.asState(MiniPlayerSize.NORMAL.scale)
        val miniPlayerSkip = preferences.miniPlayerShowSkipControls.asState(false)
        val miniPlayerNextPrev = preferences.miniPlayerShowNextPrevControls.asState(false)
        val miniBarSwipeLeft = preferences.miniBarSwipeLeftAction.asState(MiniBarSwipeAction.CLOSE)
        val miniBarSwipeRight = preferences.miniBarSwipeRightAction.asState(MiniBarSwipeAction.CLOSE)
        val queueSwipeLeft = preferences.queueSwipeLeftAction.asState(QueueSwipeAction.PLAY_NEXT)
        val queueSwipeRight = preferences.queueSwipeRightAction.asState(QueueSwipeAction.REMOVE)
        val miniCoverShape = preferences.miniCoverShape.asState(MiniCoverShape.CIRCLE)
        val miniCoverMotion = preferences.miniCoverMotion.asState(MiniCoverMotion.ON_PLAY)
        val miniCoverShapes = preferences.miniCoverShapes.asState(DefaultCoverShapes)
        val miniCoverPausedShape = preferences.miniCoverPausedShape.asState(CoverShape.CIRCLE)
        val miniBarProgress = preferences.miniBarProgress.asState(MiniBarProgress.NONE)
        val miniCoverRing = preferences.miniCoverRing.asState(true)
        val miniBarButtons = preferences.miniBarButtons.asState(DefaultMiniBarButtons)
        val miniCoverTap = preferences.miniCoverTap.asState(MiniCoverTap.OPEN)
        val miniLongPress = preferences.miniLongPress.asState(MiniLongPress.NOTHING)
        val miniBarBackground = preferences.miniBarBackground.asState(MiniBarBackground.TINTED)

        fun setSliderStyle(value: SliderStyle) = write { preferences.setSliderStyle(value) }

        fun setPortraitSeekbarMode(value: SeekbarPaddingMode) = write { preferences.setPortraitSeekbarPaddingMode(value) }

        fun setPortraitSeekbarCustom(value: Int) = write { preferences.setPortraitSeekbarCustomPaddingDp(value) }

        fun setFullscreenSeekbarMode(value: SeekbarPaddingMode) = write { preferences.setFullscreenSeekbarPaddingMode(value) }

        fun setFullscreenSeekbarCustom(value: Int) = write { preferences.setFullscreenSeekbarCustomPaddingDp(value) }

        fun setScrubPreview(value: ScrubPreviewStyle) = write { preferences.setScrubPreviewStyle(value) }

        fun setFrameStepButtons(value: Boolean) = write { preferences.setFrameStepButtonsEnabled(value) }

        fun setMusicBackground(value: MusicPlayerBackgroundStyle) = write { preferences.setMusicPlayerBackgroundStyle(value) }

        fun setLyricsAnimationStyle(value: LyricsAnimationStyle) = write { preferences.setLyricsAnimationStyle(value) }

        fun setLyricsGlow(value: LyricsGlow) = write { preferences.setLyricsGlow(value) }

        fun setLyricsBlurOtherLines(value: Boolean) = write { preferences.setLyricsBlurOtherLines(value) }

        fun setLyricsBackground(value: LyricsBackgroundStyle) = write { preferences.setLyricsBackground(value) }

        fun setLyricsTextAlign(value: String) = write { preferences.setLyricsTextAlign(value) }

        fun setLyricsOnLockScreen(value: Boolean) = write { preferences.setLyricsOnLockScreen(value) }

        fun setHideMusicArtwork(value: Boolean) = write { preferences.setHideMusicPlayerArtwork(value) }

        fun setFollowLightTheme(value: Boolean) = write { preferences.setMusicPlayerFollowLightTheme(value) }

        fun setArtworkControlColors(value: Boolean) = write { preferences.setMusicArtworkControlColors(value) }

        fun setAnimatedArtwork(value: Boolean) = write { preferences.setAnimatedArtwork(value) }

        fun setAnimatedArtworkWifiOnly(value: Boolean) = write { preferences.setAnimatedArtworkWifiOnly(value) }

        fun setPlainControlColors(value: MusicPlainControlColors) = write { preferences.setMusicPlainControlColors(value) }

        fun setSleepTimerButton(value: Boolean) = write { preferences.setMusicSleepTimerButton(value) }

        fun setAdaptivePlayerSize(value: Boolean) = write { preferences.setAdaptivePlayerSizeEnabled(value) }

        fun setAmbientMode(value: Boolean) = write { preferences.setVideoAmbientModeEnabled(value) }

        fun setGroupedQualitySelector(value: Boolean) = write { preferences.setGroupedQualitySelectorEnabled(value) }

        fun setGestureOverlay(value: GestureOverlayStyle) = write { preferences.setGestureOverlayStyle(value) }

        fun setShortsUiMode(value: ShortsPlayerUiMode) = write { preferences.setShortsPlayerUiMode(value) }

        fun setMiniPlayerSize(value: MiniPlayerSize) = write { preferences.setMiniPlayerScale(value.scale) }

        fun setMiniPlayerSkip(value: Boolean) = write { preferences.setMiniPlayerShowSkipControls(value) }

        fun setMiniPlayerNextPrev(value: Boolean) = write { preferences.setMiniPlayerShowNextPrevControls(value) }

        fun setMiniBarSwipeLeft(value: MiniBarSwipeAction) = write { preferences.setMiniBarSwipeLeftAction(value) }

        fun setMiniBarSwipeRight(value: MiniBarSwipeAction) = write { preferences.setMiniBarSwipeRightAction(value) }

        fun setQueueSwipeLeft(value: QueueSwipeAction) = write { preferences.setQueueSwipeLeftAction(value) }

        fun setQueueSwipeRight(value: QueueSwipeAction) = write { preferences.setQueueSwipeRightAction(value) }

        fun setMiniCoverShape(value: MiniCoverShape) = write { preferences.setMiniCoverShape(value) }

        fun setMiniCoverMotion(value: MiniCoverMotion) = write { preferences.setMiniCoverMotion(value) }

        /** Adds or removes [shape]; the last one left can't be removed. */
        fun toggleMiniCoverShape(shape: CoverShape) =
            write {
                val current = miniCoverShapes.value
                val next = if (shape in current) current - shape else current + shape
                if (next.isNotEmpty()) preferences.setMiniCoverShapes(next)
            }

        fun setMiniCoverPausedShape(shape: CoverShape?) = write { preferences.setMiniCoverPausedShape(shape) }

        fun setMiniBarProgress(value: MiniBarProgress) = write { preferences.setMiniBarProgress(value) }

        fun setMiniCoverRing(value: Boolean) = write { preferences.setMiniCoverRing(value) }

        fun setMiniBarButtons(value: List<MiniBarButton>) = write { preferences.setMiniBarButtons(value) }

        fun setMiniCoverTap(value: MiniCoverTap) = write { preferences.setMiniCoverTap(value) }

        fun setMiniLongPress(value: MiniLongPress) = write { preferences.setMiniLongPress(value) }

        fun setMiniBarBackground(value: MiniBarBackground) = write { preferences.setMiniBarBackground(value) }
    }

/** The three mini player sizes the setting offers, stored as the player's scale factor. */
enum class MiniPlayerSize(
    val scale: Float,
) {
    SMALL(0.35f),
    NORMAL(0.45f),
    LARGE(0.55f),
    ;

    companion object {
        fun nearest(scale: Float): MiniPlayerSize = entries.minBy { kotlin.math.abs(it.scale - scale) }
    }
}
