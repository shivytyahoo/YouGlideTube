package io.github.shivytyahoo.glidetube.ui.screens.settings.index

import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.ui.components.settings.SettingAvailability
import io.github.shivytyahoo.glidetube.ui.components.settings.SettingEntry
import io.github.shivytyahoo.glidetube.ui.components.settings.SettingsDestination

/** Options that live directly on the settings list rather than on a page of their own. */
internal object HomeIndex {
    private val home = SettingsDestination.HOME

    val deepGlide =
        SettingEntry(
            key = "home.deep_glide",
            title = R.string.deep_glide_mode_title,
            summary = R.string.deep_glide_mode_subtitle,
            section = R.string.settings_glide_engine_header,
            destination = home,
        )
    val deepGlideDuration =
        SettingEntry(
            key = "home.deep_glide_duration",
            title = R.string.deep_glide_expire_duration_title,
            section = R.string.settings_glide_engine_header,
            destination = home,
        )
    val deepGlideHistory =
        SettingEntry(
            key = "home.deep_glide_history",
            title = R.string.deep_glide_save_history_title,
            summary = R.string.deep_glide_save_history_subtitle,
            section = R.string.settings_glide_engine_header,
            destination = home,
        )
    val deepGlideScrobble =
        SettingEntry(
            key = "home.deep_glide_scrobble",
            title = R.string.deep_glide_scrobble_title,
            summary = R.string.deep_glide_scrobble_subtitle,
            section = R.string.settings_glide_engine_header,
            destination = home,
        )
    val deepGlideOnSearch =
        SettingEntry(
            key = "home.deep_glide_on_search",
            title = R.string.deep_glide_on_search_title,
            summary = R.string.deep_glide_on_search_subtitle,
            section = R.string.settings_glide_engine_header,
            destination = home,
        )
    val batterySaver =
        SettingEntry(
            key = "home.battery_saver",
            title = R.string.settings_battery_saver_title,
            summary = R.string.settings_battery_saver_subtitle,
            keywords = R.string.settings_keywords_battery_saver,
            section = R.string.settings_battery_saver_title,
            destination = home,
        )
    val checkForUpdates =
        SettingEntry(
            key = "home.updates",
            title = R.string.check_for_updates,
            summary = R.string.check_for_updates_subtitle,
            section = R.string.settings_header_about,
            destination = home,
            availability = SettingAvailability.UpdaterOnly,
        )
    /** The persona card is found through Your taste's own destination entry, not listed twice. */
    val all = listOf(batterySaver, deepGlide, deepGlideDuration, deepGlideHistory, deepGlideScrobble, deepGlideOnSearch, checkForUpdates)
}
