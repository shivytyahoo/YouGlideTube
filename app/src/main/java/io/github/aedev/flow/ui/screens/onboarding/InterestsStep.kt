package io.github.aedev.flow.ui.screens.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.data.topics.TopicCatalog
import io.github.aedev.flow.data.topics.TopicCategory
import io.github.aedev.flow.ui.components.topicCategoryIcon
import io.github.aedev.flow.ui.components.topicCategoryNameRes
import kotlinx.coroutines.delay

private val SectionSpacing = 28.dp
private val TileSpacing = 10.dp
private val TileHeight = 56.dp

/**
 * The interests picker, YouGlideTube style: each category is a headed grid of chunky topic
 * tiles — tap to fill a tile with the accent and a check. Sections stagger in on the
 * first visit only; coming back to the step shows them at once, since [revealed]
 * survives in the screen.
 */
@Composable
internal fun InterestsStep(
    selectedTopics: Set<String>,
    onTopicToggle: (String) -> Unit,
    header: LazyListScope.() -> Unit,
    revealed: Boolean,
    onRevealed: () -> Unit,
    contentPadding: PaddingValues,
) {
    val categories = TopicCatalog.TOPIC_CATEGORIES
    var visibleSections by remember { mutableIntStateOf(if (revealed) categories.size else 0) }
    LaunchedEffect(Unit) {
        if (revealed) return@LaunchedEffect
        for (i in 1..categories.size) {
            delay(STAGGER_DELAY_MS)
            visibleSections = i
        }
        onRevealed()
    }

    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(SectionSpacing),
    ) {
        header()

        itemsIndexed(categories, key = { _, category -> category.name }) { index, category ->
            AnimatedVisibility(
                visible = index < visibleSections,
                enter = fadeIn(effects) + slideInVertically(spatial) { it / 6 },
                modifier = Modifier.animateItem(),
            ) {
                InterestCategorySection(
                    category = category,
                    selectedTopics = selectedTopics,
                    onTopicToggle = onTopicToggle,
                )
            }
        }
    }
}

@Composable
private fun InterestCategorySection(
    category: TopicCategory,
    selectedTopics: Set<String>,
    onTopicToggle: (String) -> Unit,
) {
    val selectedCount = category.topics.count(selectedTopics::contains)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = topicCategoryIcon(category.icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(topicCategoryNameRes(category.name)),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            if (selectedCount > 0) {
                Spacer(Modifier.size(6.dp))
                Text(
                    text = "· $selectedCount",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        category.topics.chunked(2).forEach { rowTopics ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(TileSpacing),
            ) {
                rowTopics.forEach { topic ->
                    TopicTile(
                        label = topic,
                        selected = selectedTopics.contains(topic),
                        onClick = { onTopicToggle(topic) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowTopics.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(TileSpacing))
        }
    }
}

@Composable
private fun TopicTile(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = container,
        modifier = modifier.height(TileHeight),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = content,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 6.dp)
                            .size(16.dp),
                )
            }
        }
    }
}
