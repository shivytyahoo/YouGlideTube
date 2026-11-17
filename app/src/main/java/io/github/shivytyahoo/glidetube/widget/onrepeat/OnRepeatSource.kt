package io.github.shivytyahoo.glidetube.widget.onrepeat

import androidx.glance.appwidget.GlanceAppWidget
import io.github.shivytyahoo.glidetube.data.music.model.MusicTrack
import io.github.shivytyahoo.glidetube.widget.core.refresh.WidgetContentKey
import io.github.shivytyahoo.glidetube.widget.core.refresh.WidgetContentSource
import javax.inject.Inject
import javax.inject.Singleton

/** The Music page's On Repeat shelf. The brain has no change stream, so a finished listen requests a refresh. */
@Singleton
class OnRepeatSource
    @Inject
    constructor(
        private val musicSessionLibrary: io.github.shivytyahoo.glidetube.data.music.MusicSessionLibrary,
    ) : WidgetContentSource {
        override val key = WidgetContentKey.ON_REPEAT
        override val widget: GlanceAppWidget get() = OnRepeatWidget()

        suspend fun tracks(): List<MusicTrack> = musicSessionLibrary.onRepeatTracks(MAX_ITEMS)

        override suspend fun signature(): String = tracks().joinToString(",") { it.videoId }

        companion object {
            const val MAX_ITEMS = 8
        }
    }
