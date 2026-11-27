package io.github.shivytyahoo.glidetube.ui.screens.settings.home

import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import io.github.shivytyahoo.glidetube.BuildConfig
import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.ui.components.settings.SettingsDestination
import io.github.shivytyahoo.glidetube.ui.components.settings.SettingsGroupScope
import io.github.shivytyahoo.glidetube.ui.components.settings.SettingsListScope
import io.github.shivytyahoo.glidetube.ui.components.settings.SettingsTarget
import io.github.shivytyahoo.glidetube.ui.components.settings.nav
import io.github.shivytyahoo.glidetube.ui.components.settings.switch
import io.github.shivytyahoo.glidetube.ui.components.shared.DeepGlideState
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideNavRow
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideSwitchRow
import io.github.shivytyahoo.glidetube.ui.components.shared.deepGlideStatus
import io.github.shivytyahoo.glidetube.ui.screens.settings.index.DestinationIndex
import io.github.shivytyahoo.glidetube.ui.screens.settings.index.HomeIndex

@Immutable
internal data class SettingsHomeState(
    val selected: SettingsDestination?,
    val deepGlide: DeepGlideState,
    val batterySaver: Boolean,
    /** Shown on the update row while a check the user started is running; null when idle. */
    val updateCheckingLabel: String?,
    /** Attached to the [selected] page's row, so a host can return focus to it. */
    val selectedRowFocus: FocusRequester? = null,
)

internal class SettingsHomeActions(
    val onOpen: (SettingsTarget) -> Unit,
    val onDeepGlideChange: (Boolean) -> Unit,
    val onBatterySaverChange: (Boolean) -> Unit,
    val onDurationClick: () -> Unit,
    val onSaveHistoryChange: (Boolean) -> Unit,
    val onScrobbleChange: (Boolean) -> Unit,
    val onShowOnSearchChange: (Boolean) -> Unit,
    val onCheckForUpdates: () -> Unit,
)

/** The settings list when no search is running. */
internal fun SettingsListScope.homeContent(
    state: SettingsHomeState,
    actions: SettingsHomeActions,
) {
    fun SettingsGroupScope.page(destination: SettingsDestination) =
        nav(
            entry = DestinationIndex.entry(destination),
            onClick = { actions.onOpen(SettingsTarget(destination)) },
            icon = destinationIcon(destination),
            selected = state.selected == destination,
            showChevron = state.selected == null,
            modifier = state.selectedFocusModifier(destination),
        )

// First on the list, so it is one tap away: the whole point is reaching it fast.
    group(key = "home.battery", header = R.string.settings_battery_saver_title) {
        switch(
            HomeIndex.batterySaver,
            checked = state.batterySaver,
            onCheckedChange = actions.onBatterySaverChange,
            icon = HomeRowIcons.BatterySaver,
        )
    }
    group(key = "home.account", header = R.string.account_title) {
        page(SettingsDestination.ACCOUNT)
    }
    group(key = "home.watching", header = R.string.settings_group_watching) {
        page(SettingsDestination.CONTENT)
        page(SettingsDestination.PLAYBACK)
        page(SettingsDestination.QUALITY)
        page(SettingsDestination.INTEGRATIONS)
    }
    group(key = "home.look", header = R.string.settings_group_look) {
        page(SettingsDestination.WIDGETS)
        page(SettingsDestination.APPEARANCE)
        page(SettingsDestination.LANGUAGE_REGION)
    }
    group(key = "home.data", header = R.string.settings_group_data) {
        page(SettingsDestination.DOWNLOADS)
        page(SettingsDestination.BACKUP)
        page(SettingsDestination.HISTORY)
        page(SettingsDestination.PROFILES)
    }
    group(key = "home.engine", header = R.string.settings_glide_engine_header) {
        row(HomeIndex.deepGlide.key) { shape ->
            GlideSwitchRow(
                title = stringResource(HomeIndex.deepGlide.title),
                supportingText = deepGlideStatus(state.deepGlide) ?: stringResource(R.string.deep_glide_mode_subtitle),
                checked = state.deepGlide.active,
                onCheckedChange = actions.onDeepGlideChange,
                leadingIcon = HomeRowIcons.DeepGlide,
                shape = shape,
            )
        }
        row(HomeIndex.deepGlideDuration.key) { shape ->
            GlideNavRow(
                title = stringResource(HomeIndex.deepGlideDuration.title),
                supportingText = deepGlideDurationLabel(state.deepGlide.expireHours),
                onClick = actions.onDurationClick,
                leadingIcon = HomeRowIcons.DeepGlideDuration,
                shape = shape,
            )
        }
        switch(
            HomeIndex.deepGlideHistory,
            checked = state.deepGlide.saveToHistory,
            onCheckedChange = actions.onSaveHistoryChange,
            icon = HomeRowIcons.DeepGlideHistory,
        )
        switch(
            HomeIndex.deepGlideScrobble,
            checked = state.deepGlide.scrobble,
            onCheckedChange = actions.onScrobbleChange,
            icon = HomeRowIcons.DeepGlideScrobble,
        )
        switch(
            HomeIndex.deepGlideOnSearch,
            checked = state.deepGlide.showOnSearch,
            onCheckedChange = actions.onShowOnSearchChange,
            icon = HomeRowIcons.DeepGlideOnSearch,
        )
    }
    group(key = "home.system", header = R.string.settings_group_system) {
        page(SettingsDestination.NOTIFICATIONS)
        page(SettingsDestination.NETWORK)
        page(SettingsDestination.WELLBEING)
    }
    group(key = "home.about", header = R.string.settings_header_about) {
        page(SettingsDestination.ABOUT)
        page(SettingsDestination.DIAGNOSTICS)
        if (BuildConfig.UPDATER_ENABLED) {
            nav(
                HomeIndex.checkForUpdates,
                onClick = actions.onCheckForUpdates,
                value = state.updateCheckingLabel,
                enabled = state.updateCheckingLabel == null,
                showChevron = false,
                icon = HomeRowIcons.Updates,
            )
        }
    }
}

private fun SettingsHomeState.selectedFocusModifier(destination: SettingsDestination): Modifier =
    selectedRowFocus?.takeIf { selected == destination }?.let { Modifier.focusRequester(it) } ?: Modifier
