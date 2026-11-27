package io.github.shivytyahoo.glidetube.ui.components.layout.topbar

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.ui.LocalForwardNavigation
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Which screens show the app-shell actions ([GlideGlobalActions]).
 *
 * [Auto] is the default and resolves from the bar's own shape: a bar with no back affordance is a
 * root destination, and every root destination must be able to reach Notifications and Settings.
 * A new root tab therefore cannot forget them — it would have to opt out with [None].
 */
enum class GlideGlobalActionsMode {
    Auto,
    Always,
    None,
}

/**
 * The single top bar for every non-player screen.
 *
 * Pass [onBack] for a detail screen; leave it null for a root tab. See [GlideTopBarDefaults] for why
 * the colours and window insets differ from the Material defaults.
 *
 * @param leading replaces the default back button — used by Home for its logo + wordmark. A bar
 *   with a [leading] slot is still treated as a root destination.
 * @param actions screen-specific actions. Keep this to two on a root destination; put the rest in
 *   [GlideTopBarOverflow] so the title keeps its width.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlideTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    globalActions: GlideGlobalActionsMode = GlideGlobalActionsMode.Auto,
    windowInsets: WindowInsets = GlideTopBarDefaults.WindowInsets,
) {
    GlideTopBar(
        modifier = modifier,
        title = {
            GlideTopBarTitle(title = title, subtitle = subtitle)
        },
        onBack = onBack,
        leading = leading,
        actions = actions,
        globalActions = globalActions,
        windowInsets = windowInsets,
    )
}

/**
 * Slot-based overload for bars whose title is not plain text — a branded wordmark, a tab row, or an
 * inline field. Prefer the string overload; this exists so such screens still share the container,
 * colours, insets and global-action behaviour instead of hand-rolling a bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlideTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    globalActions: GlideGlobalActionsMode = GlideGlobalActionsMode.Auto,
    windowInsets: WindowInsets = GlideTopBarDefaults.WindowInsets,
) {
    val showGlobalActions =
        when (globalActions) {
            GlideGlobalActionsMode.Always -> true
            GlideGlobalActionsMode.None -> false
            GlideGlobalActionsMode.Auto -> onBack == null
        }

    val forwardNavigation = LocalForwardNavigation.current
    val canGoForward by remember(forwardNavigation) {
        forwardNavigation?.canGoForward ?: MutableStateFlow(false)
    }.collectAsStateWithLifecycle()
    val forwardButton: @Composable () -> Unit = {
        IconButton(onClick = { forwardNavigation?.goForward() }) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = stringResource(R.string.btn_forward),
            )
        }
    }

    TopAppBar(
        modifier = modifier,
        title = title,
        navigationIcon = {
            when {
                leading != null -> {
                    leading()
                }

                onBack != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.btn_back),
                            )
                        }
                        if (canGoForward) forwardButton()
                    }
                }

                canGoForward -> {
                    forwardButton()
                }
            }
        },
        actions = {
            actions()
            if (showGlobalActions) {
                GlideGlobalActionsRow()
            }
        },
        colors = GlideTopBarDefaults.colors(),
        windowInsets = windowInsets,
    )
}

@Composable
private fun GlideTopBarTitle(
    title: String,
    subtitle: String?,
) {
    if (subtitle == null) {
        Text(
            text = title,
            style = GlideTopBarDefaults.titleStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    } else {
        Column {
            Text(
                text = title,
                style = GlideTopBarDefaults.titleStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = GlideTopBarDefaults.subtitleStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
