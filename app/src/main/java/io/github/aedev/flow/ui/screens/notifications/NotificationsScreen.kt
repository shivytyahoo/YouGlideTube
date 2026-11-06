package io.github.aedev.flow.ui.screens.notifications

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.layout.topbar.FlowTopBar
import io.github.aedev.flow.ui.components.layout.topbar.FlowTopBarMenuItem
import io.github.aedev.flow.ui.components.layout.topbar.FlowTopBarOverflow
import io.github.aedev.flow.ui.components.shared.FlowAlertDialog
import io.github.aedev.flow.ui.components.shared.FlowEmptyState
import io.github.aedev.flow.ui.components.shared.FlowPullToRefreshBox
import kotlinx.coroutines.launch

private const val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000

@Composable
internal fun NotificationsScreen(
    onBack: () -> Unit,
    onVideoClick: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NotificationsViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val removedMessage = stringResource(R.string.notifications_removed)
    var showClearDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refreshIfStale() }

    Scaffold(
        modifier = modifier,
        topBar = {
            FlowTopBar(
                title = stringResource(R.string.notifications),
                onBack = onBack,
                actions = {
                    FlowTopBarOverflow(
                        items =
                            listOf(
                                FlowTopBarMenuItem(
                                    label = stringResource(R.string.clear_all_notifications),
                                    enabled = entries.isNotEmpty(),
                                    onClick = { showClearDialog = true },
                                ),
                                FlowTopBarMenuItem(
                                    label = stringResource(R.string.notifications_open_settings),
                                    onClick = onOpenSettings,
                                ),
                            ),
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent,
    ) { padding ->
        FlowPullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            if (entries.isEmpty()) {
                FlowEmptyState(
                    title = stringResource(R.string.peace_and_quiet),
                    subtitle = stringResource(R.string.notifications_empty_body),
                    icon = Icons.Outlined.Notifications,
                )
            } else {
                val now = System.currentTimeMillis()
                val unread = entries.filter { !it.read }
                val recent = entries.filter { it.read && it.receivedAt > now - SEVEN_DAYS_MS }
                val earlier = entries.filter { it.read && it.receivedAt <= now - SEVEN_DAYS_MS }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (unread.isNotEmpty()) {
                        item { NotificationGroupHeader(stringResource(R.string.notifications_group_new)) }
                        items(unread, key = { it.videoId }) { entry ->
                            NotificationRow(
                                entry = entry,
                                onClick = { viewModel.markRead(entry.videoId); onVideoClick(entry.videoId) },
                                onDismiss = {
                                    viewModel.dismiss(entry.videoId)
                                    scope.launch { snackbarHostState.showSnackbar(removedMessage) }
                                },
                            )
                        }
                    }
                    if (recent.isNotEmpty()) {
                        item { NotificationGroupHeader(stringResource(R.string.notifications_group_last_week)) }
                        items(recent, key = { it.videoId }) { entry ->
                            NotificationRow(
                                entry = entry,
                                onClick = { viewModel.markRead(entry.videoId); onVideoClick(entry.videoId) },
                                onDismiss = {
                                    viewModel.dismiss(entry.videoId)
                                    scope.launch { snackbarHostState.showSnackbar(removedMessage) }
                                },
                            )
                        }
                    }
                    if (earlier.isNotEmpty()) {
                        item { NotificationGroupHeader(stringResource(R.string.time_earlier)) }
                        items(earlier, key = { it.videoId }) { entry ->
                            NotificationRow(
                                entry = entry,
                                onClick = { viewModel.markRead(entry.videoId); onVideoClick(entry.videoId) },
                                onDismiss = {
                                    viewModel.dismiss(entry.videoId)
                                    scope.launch { snackbarHostState.showSnackbar(removedMessage) }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        FlowAlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.notifications_clear_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.notifications_clear_body,
                        entries.size,
                        entries.size,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAll()
                        showClearDialog = false
                    },
                ) {
                    Text(stringResource(R.string.notifications_clear_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun NotificationGroupHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
    )
}
