package io.github.shivytyahoo.glidetube.ui.screens.settings.quality

import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.shivytyahoo.glidetube.data.local.MusicAudioQuality
import io.github.shivytyahoo.glidetube.data.local.PlayerPreferences
import io.github.shivytyahoo.glidetube.data.local.ThumbnailQuality
import io.github.shivytyahoo.glidetube.data.local.VideoCodec
import io.github.shivytyahoo.glidetube.data.local.VideoQuality
import io.github.shivytyahoo.glidetube.ui.screens.settings.SettingsViewModel
import javax.inject.Inject

@HiltViewModel
class QualitySettingsViewModel
    @Inject
    constructor(
        private val preferences: PlayerPreferences,
    ) : SettingsViewModel() {
        val videoWifi = preferences.defaultQualityWifi.asState(VideoQuality.Q_720P)
        val videoMobile = preferences.defaultQualityCellular.asState(VideoQuality.Q_480P)
        val previewWifi = preferences.previewQualityWifi.asState(VideoQuality.Q_360P)
        val previewMobile = preferences.previewQualityCellular.asState(VideoQuality.Q_360P)
        val shortsPreviewWifi = preferences.shortsPreviewQualityWifi.asState(VideoQuality.Q_360P)
        val shortsPreviewMobile = preferences.shortsPreviewQualityCellular.asState(VideoQuality.Q_360P)
        val shortsWifi = preferences.shortsQualityWifi.asState(VideoQuality.Q_720P)
        val shortsMobile = preferences.shortsQualityCellular.asState(VideoQuality.Q_480P)
        val music = preferences.musicAudioQuality.asState(MusicAudioQuality.AUTO)
        val thumbnailsWifi = preferences.thumbnailQualityWifi.asState(ThumbnailQuality.HIGH)
        val thumbnailsMobile = preferences.thumbnailQualityCellular.asState(ThumbnailQuality.HIGH)
        val codec = preferences.defaultVideoCodec.asState(VideoCodec.H264)
        val fallbackCodec = preferences.fallbackVideoCodec.asState(VideoCodec.AUTO)

        fun setVideoWifi(value: VideoQuality) = write { preferences.setDefaultQualityWifi(value) }

        fun setVideoMobile(value: VideoQuality) = write { preferences.setDefaultQualityCellular(value) }

        fun setPreviewWifi(value: VideoQuality) = write { preferences.setPreviewQualityWifi(value) }

        fun setPreviewMobile(value: VideoQuality) = write { preferences.setPreviewQualityCellular(value) }

        fun setShortsPreviewWifi(value: VideoQuality) = write { preferences.setShortsPreviewQualityWifi(value) }

        fun setShortsPreviewMobile(value: VideoQuality) = write { preferences.setShortsPreviewQualityCellular(value) }

        fun setShortsWifi(value: VideoQuality) = write { preferences.setShortsQualityWifi(value) }

        fun setShortsMobile(value: VideoQuality) = write { preferences.setShortsQualityCellular(value) }

        fun setMusic(value: MusicAudioQuality) = write { preferences.setMusicAudioQuality(value) }

        fun setThumbnailsWifi(value: ThumbnailQuality) = write { preferences.setThumbnailQualityWifi(value) }

        fun setThumbnailsMobile(value: ThumbnailQuality) = write { preferences.setThumbnailQualityCellular(value) }

        fun setCodec(value: VideoCodec) =
            write {
                preferences.setDefaultVideoCodec(value)
                val fallback = fallbackAfterPreferredChange(value, fallbackCodec.value)
                if (fallback != fallbackCodec.value) preferences.setFallbackVideoCodec(fallback)
            }

        fun setFallbackCodec(value: VideoCodec) = write { preferences.setFallbackVideoCodec(value) }
    }
