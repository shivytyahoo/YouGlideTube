package io.github.shivytyahoo.glidetube.ui.tv.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.data.account.AccountPlaylistIds
import io.github.shivytyahoo.glidetube.data.model.Video
import io.github.shivytyahoo.glidetube.data.music.DownloadedTrack
import io.github.shivytyahoo.glidetube.data.video.DownloadedVideo
import io.github.shivytyahoo.glidetube.data.music.model.MusicTrack
import io.github.shivytyahoo.glidetube.ui.OnTabReselected
import io.github.shivytyahoo.glidetube.ui.components.layout.flowBottomContentPadding
import io.github.shivytyahoo.glidetube.ui.components.layout.navigation.GlideTab
import io.github.shivytyahoo.glidetube.ui.components.library.LibrarySection
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideEmptyState
import io.github.shivytyahoo.glidetube.ui.components.shared.MediaKind
import io.github.shivytyahoo.glidetube.ui.screens.library.LibrarySectionHeader
import io.github.shivytyahoo.glidetube.ui.screens.library.LibrarySectionRow
import io.github.shivytyahoo.glidetube.ui.screens.library.LibraryViewModel
import io.github.shivytyahoo.glidetube.ui.screens.library.libraryShelves
import io.github.shivytyahoo.glidetube.ui.screens.library.visibleContentSections
import io.github.shivytyahoo.glidetube.ui.screens.library.visibleSettingsSections
import io.github.shivytyahoo.glidetube.ui.tv.components.TvScreenFrame

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
    val listState = rememberLazyListState()
    // The phone Library reselected this way since 2026-11-12 (scroll up + force
    // refresh); TV Library is a separate screen and never had the listener, so a
    // rail tap on TV did nothing at all.
    OnTabReselected(GlideTab.Library.route) {
        listState.animateScrollToItem(0)
        viewModel.refreshOnReturn(force = true)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshOnReturn() }

    TvScreenFrame(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = flowBottomContentPadding(extra = 24.dp)),
            verticalArrangement = Arrangement.spacedBy(ShelfSpacing),
        ) {
            val hidden = hiddenSections ?: return@LazyColumn
            if (isLibraryEmpty) {
                item(key = "library-empty", contentType = "empty") {
                    GlideEmptyState(
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
            // Local Media is a settings section, not a content section, so the shelves above never
            // list it — without this row the TV Library has no way into it at all (2026-11-15).
            val settingsSections = visibleSettingsSections(hidden).filter { it == LibrarySection.LOCAL_MEDIA }
            if (settingsSections.isNotEmpty()) {
                item(key = "library-settings", contentType = "settings") {
                    Column {
                        LibrarySectionHeader(stringResource(R.string.library_section_header))
                        settingsSections.forEach { section ->
                            LibrarySectionRow(section, counts = null) {
                                when (section) {
                                    LibrarySection.LOCAL_MEDIA -> onOpenLocalMedia()
                                    else -> Unit
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
