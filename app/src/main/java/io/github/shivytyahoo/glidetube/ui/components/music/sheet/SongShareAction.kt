package io.github.shivytyahoo.glidetube.ui.components.music.sheet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.shivytyahoo.glidetube.data.music.model.MusicTrack
import io.github.shivytyahoo.glidetube.ui.components.shared.rememberShareLinksWithoutText
import io.github.shivytyahoo.glidetube.utils.shareSongIntent

/** Raises the system share sheet for a song, with the same message wherever it is shared from. */
@Composable
fun rememberSongShareAction(): (MusicTrack) -> Unit {
    val context = LocalContext.current
    val linkOnly = rememberShareLinksWithoutText()
    return remember(context) {
        { track: MusicTrack ->
            context.startActivity(shareSongIntent(context, track.videoId, track.title, track.artist, linkOnly.value))
        }
    }
}
