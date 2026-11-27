package io.github.shivytyahoo.glidetube.ui.screens.settings.index

import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.ui.components.settings.SettingEntry
import io.github.shivytyahoo.glidetube.ui.components.settings.SettingsDestination

internal object BackupIndex {
    private val page = SettingsDestination.BACKUP

    private fun entry(
        key: String,
        title: Int,
    ) = SettingEntry(
        key = "backup.$key",
        title = title,
        section = R.string.settings_backup_title,
        destination = page,
    )

    val export = entry("export", R.string.settings_backup_tab_export)
    val import = entry("import", R.string.settings_backup_tab_import)

    val all = listOf(export, import)
}
