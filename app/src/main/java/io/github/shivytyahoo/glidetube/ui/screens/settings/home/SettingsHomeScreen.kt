package io.github.shivytyahoo.glidetube.ui.screens.settings.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.ui.components.settings.SettingEntry
import io.github.shivytyahoo.glidetube.ui.components.settings.SettingsDestination
import io.github.shivytyahoo.glidetube.ui.components.settings.SettingsPage
import io.github.shivytyahoo.glidetube.ui.components.settings.SettingsTarget
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideAlertDialog
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideChoice
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideChoiceDialog
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideSearchField
import io.github.shivytyahoo.glidetube.ui.screens.settings.index.DestinationIndex
import io.github.shivytyahoo.glidetube.ui.screens.settings.index.HomeIndex
import io.github.shivytyahoo.glidetube.ui.screens.settings.index.SettingsSearch

private val SearchFieldBottomPadding = 8.dp

/**
 * The settings list: search, the persona card, Deep Glide, and one row per settings page grouped
 * the way people look for them. Beside a detail pane, [selected] marks the page open next to it.
 */
@Composable
internal fun SettingsHomeScreen(
    selected: SettingsDestination?,
    onOpen: (SettingsTarget) -> Unit,
    onOpenUpdate: () -> Unit,
    onBack: () -> Unit,
    selectedRowFocus: FocusRequester? = null,
    viewModel: SettingsHomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val deepGlide by viewModel.deepGlide.collectAsStateWithLifecycle()
    val batterySaver by viewModel.batterySaver.collectAsStateWithLifecycle()
    val updateCheck by viewModel.updateCheck.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var query by rememberSaveable { mutableStateOf("") }
    var highlight by remember { mutableStateOf<String?>(null) }
    var showDurationDialog by rememberSaveable { mutableStateOf(false) }

    val checkingLabel = stringResource(R.string.checking_for_updates)
    val searchable = rememberSearchableSettings()
    LifecycleResumeEffect(selected) {
        onPauseOrDispose {}
    }
    val results = remember(query, searchable) { SettingsSearch.search(query, searchable) }

    UpdateCheckFeedback(
        state = updateCheck,
        snackbarHostState = snackbarHostState,
        onConsumed = viewModel::consumeUpdateCheck,
    )

    val onResultClick: (SettingEntry) -> Unit = { entry ->
        query = ""
        val page = DestinationIndex.destinationOf(entry)
        when {
            entry.key == HomeIndex.checkForUpdates.key -> viewModel.checkForUpdates()
            page != null -> onOpen(SettingsTarget(page))
            entry.destination == SettingsDestination.HOME -> highlight = entry.key
            else -> onOpen(entry.target)
        }
    }

    SettingsPage(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
        highlight = highlight,
        snackbarHostState = snackbarHostState,
        header = {
            GlideSearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.ui_search_settings),
                onClear = { query = "" },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = SearchFieldBottomPadding),
            )
        },
    ) {
        if (query.isNotBlank()) {
            searchResults(query = query, results = results, onResultClick = onResultClick)
        } else {
            homeContent(
                state =
                    SettingsHomeState(
                        selected = selected,
                        deepGlide = deepGlide,
                        batterySaver = batterySaver,
                        updateCheckingLabel = checkingLabel.takeIf { updateCheck == UpdateCheckState.Checking },
                        selectedRowFocus = selectedRowFocus,
                    ),
                actions =
                    SettingsHomeActions(
                        onOpen = onOpen,
                        onDeepGlideChange = viewModel::setDeepGlideEnabled,
                        onBatterySaverChange = viewModel::setBatterySaverEnabled,
                        onDurationClick = { showDurationDialog = true },
                        onSaveHistoryChange = viewModel::setDeepGlideSaveToHistory,
                        onScrobbleChange = viewModel::setDeepGlideScrobble,
                        onShowOnSearchChange = viewModel::setDeepGlideOnSearch,
                        onCheckForUpdates = viewModel::checkForUpdates,
                    ),
            )
        }
    }

    if (showDurationDialog) {
        GlideChoiceDialog(
            title = stringResource(R.string.deep_glide_dialog_title),
            description = stringResource(R.string.deep_glide_dialog_body),
            icon = Icons.Outlined.Timer,
            options = DeepGlideDurations.map { GlideChoice(it, deepGlideDurationLabel(it)) },
            selected = deepGlide.expireHours,
            onSelect = viewModel::setDeepGlideExpireHours,
            onDismiss = { showDurationDialog = false },
        )
    }

    LaunchedEffect(updateCheck) {
        if (updateCheck is UpdateCheckState.Available) {
            viewModel.consumeUpdateCheck()
            onOpenUpdate()
        }
    }
}

@Composable
private fun UpdateCheckFeedback(
    state: UpdateCheckState,
    snackbarHostState: SnackbarHostState,
    onConsumed: () -> Unit,
) {
    val upToDate = stringResource(R.string.glide_is_up_to_date)
    val failed = stringResource(R.string.update_check_failed)
    LaunchedEffect(state) {
        val message =
            when (state) {
                UpdateCheckState.UpToDate -> upToDate
                UpdateCheckState.Failed -> failed
                else -> return@LaunchedEffect
            }
        // Consuming first would change this effect's key and cancel it before the snackbar shows.
        try {
            snackbarHostState.showSnackbar(message)
        } finally {
            onConsumed()
        }
    }
}
