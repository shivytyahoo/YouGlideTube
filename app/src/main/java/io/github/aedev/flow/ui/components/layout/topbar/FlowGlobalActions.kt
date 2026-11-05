package io.github.aedev.flow.ui.components.layout.topbar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * App-shell destinations that must stay reachable from every root screen. Root tabs 3
 * (Subscriptions) and 4 (Library) can never be hidden, so rendering these actions on every
 * backless destination guarantees reachability for any navigation configuration.
 */
@Immutable
data class FlowGlobalActions(
    val onOpenSettings: () -> Unit,
    val onOpenNotifications: () -> Unit,
)

/**
 * Static because the provided value is remembered for the lifetime of the shell and never changes;
 * reading it must never invalidate a screen.
 */
val LocalFlowGlobalActions = staticCompositionLocalOf<FlowGlobalActions?> { null }

/**
 * Publishes the shell's global actions to every [FlowTopBar] below it. Call once, around the
 * `NavHost`.
 */
@Composable
fun ProvideFlowGlobalActions(
    onOpenSettings: () -> Unit,
    onOpenNotifications: () -> Unit,
    content: @Composable () -> Unit,
) {
    val actions =
        remember(onOpenSettings, onOpenNotifications) {
            FlowGlobalActions(
                onOpenSettings = onOpenSettings,
                onOpenNotifications = onOpenNotifications,
            )
        }
    CompositionLocalProvider(LocalFlowGlobalActions provides actions, content = content)
}
