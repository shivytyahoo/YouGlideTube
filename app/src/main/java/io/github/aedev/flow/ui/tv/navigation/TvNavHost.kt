package io.github.aedev.flow.ui.tv.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.github.aedev.flow.R
import io.github.aedev.flow.data.localmedia.toMusicTrack
import io.github.aedev.flow.data.localmedia.toVideo
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.DownloadedTrack
import io.github.aedev.flow.data.video.DownloadedVideo
import io.github.aedev.flow.data.music.model.toMusicTrack
import io.github.aedev.flow.data.music.model.toVideo
import io.github.aedev.flow.data.shorts.queue.ShortsQueueSource
import io.github.aedev.flow.ui.components.layout.navigation.FlowTab
import io.github.aedev.flow.ui.components.shared.MediaKind
import io.github.aedev.flow.ui.screens.channel.ChannelScreen
import io.github.aedev.flow.ui.screens.history.HistoryScreen
import io.github.aedev.flow.ui.screens.home.HomeViewModel
import io.github.aedev.flow.ui.screens.library.DownloadsScreen
import io.github.aedev.flow.ui.screens.library.LocalMediaScreen
import io.github.aedev.flow.ui.screens.music.MusicPlayerViewModel
import io.github.aedev.flow.ui.screens.music.MusicViewModel
import io.github.aedev.flow.ui.screens.onboarding.OnboardingScreen
import io.github.aedev.flow.ui.screens.playlists.PlaylistDetailScreen
import io.github.aedev.flow.ui.screens.playlists.PlaylistsScreen
import io.github.aedev.flow.ui.screens.search.SearchViewModel
import io.github.aedev.flow.ui.screens.shorts.ShortsScreen
import io.github.aedev.flow.ui.screens.subscriptions.SubscriptionsViewModel
import io.github.aedev.flow.ui.tv.components.TvScreenFrame
import io.github.aedev.flow.ui.tv.screens.TvHomeScreen
import io.github.aedev.flow.ui.tv.screens.TvLibraryScreen
import io.github.aedev.flow.ui.tv.screens.TvSearchScreen
import io.github.aedev.flow.ui.tv.screens.TvSubscriptionsScreen
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens

