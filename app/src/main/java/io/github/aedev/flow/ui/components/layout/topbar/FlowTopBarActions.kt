package io.github.aedev.flow.ui.components.layout.topbar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.PlayerPreferences

/**
 * The bell and Settings actions every root destination gets.
 *
 * Renders nothing when the shell has not provided [LocalFlowGlobalActions] — the case in previews
 * and in isolated Compose tests.
 */
@Composable
internal fun FlowGlobalActionsRow() {
    val actions = LocalFlowGlobalActions.current ?: return

    val context = LocalContext.current
    val preferences = remember(context) { PlayerPreferences(context.applicationContext) }
    val unreadCount by preferences.inboxUnreadCount.collectAsStateWithLifecycle(initialValue = 0)

    BadgedBox(
        badge = {
            if (unreadCount > 0) {
                Badge {
                    Text(
                        text =
                            if (unreadCount > 9) {
                                stringResource(R.string.notification_badge_9_plus)
                            } else {
                                unreadCount.toString()
                            },
                    )
                }
            }
        },
    ) {
        IconButton(onClick = actions.onOpenNotifications) {
            Icon(
                imageVector = Icons.Outlined.Notifications,
                contentDescription = stringResource(R.string.notifications),
            )
        }
    }
    IconButton(onClick = actions.onOpenSettings) {
        Icon(
            imageVector = Icons.Outlined.Settings,
            contentDescription = stringResource(R.string.settings),
        )
    }
}

data class FlowTopBarMenuItem(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val icon: ImageVector? = null,
)

@Composable
fun FlowTopBarOverflow(
    items: List<FlowTopBarMenuItem>,
    modifier: Modifier = Modifier,
    contentDescription: String = stringResource(R.string.more_options),
) {
    if (items.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = contentDescription,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    enabled = item.enabled,
                    leadingIcon =
                        item.icon?.let { icon ->
                            { Icon(imageVector = icon, contentDescription = null) }
                        },
                    onClick = {
                        expanded = false
                        item.onClick()
                    },
                )
            }
        }
    }
}
