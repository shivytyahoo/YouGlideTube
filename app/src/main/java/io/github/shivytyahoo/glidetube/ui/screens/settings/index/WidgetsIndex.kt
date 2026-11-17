package io.github.shivytyahoo.glidetube.ui.screens.settings.index

import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.ui.components.settings.SettingEntry
import io.github.shivytyahoo.glidetube.ui.components.settings.SettingsDestination
import io.github.shivytyahoo.glidetube.widget.core.GlideWidgetEntry
import io.github.shivytyahoo.glidetube.widget.core.GlideWidgets

internal object WidgetsIndex {
    fun entryFor(widget: GlideWidgetEntry) =
        SettingEntry(
            key = "widgets.${widget.id}",
            title = widget.label,
            summary = widget.description,
            section = R.string.settings_widgets_add_header,
            keywords = R.string.settings_keywords_widgets,
            destination = SettingsDestination.WIDGETS,
        )

    val all = GlideWidgets.catalog.map(::entryFor)
}
