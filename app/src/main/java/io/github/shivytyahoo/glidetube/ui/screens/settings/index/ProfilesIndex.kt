package io.github.shivytyahoo.glidetube.ui.screens.settings.index

import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.ui.components.settings.SettingEntry
import io.github.shivytyahoo.glidetube.ui.components.settings.SettingsDestination

internal object ProfilesIndex {
    private val page = SettingsDestination.PROFILES

    private fun entry(
        key: String,
        title: Int,
    ) = SettingEntry(
        key = "profiles.$key",
        title = title,
        section = R.string.settings_profiles_title,
        destination = page,
    )

    val addPerson = entry("add_person", R.string.profiles_add_person)
    val askOnOpen = entry("ask_on_open", R.string.profiles_ask_on_open)

    val all = listOf(addPerson, askOnOpen)
}
