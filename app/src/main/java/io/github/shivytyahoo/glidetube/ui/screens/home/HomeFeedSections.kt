package io.github.shivytyahoo.glidetube.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.data.model.Video
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideFilterChip
import io.github.shivytyahoo.glidetube.ui.components.shared.card.MediaVideoCard
import io.github.shivytyahoo.glidetube.ui.components.shared.card.VideoCardLayout
import java.time.LocalTime

/** The shelf's heading, from where its videos came from. */
@Composable
internal fun HomeShelf.title(): String =
    when (source) {
        FeedSource.RELATED -> {
            stringResource(R.string.home_shelf_more_like_watched)
        }

        FeedSource.CHANNEL_MEMORY, FeedSource.NEIGHBOURS -> {
            stringResource(R.string.home_shelf_channels_you_might_like)
        }

        FeedSource.DISCOVERY -> {
            tag?.let { stringResource(R.string.home_shelf_picked_for, it) }
                ?: stringResource(R.string.home_shelf_for_you)
        }

        FeedSource.SUBS -> {
            stringResource(R.string.home_shelf_new_from_subscriptions)
        }
    }

/** Good morning/afternoon/evening, and how long was watched today. */
@Composable
internal fun HomeGreeting(
    minutesToday: Int,
    modifier: Modifier = Modifier,
) {
    val hour = LocalTime.now().hour
    val hello =
        when (GuestTaste.greetingPart(hour)) {
            0 -> stringResource(R.string.home_greeting_morning)
            1 -> stringResource(R.string.home_greeting_afternoon)
            2 -> stringResource(R.string.home_greeting_evening)
            else -> stringResource(R.string.home_greeting_night)
        }
    val watched =
        if (minutesToday <= 0) {
            stringResource(R.string.home_greeting_new_for_you)
        } else {
            val amount =
                if (minutesToday < 60) {
                    stringResource(R.string.home_greeting_min, minutesToday)
                } else {
                    stringResource(R.string.home_greeting_hours_minutes, minutesToday / 60, minutesToday % 60)
                }
            stringResource(R.string.home_greeting_watched_today, amount)
        }
    Column(modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            text = hello,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
        )
        Text(
            text = watched,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The taste's strongest keywords, each a search. */
@Composable
internal fun HomeInterests(
    words: List<String>,
    onInterestClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = stringResource(R.string.home_interests),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(words, key = { it }) { word ->
                GlideFilterChip(
                    label = "#$word",
                    selected = false,
                    onClick = { onInterestClick(word) },
                )
            }
        }
    }
}

/** One source shelf of the guest feed, swiped across. */
@Composable
internal fun HomeShelfRow(
    shelf: HomeShelf,
    cardWidth: Dp,
    onVideoClick: (Video) -> Unit,
    onEnrichChannelMetadata: (Video) -> Unit,
    onFocusChanged: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = shelf.title(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 10.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(shelf.videos, key = { it.id }) { video ->
                LaunchedEffect(video.id, video.channelId, video.channelThumbnailUrl) {
                    onEnrichChannelMetadata(video)
                }
                MediaVideoCard(
                    video = video,
                    onClick = { onVideoClick(video) },
                    layout = VideoCardLayout.Stacked,
                    useInternalPadding = true,
                    modifier = Modifier.width(cardWidth),
                    onFocusChanged = { focused -> onFocusChanged(video.id, focused) },
                )
            }
        }
    }
}
