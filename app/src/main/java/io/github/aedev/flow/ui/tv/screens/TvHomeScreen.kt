package io.github.aedev.flow.ui.tv.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.components.rememberFeedGridLayout
import io.github.aedev.flow.ui.components.shared.FlowLogo
import io.github.aedev.flow.ui.components.shared.dpadFocusGroup
import io.github.aedev.flow.ui.screens.home.HomeFeedBody
import io.github.aedev.flow.ui.screens.home.HomeFeedEffects
import io.github.aedev.flow.ui.screens.home.HomeViewModel
import io.github.aedev.flow.ui.screens.home.chips.HomeChipRow
import io.github.aedev.flow.ui.screens.home.rememberHomeGridStates
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens

/** Grid cells sit edge to edge and each card insets itself, so this much of the margin is already there. */
private val CardInset = 16.dp

/**
 * TV Home: Home's own chips and feed, with the phone's cards, shelves, states and
 * prefetch. No page title (the rail says where you are) and no Refresh button: selecting Home on the
 * rail again scrolls up and then refreshes, as reselecting the tab does on a phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvHomeScreen(
    viewModel: HomeViewModel,
    onVideoClick: (Video) -> Unit,
    onPlayPlaylist: (List<Video>, String) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
    settings: TvHomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val dimens = LocalTvDimens.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val chips by viewModel.chips.collectAsStateWithLifecycle()
    val feedEnabled by settings.feedEnabled.collectAsStateWithLifecycle()
    val refreshOnReselect by settings.refreshOnReselect.collectAsStateWithLifecycle()
    val gridStates = rememberHomeGridStates()

    LaunchedEffect(viewModel) {
        viewModel.initialize(context.applicationContext)
    }
    HomeFeedEffects(
        viewModel = viewModel,
        uiState = uiState,
        chips = chips,
        gridStates = gridStates,
        homeFeedEnabled = feedEnabled,
        refreshHomeOnReselect = refreshOnReselect,
    )

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(top = dimens.overscanVertical),
    ) {
        if (feedEnabled && chips.chips.size > 1) {
            HomeChipRow(
                state = chips,
                scrollState = rememberTopAppBarState(),
                onSelect = viewModel::selectChip,
                modifier = Modifier.dpadFocusGroup(),
            )
        }
        BoxWithConstraints(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(end = dimens.overscanHorizontal - CardInset),
        ) {
            HomeFeedBody(
                viewModel = viewModel,
                uiState = uiState,
                chips = chips,
                gridStates = gridStates,
                feedLayout =
                    rememberFeedGridLayout(
                        maxWidth = maxWidth,
                        maxAutoColumns = dimens.feedMaxColumns,
                        minCellWidth = dimens.feedCardMinWidth,
                    ),
                isListView = false,
                homeFeedEnabled = feedEnabled,
                onVideoClick = onVideoClick,
                onPlayMix = onPlayPlaylist,
                onShortClick = {},
                onNavigateToHistory = onOpenLibrary,
                onOpenShortsFeed = {},
                showShorts = false,
            )
            if (uiState.isGuestEmpty && !uiState.isLoading) {
                TvGuestLanding(
                    onOpenSettings = onOpenSettings,
                    onOpenSearch = onOpenSearch,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** The phone's signed-out landing, laid out for a remote: Sign in takes focus first. */
@Composable
private fun TvGuestLanding(
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val signInFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { signInFocusRequester.requestFocus() }
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        FlowLogo(Modifier.width(120.dp))
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.home_guest_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.home_guest_message),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onOpenSettings,
            modifier = Modifier.focusRequester(signInFocusRequester),
        ) {
            Text(stringResource(R.string.home_guest_sign_in))
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onOpenSearch) {
            Text(stringResource(R.string.search))
        }
    }
}
