package io.github.aedev.flow.ui.tv.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.account.AccountPlaylistIds
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.DownloadedTrack
import io.github.aedev.flow.data.video.DownloadedVideo
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.components.layout.flowBottomContentPadding
import io.github.aedev.flow.ui.components.library.LibrarySection
import io.github.aedev.flow.ui.components.shared.FlowEmptyState
import io.github.aedev.flow.ui.components.shared.MediaKind
import io.github.aedev.flow.ui.screens.library.LibraryViewModel
import io.github.aedev.flow.ui.screens.library.libraryShelves
import io.github.aedev.flow.ui.screens.library.visibleContentSections
import io.github.aedev.flow.ui.tv.components.TvScreenFrame

private val ShelfSpacing = 24.dp

/**
 * TV Library: the phone's shelves for every section the user shows (#1336), each title opening the
 * section's own page, from the same LibraryViewModel.
 */
@Composable
fun TvLibraryScreen(
    onVideoClick: (Video) -> Unit,
    onMusicClick: (MusicTrack, List<MusicTrack>, String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenPlaylists: (MediaKind?) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onOpenMusicPlaylist: (String) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenLocalMedia: () -> Unit,
    onDownloadedVideoClick: (List<DownloadedVideo>, Int) -> Unit,
    onDownloadedMusicClick: (List<DownloadedTrack>, Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val separatePlaylistKinds by viewModel.separatePlaylistKinds.collectAsStateWithLifecycle()
    val isLibraryEmpty by viewModel.isLibraryEmpty.collectAsStateWithLifecycle()
    val hiddenSections by viewModel.hiddenSections.collectAsStateWithLifecycle()

    TvScreenFrame(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = flowBottomContentPadding(extra = 24.dp)),
            verticalArrangement = Arrangement.spacedBy(ShelfSpacing),
        ) {
            val hidden = hiddenSections ?: return@LazyColumn
            if (isLibraryEmpty) {
                item(key = "library-empty", contentType = "empty") {
                    FlowEmptyState(
                        title = stringResource(R.string.library_empty_title),
                        subtitle = stringResource(R.string.library_empty_body),
                        icon = Icons.Outlined.VideoLibrary,
                    )
                }
                return@LazyColumn
            }
            libraryShelves(
                viewModel = viewModel,
                sections = visibleContentSections(hidden, separatePlaylistKinds, shortsEnabled = false),
                onOpenSection = { section ->
                    when (section) {
                        LibrarySection.HISTORY -> onOpenHistory()
                        LibrarySection.PLAYLISTS -> onOpenPlaylists(null)
                        LibrarySection.VIDEO_PLAYLISTS -> onOpenPlaylists(MediaKind.Videos)
                        LibrarySection.MUSIC_PLAYLISTS -> onOpenPlaylists(MediaKind.Music)
                        LibrarySection.WATCH_LATER -> onOpenPlaylist(AccountPlaylistIds.WATCH_LATER)
                        LibrarySection.LIKED_VIDEOS -> onOpenPlaylist(AccountPlaylistIds.LIKED_VIDEOS)
                        LibrarySection.LIKED_MUSIC -> onOpenMusicPlaylist(AccountPlaylistIds.LIKED_MUSIC)
                        LibrarySection.DOWNLOADS -> onOpenDownloads()
                        LibrarySection.LOCAL_MEDIA -> onOpenLocalMedia()
                        else -> Unit
                    }
                },
                onVideoClick = onVideoClick,
                onMusicClick = onMusicClick,
                onPlaylistClick = onOpenPlaylist,
                onMusicPlaylistClick = onOpenMusicPlaylist,
                onDownloadedVideoClick = onDownloadedVideoClick,
                onDownloadedMusicClick = onDownloadedMusicClick,
            )
        }
    }
}
