package io.github.aedev.flow.ui.components.layout.navigation

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.theme.MorpheBrandBlue
import io.github.aedev.flow.ui.theme.morpheGlassFill
import kotlin.math.roundToInt

private const val MAX_BAR_TABS = 5
private val SlotHorizontalPadding = 12.dp
private val SlotVerticalPadding = 12.dp
private val PillHorizontalPadding = 12.dp
private val IndicatorVerticalPadding = 6.dp
private val IconLabelGap = 8.dp
private val IconSize = 24.dp

/**
 * The bottom navigation bar, built the way Morphe Manager builds its floating pill instead
 * of dressing up a Material ShortNavigationBar: the pill is only as wide as its items need,
 * centered on the screen, and the selected slot gets a solid brand-blue pill behind the
 * whole slot — icon and label together, sliding between slots.
 *
 * The Material bar's selected indicator wraps the icon only and leaves the label outside
 * it, which is what made the batch 4 tabs read as broken. Labels hide when the slots do
 * not fit the width, the way Morphe hides its pill labels, so the pill never overflows.
 *
 * Material allows at most five destinations in a bar, so a sixth and seventh enabled tab
 * move behind a "More" item; the rail has room for all of them.
 */
@Composable
internal fun FlowNavigationBar(
    tabs: List<FlowTab>,
    selectedTab: FlowTab?,
    onTabSelected: (FlowTab) -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
) {
    val barTabs = if (tabs.size <= MAX_BAR_TABS) tabs else tabs.take(MAX_BAR_TABS - 1)
    val overflowTabs = tabs.drop(barTabs.size)
    val selectedIndex =
        barTabs.indexOf(selectedTab).takeIf { it >= 0 }
            ?: if (selectedTab != null && selectedTab in overflowTabs) barTabs.size else -1

    // Morphe-style floating glass pill: the bar floats above the feed with side and bottom
    // margins, a fully rounded shape, and a backdrop-blurred container — the feed blurs
    // through it instead of merely showing through a translucent fill, which is what
    // batch 7 did and why it read as a dim bar rather than glass.
    // The caller's modifier (size reporting for content padding) stays on the outer box, so
    // the content still clears the whole pill.
    // The pill floats above the system navigation bar, not over it: on 3-button
    // navigation the system strip is tall, and a pill pinned to the screen's bottom
    // edge lands underneath it, so its tabs cannot be pressed. The inset goes
    // inside this measured box, so the height the chrome reports grows by it and
    // the content clearance (bar height + inset) stays exact.
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(
        modifier = modifier.padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 8.dp + navBarBottom),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val density = LocalDensity.current
            val textMeasurer = rememberTextMeasurer()
            val labelStyle = MaterialTheme.typography.labelMedium
            val labels = barTabs.map { stringResource(it.labelRes) } + if (overflowTabs.isNotEmpty()) listOf(stringResource(R.string.nav_more)) else emptyList()
            val showLabels = remember(labels, maxWidth) {
                val withLabels = labels.fold(PillHorizontalPadding * 2) { acc, label ->
                    val labelWidth = with(density) { textMeasurer.measure(AnnotatedString(label), labelStyle).size.width.toDp() }
                    acc + IconSize + IconLabelGap + labelWidth + SlotHorizontalPadding * 2
                }
                withLabels <= maxWidth
            }
            val slots = remember { mutableStateMapOf<Int, IntRange>() }
            val selectedSpan = if (selectedIndex >= 0) slots[selectedIndex] else null
            // A slot's positionInParent is relative to the Row's content, not the
            // pill box: the Row's own horizontal padding sits outside the Row
            // layout, so the indicator — a pill-box child — must add it back, or
            // it lands one padding to the left of the slot (batch 8: the icon sat
            // right-of-centre in an oval starting at the pill's edge).
            val rowPaddingPx = with(density) { PillHorizontalPadding.toPx().roundToInt() }

            // Fixed height, Morphe's 64 dp pill: a fillMaxHeight child inside a
            // wrap-content box would take the whole screen's height instead — that is
            // exactly the full-screen blue oval batch 6 shipped, with the slots pushed
            // to the top. With the height fixed, fillMaxHeight below is bounded.
            // Glass values start from Morphe's GlassButtonDefaults, thinned further:
            // the fill at 0.35 dark read too solid, so it sits at 0.22 dark / 0.45
            // light, with the outline border unchanged at 0.4 dark / 0.6 light.
            // Darkness comes from the resolved scheme's background luminance, so it
            // follows the app's own theme mode instead of the system setting.
            val scheme = MaterialTheme.colorScheme
            val isDark = scheme.background.luminance() < 0.5f
            val glass = scheme.surfaceVariant.copy(alpha = if (isDark) 0.22f else 0.45f)
            Box(
                modifier =
                    Modifier
                        .height(64.dp)
                        .morpheGlassFill(glass, CircleShape, hazeState)
                        .border(1.dp, scheme.outline.copy(alpha = if (isDark) 0.4f else 0.6f), CircleShape),
            ) {
                // The selected slot's blue pill, sliding between slots like Morphe's
                // selection indicator instead of jumping.
                if (selectedSpan != null) {
                    val left by animateIntAsState(selectedSpan.first, spring(stiffness = Spring.StiffnessMediumLow), label = "pill_indicator_left")
                    val width by animateIntAsState(selectedSpan.last + 1 - selectedSpan.first, spring(stiffness = Spring.StiffnessMediumLow), label = "pill_indicator_width")
                    Box(
                        modifier =
                            Modifier
                                .offset { IntOffset(left, 0) }
                                .width(with(density) { width.toDp() })
                                .fillMaxHeight()
                                .padding(vertical = IndicatorVerticalPadding)
                                .clip(CircleShape)
                                .background(MorpheBrandBlue),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxHeight().padding(horizontal = PillHorizontalPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    barTabs.forEachIndexed { index, tab ->
                        PillSlot(
                            selected = tab == selectedTab,
                            showLabel = showLabels,
                            onClick = { onTabSelected(tab) },
                            onPlaced = { left, width -> slots[index] = (left + rowPaddingPx) until (left + rowPaddingPx + width) },
                            icon = { Icon(imageVector = tab.icon(tab == selectedTab), contentDescription = null) },
                            label = stringResource(tab.labelRes),
                        )
                    }
                    if (overflowTabs.isNotEmpty()) {
                        OverflowSlot(
                            tabs = overflowTabs,
                            selectedTab = selectedTab,
                            showLabel = showLabels,
                            onTabSelected = onTabSelected,
                            onPlaced = { left, width -> slots[barTabs.size] = (left + rowPaddingPx) until (left + rowPaddingPx + width) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PillSlot(
    selected: Boolean,
    showLabel: Boolean,
    onClick: () -> Unit,
    onPlaced: (left: Int, width: Int) -> Unit,
    icon: @Composable () -> Unit,
    label: String,
    popup: @Composable (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier =
            Modifier
                .onPlaced { coordinates ->
                    val left = coordinates.positionInParent().x.roundToInt()
                    onPlaced(left, coordinates.size.width)
                }
                .clip(CircleShape)
                .clickable(role = Role.Tab, onClick = onClick)
                .semantics { this.selected = selected }
                .padding(horizontal = SlotHorizontalPadding, vertical = SlotVerticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.size(IconSize),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides if (selected) androidx.compose.ui.graphics.Color.White else scheme.onSurfaceVariant,
                ) {
                    icon()
                }
            }
            if (showLabel) {
                Spacer(modifier = Modifier.width(IconLabelGap))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) androidx.compose.ui.graphics.Color.White else scheme.onSurfaceVariant,
                )
            }
        }
    }
    popup?.invoke()
}

@Composable
private fun OverflowSlot(
    tabs: List<FlowTab>,
    selectedTab: FlowTab?,
    showLabel: Boolean,
    onTabSelected: (FlowTab) -> Unit,
    onPlaced: (left: Int, width: Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    PillSlot(
        selected = selectedTab in tabs,
        showLabel = showLabel,
        onClick = { expanded = true },
        onPlaced = onPlaced,
        icon = { Icon(imageVector = Icons.Filled.MoreHoriz, contentDescription = null) },
        label = stringResource(R.string.nav_more),
        popup = {
            DropdownMenuPopup(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuGroup(shapes = MenuDefaults.groupShape(index = 0, count = 1)) {
                    tabs.forEachIndexed { index, tab ->
                        DropdownMenuItem(
                            selected = tab == selectedTab,
                            onClick = {
                                expanded = false
                                onTabSelected(tab)
                            },
                            text = { Text(text = stringResource(tab.labelRes)) },
                            shapes = MenuDefaults.itemShape(index = index, count = tabs.size),
                            leadingIcon = {
                                Icon(
                                    imageVector = tab.icon(selected = false),
                                    contentDescription = null,
                                    modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                                )
                            },
                            selectedLeadingIcon = {
                                Icon(
                                    imageVector = tab.icon(selected = true),
                                    contentDescription = null,
                                    modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                                )
                            },
                        )
                    }
                }
            }
        },
    )
}
