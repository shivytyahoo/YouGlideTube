package io.github.shivytyahoo.glidetube.ui.components.layout.topbar

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideSearchField

/**
 * Search variant of [GlideTopBar]: back button, inline field, optional trailing actions.
 *
 * Used for in-place filtering of a screen's own content (Settings search, Subscriptions manage
 * mode), not for the Search tab. Global actions are never shown — the user is in a focused mode and
 * the leading affordance dismisses it rather than navigating back.
 */
@Composable
fun GlideSearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    autoFocus: Boolean = true,
    releaseFocusWithKeyboard: Boolean = false,
    windowInsets: WindowInsets = GlideTopBarDefaults.WindowInsets,
) {
    val focusRequester = remember { FocusRequester() }

    if (autoFocus) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }

    GlideTopBar(
        modifier = modifier,
        title = {
            GlideSearchField(
                query = query,
                onQueryChange = onQueryChange,
                placeholder = placeholder,
                modifier = Modifier.fillMaxWidth(),
                focusRequester = focusRequester,
                releaseFocusWithKeyboard = releaseFocusWithKeyboard,
            )
        },
        onBack = onClose,
        actions = actions,
        globalActions = GlideGlobalActionsMode.None,
        windowInsets = windowInsets,
    )
}
