package io.github.aedev.flow.ui.screens.notifications

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.notification.InboxNotification
import io.github.aedev.flow.ui.components.shared.MediaThumbnail
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun NotificationRow(
    entry: InboxNotification,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaThumbnail(
            videoId = entry.videoId,
            thumbnailUrl = entry.thumbnailUrl,
            width = 120.dp,
        )
        Column(
            modifier = Modifier.weight(1f).padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = entry.videoTitle,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.notifications_meta, entry.channelName, dayLabel(entry.receivedAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.dismiss),
            )
        }
    }
}

/** Today, yesterday, or the calendar date — the group header already carries the recency. */
@Composable
internal fun dayLabel(receivedAt: Long): String {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val date = Instant.ofEpochMilli(receivedAt).atZone(zone).toLocalDate()
    return when {
        date == today -> stringResource(R.string.time_today)
        date == today.minusDays(1) -> stringResource(R.string.time_yesterday)
        else -> DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).format(date)
    }
}