/** Top-level TV navigation graph plus detail routes (channel, …). */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun TvNavHost(
    navController: NavHostController,
    startRoute: String,
    onboardingTargetRoute: String = startRoute,
    homeViewModel: HomeViewModel,
    musicViewModel: MusicViewModel,
    musicPlayerViewModel: MusicPlayerViewModel,
    subscriptionsViewModel: SubscriptionsViewModel,
    searchViewModel: SearchViewModel,
    onPlayVideo: (Video) -> Unit,
    onPlayPlaylist: (videos: List<Video>, startIndex: Int, title: String, shuffle: Boolean) -> Unit,
    onPlayVideoId: (String) -> Unit,
    onSelectRoot: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val openChannel: (String) -> Unit = { channelRef ->
        navController.navigate(TvRoutes.channel(channelRef))
    }
    val downloadsTitle = stringResource(R.string.library_downloads_label)
    val localTitle = stringResource(R.string.local_media_title)
    val playDownloadedVideos: (List<DownloadedVideo>, Int) -> Unit = { videos, index ->
        onPlayPlaylist(videos.map { it.video }, index, downloadsTitle, false)
    }
    val playDownloadedMusic: (List<DownloadedTrack>, Int) -> Unit = { tracks, index ->
        val queue = tracks.map { it.track }
        musicPlayerViewModel.loadAndPlayTrack(queue[index], queue, downloadsTitle)
    }
    NavHost(
        navController = navController,
        startDestination = startRoute,
        modifier = modifier,
    ) {
        composable("onboarding") {
            // TvScreenFrame covers the top and end edges only: the rail owns the start
            // edge on other pages, but it is hidden during onboarding, and nothing
            // reserves the bottom edge — so the frame is closed here, or the title and
            // the button sit in the cropped overscan zone on every TV.
            val dimens = LocalTvDimens.current
            TvScreenFrame {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(start = dimens.overscanHorizontal, bottom = dimens.overscanVertical),
                ) {
                    OnboardingScreen(
                        onComplete = {
                            navController.navigate(onboardingTargetRoute) {
                                popUpTo("onboarding") { inclusive = true }
                            }
                        },
                    )
                }
            }
        }
        composable(FlowTab.Home.route) {
            TvHomeScreen(
                viewModel = homeViewModel,
                onVideoClick = onPlayVideo,
                onPlayPlaylist = { videos, title -> onPlayPlaylist(videos, 0, title, false) },
                onOpenLibrary = { onSelectRoot(FlowTab.Library.route) },
                onOpenSettings = { onSelectRoot(TV_SETTINGS_ROUTE) },
                onOpenSearch = { onSelectRoot(FlowTab.Search.route) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        composable(FlowTab.Subscriptions.route) {
            TvSubscriptionsScreen(
                viewModel = subscriptionsViewModel,
                onVideoClick = onPlayVideo,
                onChannelClick = { channel -> openChannel(channel.url.ifBlank { channel.id }) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        composable(FlowTab.Shorts.route) {
            ShortsScreen(
                source = ShortsQueueSource.Feed,
                enableDpadPaging = true,
                onBack = { navController.popBackStack() },
                onChannelClick = { channelId -> openChannel(channelId) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        composable(FlowTab.Search.route) {
            TvSearchScreen(
                viewModel = searchViewModel,
                onVideoClick = onPlayVideo,
                onChannelClick = { channel -> openChannel(channel.url.ifBlank { channel.id }) },
                onPlaylistClick = { playlist -> navController.navigate(TvRoutes.playlist(playlist.id)) },
                onPlayTrack = musicPlayerViewModel::loadAndPlayTrack,
                onOpenAlbum = { navController.navigate(TvRoutes.musicCollection(it)) },
                onOpenArtist = { navController.navigate(TvRoutes.musicArtist(it)) },
                onOpenMusicPlaylist = { navController.navigate(TvRoutes.musicCollection(it)) },
                onOpenMood = { navController.navigate(TvRoutes.musicBrowse(it)) },
                onOpenMoods = { navController.navigate(TvRoutes.MOODS) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        composable(FlowTab.Library.route) {
            TvLibraryScreen(
                onVideoClick = onPlayVideo,
                onMusicClick = musicPlayerViewModel::loadAndPlayTrack,
                onOpenHistory = { navController.navigate(TvRoutes.HISTORY) },
                onOpenPlaylists = { kind -> navController.navigate(TvRoutes.playlists(kind)) },
                onOpenPlaylist = { navController.navigate(TvRoutes.playlist(it)) },
                onOpenMusicPlaylist = { navController.navigate(TvRoutes.musicCollection(it)) },
                onOpenDownloads = { navController.navigate(TvRoutes.DOWNLOADS) },
                onOpenLocalMedia = { navController.navigate(TvRoutes.LOCAL_MEDIA) },
                onDownloadedVideoClick = playDownloadedVideos,
                onDownloadedMusicClick = playDownloadedMusic,
                modifier = Modifier.fillMaxSize(),
            )
        }
        composable(TvRoutes.LOCAL_MEDIA) {
            TvScreenFrame {
                LocalMediaScreen(
                    onBackClick = { navController.popBackStack() },
                    onPlayVideos = { items, index, shuffle ->
                        onPlayPlaylist(items.map { it.toVideo() }, index, localTitle, shuffle)
                    },
                    onPlayMusic = { items, index, shuffle ->
                        val tracks = items.map { it.toMusicTrack() }.let { if (shuffle) it.shuffled() else it }
                        val start = if (shuffle) tracks.first() else tracks[index]
                        musicPlayerViewModel.loadAndPlayTrack(start, tracks, localTitle)
                    },
                    onOpenSettings = { onSelectRoot(TV_SETTINGS_ROUTE) },
                )
            }
        }
        composable(TvRoutes.DOWNLOADS) {
            TvScreenFrame {
                DownloadsScreen(
                    onBackClick = { navController.popBackStack() },
                    onVideoClick = playDownloadedVideos,
                    onMusicClick = playDownloadedMusic,
                    onOpenCollection = { summary ->
                        val id = summary.collection.id
                        navController.navigate(if (summary.collection.kind.isMusic) TvRoutes.musicCollection(id) else TvRoutes.playlist(id))
                    },
                    onHomeClick = { onSelectRoot(startRoute) },
                )
            }
        }
        composable(TvRoutes.HISTORY) {
            TvScreenFrame {
                HistoryScreen(
                    onVideoClick = { track -> onPlayVideo(track.toVideo()) },
                    onBackClick = { navController.popBackStack() },
                    onShortsQueue = {},
                    onMusicClick = { track, queue -> musicPlayerViewModel.loadAndPlayTrack(track, queue) },
                )
            }
        }
        composable(
            route = TvRoutes.PLAYLISTS,
            arguments =
                listOf(
                    navArgument(TvRoutes.PLAYLISTS_KIND_ARG) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
        ) { entry ->
            val kind =
                entry.arguments?.getString(TvRoutes.PLAYLISTS_KIND_ARG)?.let { name ->
                    MediaKind.entries.firstOrNull {
                        it.name ==
                            name
                    }
                }
            TvScreenFrame {
                PlaylistsScreen(
                    fixedKind = kind,
                    onBackClick = { navController.popBackStack() },
                    onVideoPlaylistClick = { navController.navigate(TvRoutes.playlist(it.id)) },
                    onMusicPlaylistClick = { navController.navigate(TvRoutes.musicCollection(it.id)) },
                )
            }
        }
        composable(
            route = TvRoutes.CHANNEL,
            arguments =
                listOf(
                    navArgument(TvRoutes.CHANNEL_ARG) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
        ) { entry ->
            val channelRef =
                entry.arguments
                    ?.getString(TvRoutes.CHANNEL_ARG)
                    ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                    .orEmpty()
            TvScreenFrame {
                ChannelScreen(
                    channelUrl = channelRef,
                    onVideoClick = onPlayVideo,
                    onChannelClick = openChannel,
                    onShortClick = { videoId, _ -> onPlayVideoId(videoId) },
                    onPlaylistClick = { navController.navigate(TvRoutes.playlist(it)) },
                    onBackClick = { navController.popBackStack() },
                    showShorts = false,
                )
            }
        }
        composable(
            route = TvRoutes.PLAYLIST,
            arguments = listOf(navArgument(TvRoutes.PLAYLIST_ARG) { type = NavType.StringType }),
        ) {
            TvScreenFrame {
                PlaylistDetailScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onPlayPlaylist = { videos, index, shuffle, title ->
                        val start = videos[index]
                        if (start.isMusic) {
                            val tracks = videos.filter { it.isMusic }.map { it.toMusicTrack() }
                            musicPlayerViewModel.loadAndPlayTrack(start.toMusicTrack(), tracks, title)
                        } else {
                            onPlayPlaylist(videos, index, title, shuffle)
                        }
                    },
                )
            }
        }
        tvMusicRoutes(
            navController = navController,
            musicViewModel = musicViewModel,
            musicPlayerViewModel = musicPlayerViewModel,
            onPlayVideo = onPlayVideo,
            onSelectRoot = onSelectRoot,
        )
        tvSettingsRoutes(navController = navController)
    }
}
