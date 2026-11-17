package io.github.shivytyahoo.glidetube.ui.screens.settings.index

import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.ui.components.music.section.MusicHomeShelf
import io.github.shivytyahoo.glidetube.ui.components.settings.SettingEntry
import io.github.shivytyahoo.glidetube.ui.components.settings.SettingsDestination

internal object MusicHomeIndex {
    private val entries: Map<MusicHomeShelf, SettingEntry> =
        MusicHomeShelf.entries.associateWith { shelf ->
            SettingEntry(
                key = "music_home.${shelf.name.lowercase()}",
                title = shelf.labelRes,
                section = R.string.settings_music_home_sections,
                destination = SettingsDestination.MUSIC_HOME,
            )
        }

    fun entry(shelf: MusicHomeShelf): SettingEntry = entries.getValue(shelf)

    val all: List<SettingEntry> = MusicHomeShelf.entries.map(::entry)
}
