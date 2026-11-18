package io.github.shivytyahoo.glidetube.ui.components.musicplayer.controls

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** The outer corner of a segmented action group. */
private val SegmentFullRadius = 21.dp

/** The inner corner of a segmented action group. */
private val SegmentEdgeRadius = 8.dp

/** The first button of a segmented action group: round on the outside, square inside. */
internal val PlayerSegmentLeadingShape: Shape =
    RoundedCornerShape(
        topStart = SegmentFullRadius,
        bottomStart = SegmentFullRadius,
        topEnd = SegmentEdgeRadius,
        bottomEnd = SegmentEdgeRadius,
    )

/** A middle button of a segmented action group: square on every corner. */
internal val PlayerSegmentInnerShape: Shape = RoundedCornerShape(SegmentEdgeRadius)

/** The last button of a segmented action group: square inside, round on the outside. */
internal val PlayerSegmentTrailingShape: Shape =
    RoundedCornerShape(
        topStart = SegmentEdgeRadius,
        bottomStart = SegmentEdgeRadius,
        topEnd = SegmentFullRadius,
        bottomEnd = SegmentFullRadius,
    )

/** One button of a segmented like / shuffle / repeat group, in the music player's action row style. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RowScope.PlayerToggleButton(
    checked: Boolean,
    checkedContainerColor: Color,
    checkedContentColor: Color,
    icon: ImageVector,
    contentDescription: String?,
    uncheckedShape: Shape,
    onClick: () -> Unit,
) {
    ToggleButton(
        checked = checked,
        onCheckedChange = { onClick() },
        modifier =
            Modifier
                .weight(1f)
                .fillMaxHeight(),
        colors =
            ToggleButtonDefaults.toggleButtonColors(
                containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                checkedContainerColor = checkedContainerColor,
                checkedContentColor = checkedContentColor,
            ),
        shapes =
            ToggleButtonShapes(
                shape = uncheckedShape,
                pressedShape = RoundedCornerShape(12.dp),
                checkedShape = RoundedCornerShape(SegmentFullRadius),
            ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
        )
    }
}
