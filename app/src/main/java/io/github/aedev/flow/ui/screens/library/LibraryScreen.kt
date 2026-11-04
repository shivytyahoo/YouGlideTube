package io.github.aedev.flow.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.DownloadedTrack
import io.github.aedev.flow.data.video.DownloadedVideo
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.OnTabReselected
import io.github.aedev.flow.ui.components.layout.flowBottomContentPadding
import io.github.aedev.flow.ui.components.layout.navigation.FlowTab
import io.github.aedev.flow.ui.components.layout.topbar.FlowTopBar
import io.github.aedev.flow.ui.components.library.LibraryNavigationRow
import io.github.aedev.flow.ui.components.library.LibrarySection
import io.github.aedev.flow.ui.components.shared.FlowEmptyState
import io.github.aedev.flow.ui.components.shared.FlowMaxContentWidth
import io.github.aedev.flow.ui.components.shared.MediaKind

private val ListVerticalPadding = 12.dp
private val ShelfSpacing = 24.dp

@Composable
fun LibraryScreen(
    onNavigateToHistory: () -> Unit,
    onNavigateToPlaylists: (MediaKind?) -> Unit,
    onNavigateToLikedVideos: () -> Unit,
    onNavigateToLikedMusic: () -> Unit,
    onNavigateToWatchLater: () -> Unit,
    onNavigateToDownloads: () -> Unit,
    onNavigateToLocalMedia: () -> Unit,
    onManageData: () -> Unit,
    onVideoClick: (Video) -> Unit,
    onMusicClick: (MusicTrack, List<MusicTrack>, String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onMusicPlaylistClick: (String) -> Unit,
    onDownloadedVideoClick: (List<DownloadedVideo>, Int) -> Unit,
    onDownloadedMusicClick: (List<DownloadedTrack>, Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val shortsEnabled by viewModel.shortsEnabled.collectAsStateWithLifecycle()
    val shelfPreviewsEnabled by viewModel.shelfPreviewsEnabled.collectAsStateWithLifecycle()
    val separatePlaylistKinds by viewModel.separatePlaylistKinds.collectAsStateWithLifecycle()
    val isLibraryEmpty by viewModel.isLibraryEmpty.collectAsStateWithLifecycle()
    val hiddenSections by viewModel.hiddenSections.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    OnTabReselected(FlowTab.Library.route) { listState.animateScrollToItem(0) }
    val openSection: (LibrarySection) -> Unit = { section ->
        when (section) {
            LibrarySection.HISTORY -> onNavigateToHistory()
            LibrarySection.PLAYLISTS -> onNavigateToPlaylists(null)
            LibrarySection.VIDEO_PLAYLISTS -> onNavigateToPlaylists(MediaKind.Videos)
            LibrarySection.MUSIC_PLAYLISTS -> onNavigateToPlaylists(MediaKind.Music)
            LibrarySection.WATCH_LATER -> onNavigateToWatchLater()
            LibrarySection.LIKED_VIDEOS -> onNavigateToLikedVideos()
            LibrarySection.LIKED_MUSIC -> onNavigateToLikedMusic()
            LibrarySection.DOWNLOADS -> onNavigateToDownloads()
            LibrarySection.LOCAL_MEDIA -> onNavigateToLocalMedia()
            LibrarySection.SETTINGS -> onManageData()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        containerColor = Color.Transparent,
        topBar = { FlowTopBar(title = stringResource(R.string.library)) },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier =
                modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Color.Transparent),
            contentPadding = PaddingValues(top = ListVerticalPadding, bottom = flowBottomContentPadding(ListVerticalPadding)),
            verticalArrangement = Arrangement.spacedBy(ShelfSpacing),
        ) {
            val hidden = hiddenSections ?: return@LazyColumn
            val contentSections = visibleContentSections(hidden, separatePlaylistKinds, shortsEnabled)
            val settingsSections = visibleSettingsSections(hidden)
            if (shelfPreviewsEnabled && isLibraryEmpty) {
                item(key = "library-empty", contentType = "empty") {
                    FlowEmptyState(
                        title = stringResource(R.string.library_empty_title),
                        subtitle = stringResource(R.string.library_empty_body),
                        icon = Icons.Outlined.VideoLibrary,
                    )
                }
            } else if (shelfPreviewsEnabled) {
                libraryShelves(
                    viewModel = viewModel,
                    sections = contentSections,
                    onOpenSection = openSection,
                    onVideoClick = onVideoClick,
                    onMusicClick = onMusicClick,
                    onPlaylistClick = onPlaylistClick,
                    onMusicPlaylistClick = onMusicPlaylistClick,
                    onDownloadedVideoClick = onDownloadedVideoClick,
                    onDownloadedMusicClick = onDownloadedMusicClick,
                )
            } else if (contentSections.isNotEmpty()) {
                item(key = "sections", contentType = "navigation-section") {
                    val counts by viewModel.counts.collectAsStateWithLifecycle()
                    LibrarySectionList(sections = contentSections, counts = counts, onOpenSection = openSection)
                }
            }

            val emptyLibraryDownloads = shelfPreviewsEnabled && isLibraryEmpty && LibrarySection.DOWNLOADS in contentSections
            if (emptyLibraryDownloads || settingsSections.isNotEmpty()) {
                item(key = "settings-data", contentType = "navigation-section") {
                    Column(modifier = Modifier.widthIn(max = FlowMaxContentWidth).padding(horizontal = 16.dp)) {
                        LibrarySectionHeader(stringResource(R.string.library_settings_data_header))
                        if (emptyLibraryDownloads) {
                            LibraryNavigationRow(
                                icon = LibrarySection.DOWNLOADS.icon,
                                title = LibrarySection.DOWNLOADS.title,
                                subtitle = stringResource(R.string.empty_downloads),
                                onClick = onNavigateToDownloads,
                            )
                        }
                        settingsSections.forEach { section ->
                            LibrarySectionRow(section = section, counts = null, onClick = { openSection(section) })
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

internal fun LazyListScope.libraryShelves(
    viewModel: LibraryViewModel,
    sections: List<LibrarySection>,
    onOpenSection: (LibrarySection) -> Unit,
    onVideoClick: (Video) -> Unit,
    onMusicClick: (MusicTrack, List<MusicTrack>, String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onMusicPlaylistClick: (String) -> Unit,
    onDownloadedVideoClick: (List<DownloadedVideo>, Int) -> Unit,
    onDownloadedMusicClick: (List<DownloadedTrack>, Int) -> Unit,
) {
    sections.forEach { section ->
        val onTitleClick = { onOpenSection(section) }
        when (section) {
            LibrarySection.HISTORY, LibrarySection.LIKED_MUSIC -> {
                item(key = section.name, contentType = "media-shelf") {
                    LibraryMediaShelfRoute(
                        section = section,
                        itemsFlow = if (section == LibrarySection.HISTORY) viewModel.history else viewModel.likedMusic,
                        onTitleClick = onTitleClick,
                        onVideoClick = onVideoClick,
                        onMusicClick = onMusicClick,
                        onDownloadedVideoClick = onDownloadedVideoClick,
                        onDownloadedMusicClick = onDownloadedMusicClick,
                    )
                }
            }

            LibrarySection.PLAYLISTS, LibrarySection.VIDEO_PLAYLISTS, LibrarySection.MUSIC_PLAYLISTS -> {
                item(key = section.name, contentType = "playlist-shelf") {
                    LibraryPlaylistsShelf(
                        section = section,
                        videoPlaylistsFlow = viewModel.playlists.takeIf { section != LibrarySection.MUSIC_PLAYLISTS },
                        musicPlaylistsFlow = viewModel.musicPlaylists.takeIf { section != LibrarySection.VIDEO_PLAYLISTS },
                        onTitleClick = onTitleClick,
                        onVideoPlaylistClick = onPlaylistClick,
                        onMusicPlaylistClick = onMusicPlaylistClick,
                    )
                }
            }

            LibrarySection.WATCH_LATER, LibrarySection.LIKED_VIDEOS -> {
                item(key = section.name, contentType = "video-shelf") {
                    LibraryVideoShelf(
                        section = section,
                        videosFlow = if (section == LibrarySection.WATCH_LATER) viewModel.watchLater else viewModel.likedVideos,
                        onTitleClick = onTitleClick,
                        onVideoClick = onVideoClick,
                    )
                }
            }

            LibrarySection.DOWNLOADS -> {
                item(key = section.name, contentType = "media-shelf") {
                    val activeDownloads by viewModel.activeDownloadCount.collectAsStateWithLifecycle()
                    LibraryMediaShelfRoute(
                        section = section,
                        itemsFlow = viewModel.downloads,
                        onTitleClick = onTitleClick,
                        onVideoClick = onVideoClick,
                        onMusicClick = onMusicClick,
                        onDownloadedVideoClick = onDownloadedVideoClick,
                        onDownloadedMusicClick = onDownloadedMusicClick,
                        emptyMessage =
                            stringResource(
                                if ((activeDownloads ?: 0) > 0) R.string.downloads_section_in_progress else R.string.empty_downloads,
                            ),
                    )
                }
            }

            LibrarySection.LOCAL_MEDIA, LibrarySection.SETTINGS -> {
                Unit
            }
        }
    }
}
