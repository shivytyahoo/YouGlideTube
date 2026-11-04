package io.github.aedev.flow.ui.screens.home

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import io.github.aedev.flow.ui.screens.settings.account.GoogleLoginActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.HomeFeedColumns
import io.github.aedev.flow.data.local.HomeViewMode
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.VideoHistoryEntry
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.shorts.queue.ShortsQueueSource
import io.github.aedev.flow.player.DeepFlowManager
import io.github.aedev.flow.ui.components.layout.LocalFlowBottomInsets
import io.github.aedev.flow.ui.components.layout.floatAboveBottomChrome
import io.github.aedev.flow.ui.components.layout.topbar.FlowTopBar
import io.github.aedev.flow.ui.components.rememberFeedGridLayout
import io.github.aedev.flow.ui.components.shared.FlowPullToRefreshBox
import io.github.aedev.flow.ui.screens.home.chips.HomeChipRow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val MILLIS_PER_SECOND = 1000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onVideoClick: (Video) -> Unit,
    onShortClick: (ShortsQueueSource) -> Unit,
    onSearchClick: () -> Unit,
    onNavigateToHistory: () -> Unit = {},
    onOpenShortsFeed: () -> Unit = {},
    onPlayMix: (videos: List<Video>, title: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val preferences = remember(context) { PlayerPreferences(context) }
    val homeViewMode by preferences.homeViewMode.collectAsStateWithLifecycle(initialValue = HomeViewMode.GRID)
    val homeFeedColumns by preferences.homeFeedColumns.collectAsStateWithLifecycle(initialValue = HomeFeedColumns.AUTO)
    val homeFeedEnabled by preferences.homeFeedEnabled.collectAsStateWithLifecycle(initialValue = true)
    val refreshHomeOnReselect by preferences.refreshHomeOnReselect.collectAsStateWithLifecycle(initialValue = true)
    val showAppLogoIcon by preferences.showAppLogoIcon.collectAsStateWithLifecycle(initialValue = true)
    val deepFlowActive by preferences.deepFlowActive.collectAsStateWithLifecycle(initialValue = false)

    val gridStates = rememberHomeGridStates()
    val coroutineScope = rememberCoroutineScope()
    val chips by viewModel.chips.collectAsStateWithLifecycle()
    val chipRowState = rememberTopAppBarState()
    val chipRowBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(chipRowState)
    val snackbarHostState = remember { SnackbarHostState() }
    val refreshFailed = stringResource(R.string.home_refresh_failed)
    val retryLabel = stringResource(R.string.retry)

    LaunchedEffect(viewModel) {
        viewModel.refreshFailures.collectLatest {
            val result = snackbarHostState.showSnackbar(refreshFailed, actionLabel = retryLabel, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) viewModel.refreshFeed()
        }
    }

    HomeFeedEffects(
        viewModel = viewModel,
        uiState = uiState,
        chips = chips,
        gridStates = gridStates,
        homeFeedEnabled = homeFeedEnabled,
        refreshHomeOnReselect = refreshHomeOnReselect,
    )

    val haptic = LocalHapticFeedback.current
    val deepFlowLabel = stringResource(R.string.deep_flow_mode_title)
    val toggleDeepFlow: () -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        coroutineScope.launch { DeepFlowManager.toggle(context) }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0.dp),
        containerColor = Color.Transparent,
        snackbarHost = {
            SnackbarHost(snackbarHostState, Modifier.floatAboveBottomChrome(LocalFlowBottomInsets.current))
        },
        topBar = {
            FlowTopBar(
                title = {
                    Text(
                        stringResource(R.string.app_name),
                        modifier = Modifier.deepFlowLongPress(deepFlowLabel, toggleDeepFlow),
                        style =
                            MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                    )
                },
                leading =
                    if (showAppLogoIcon) {
                        {
                            FlowHeaderLogoIcon(
                                isDeepFlowActive = deepFlowActive,
                                modifier = Modifier.size(32.dp).deepFlowLongPress(deepFlowLabel, toggleDeepFlow),
                            )
                        }
                    } else {
                        null
                    },
                actions = {
                    IconButton(onClick = onSearchClick) {
                        Icon(
                            Icons.Outlined.Search,
                            contentDescription = stringResource(R.string.search),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Color.Transparent),
        ) {
            if (homeFeedEnabled && chips.chips.size > 1) {
                HomeChipRow(
                    state = chips,
                    scrollState = chipRowState,
                    onSelect = { key ->
                        chipRowState.heightOffset = 0f
                        viewModel.selectChip(key)
                    },
                )
            }
            ResettableHomePullToRefreshBox(
                resetKey = uiState.isLoading && uiState.videos.isEmpty(),
                isRefreshing = if (chips.isAll) uiState.isRefreshing else chips.isRefreshing,
                onRefresh = { if (chips.isAll) viewModel.refreshFeed() else viewModel.refreshSelectedChip() },
                modifier =
                    Modifier
                        .fillMaxSize()
                        .then(
                            // The enter-always behaviour collapses the chip row before the feed
                            // scrolls, and only the composed chip row sets its height limit. With
                            // no chip row the limit stays unbounded and the connection would
                            // swallow every scroll delta, freezing the feed on its first screen.
                            if (chips.chips.size > 1) {
                                Modifier.nestedScroll(chipRowBehavior.nestedScrollConnection)
                            } else {
                                Modifier
                            },
                        ),
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    HomeFeedBody(
                        viewModel = viewModel,
                        uiState = uiState,
                        chips = chips,
                        gridStates = gridStates,
                        feedLayout = rememberFeedGridLayout(maxWidth, homeFeedColumns),
                        isListView = homeViewMode == HomeViewMode.LIST,
                        homeFeedEnabled = homeFeedEnabled,
                        onVideoClick = onVideoClick,
                        onPlayMix = onPlayMix,
                        onShortClick = onShortClick,
                        onNavigateToHistory = onNavigateToHistory,
                        onOpenShortsFeed = onOpenShortsFeed,
                    )
                }
                // Signed out with nothing to show: the calm landing, not an error. (A failed
                // signed-in load is rendered once, by HomeFeedBody's error state — this screen
                // used to draw a second one over it, two Retry buttons deep.)
                if (uiState.isGuestEmpty && !uiState.isLoading) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(Color.Transparent)
                                .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        FlowHeaderLogoIcon(
                            isDeepFlowActive = deepFlowActive,
                            modifier = Modifier.size(76.dp),
                        )
                        Spacer(Modifier.height(20.dp))
                        Text(
                            text = stringResource(R.string.home_guest_title),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.home_guest_message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = {
                                context.startActivity(
                                    Intent(context, GoogleLoginActivity::class.java),
                                )
                            },
                        ) {
                            Text(stringResource(R.string.home_guest_sign_in))
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onSearchClick) {
                            Text(stringResource(R.string.search))
                        }
                    }
                }
            }
        }
    }
}

// Deliberately not data/model's VideoHistoryEntry.toVideo(): that one carries isShort, which would
// reroute a resumed short to the Shorts player instead of the video player.
internal fun VideoHistoryEntry.toResumeVideo(): Video =
    Video(
        id = videoId,
        title = title,
        channelName = channelName,
        channelId = channelId,
        thumbnailUrl = thumbnailUrl,
        duration = (duration / MILLIS_PER_SECOND).toInt(),
        viewCount = 0L,
        uploadDate = "",
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResettableHomePullToRefreshBox(
    resetKey: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    key(resetKey) {
        FlowPullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = modifier,
            content = content,
        )
    }
}
