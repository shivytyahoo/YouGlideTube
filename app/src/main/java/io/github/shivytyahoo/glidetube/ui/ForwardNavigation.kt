package io.github.shivytyahoo.glidetube.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The forward stack for the app shell, or null where no navigation shell exists. */
val LocalForwardNavigation = staticCompositionLocalOf<ForwardNavigation?> { null }

/**
 * A forward stack over the NavHost: Back pushes onto it, any new navigation clears it, and the
 * TopBar's forward button walks back down it.
 *
 * It watches the back stack rather than wrapping every navigation call, so pops from any path —
 * the TopBar, the system back gesture, a screen's own back handling — all land here. Routes are
 * stored as the full navigation string, rebuilt from the entry's arguments, so a forward step
 * re-opens the same destination with the same arguments.
 */
class ForwardNavigation(
    private val navController: NavHostController,
) {
    private val forwardStack = ArrayDeque<String>()
    private var lastStack: List<String?> = emptyList()
    private var navigatingForward = false

    private val _canGoForward = MutableStateFlow(false)

    /** Whether a forward step exists, for the TopBar button. */
    val canGoForward: StateFlow<Boolean> = _canGoForward.asStateFlow()

    /** Feed this every back-stack change; it decides whether entries were popped or replaced. */
    fun onBackStackChanged(stack: List<String?>) {
        if (navigatingForward) {
            navigatingForward = false
            lastStack = stack
            return
        }
        if (stack == lastStack) return
        if (stack.any { it == null }) {
            // A destination whose route cannot be rebuilt makes the whole snapshot untrustworthy.
            forwardStack.clear()
        } else if (stack.size < lastStack.size && lastStack.subList(0, stack.size) == stack) {
            // A pop: the entries that left, deepest first, so the nearest one comes back first.
            lastStack.subList(stack.size, lastStack.size).forEach { route ->
                route?.let { forwardStack.addLast(it) }
            }
        } else {
            // A new navigation, a tab switch, a replacement: forward history no longer applies.
            forwardStack.clear()
        }
        lastStack = stack
        _canGoForward.value = forwardStack.isNotEmpty()
    }

    /** Re-opens the most recently popped destination, if there is one. */
    fun goForward() {
        val route = forwardStack.removeLastOrNull() ?: return
        navigatingForward = true
        try {
            navController.navigate(route) { launchSingleTop = true }
        } catch (e: IllegalArgumentException) {
            // The destination left the graph since it was popped; the step is gone, not stuck.
            navigatingForward = false
            return
        }
        _canGoForward.value = forwardStack.isNotEmpty()
    }

    companion object {
        private val PLACEHOLDER = Regex("""\{(\w+)\}""")

        /**
         * The navigation string for [entry], or null when it cannot be rebuilt — a route with an
         * argument the entry does not carry, or a destination with no route at all. A query
         * argument that is absent is dropped from the string rather than invented.
         */
        fun reconstructRoute(entry: NavBackStackEntry): String? {
            val pattern = entry.destination.route ?: return null
            val path = pattern.substringBefore('?')
            val query = pattern.substringAfter('?', "")
            val resolvedPath = resolvePlaceholders(path, entry) ?: return null
            if (query.isEmpty()) return resolvedPath
            val resolvedQuery =
                query
                    .split('&')
                    .mapNotNull { pair ->
                        val match = PLACEHOLDER.find(pair) ?: return@mapNotNull pair
                        val name = match.groupValues[1]
                        val args = entry.arguments
                        val value = if (args != null && args.containsKey(name)) args.get(name)?.toString() else null
                        value?.let { pair.replace("{$name}", it) }
                    }.joinToString("&")
            return if (resolvedQuery.isEmpty()) resolvedPath else "$resolvedPath?$resolvedQuery"
        }

        private fun resolvePlaceholders(
            text: String,
            entry: NavBackStackEntry,
        ): String? {
            var resolved = text
            PLACEHOLDER.findAll(text).forEach { match ->
                val name = match.groupValues[1]
                val args = entry.arguments
                val value = if (args != null && args.containsKey(name)) args.get(name)?.toString() else null
                if (value == null) return null
                resolved = resolved.replace("{$name}", value)
            }
            return resolved
        }
    }
}
