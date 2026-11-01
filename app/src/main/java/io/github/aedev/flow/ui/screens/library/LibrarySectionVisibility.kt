package io.github.aedev.flow.ui.screens.library

import io.github.aedev.flow.ui.components.library.LibrarySection

internal fun visibleContentSections(
    hidden: Set<LibrarySection>,
    separatePlaylistKinds: Boolean,
    shortsEnabled: Boolean,
): List<LibrarySection> =
    LibrarySection
        .content(separatePlaylistKinds)
        .filter { it !in hidden }

internal fun visibleSettingsSections(
    hidden: Set<LibrarySection>,
): List<LibrarySection> = LibrarySection.settingsAndData.filter { it !in hidden }
