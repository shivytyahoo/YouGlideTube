package io.github.shivytyahoo.glidetube.ui.components.equalizer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.data.audio.eq.EqBand
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideNavRow
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideSwitch

private val BadgeSize = 32.dp
private val ShapeIconSize = 22.dp
private val LeadingSpacing = 12.dp

/** One band as a settings-kit row: number, shape, type and frequency, gain and Q, and an on/off switch. */
@Composable
internal fun EqBandRow(
    index: Int,
    band: EqBand,
    shape: Shape,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val q = stringResource(R.string.eq_band_q, formatQ(band.q))
    GlideNavRow(
        title = stringResource(R.string.eq_band_line, stringResource(band.type.labelRes), formatFrequency(band.frequency)),
        supportingText = if (band.type.hasGain) stringResource(R.string.eq_band_line, formatGain(band.gain), q) else q,
        onClick = onClick,
        modifier = modifier,
        selected = selected,
        shape = shape,
        leadingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(LeadingSpacing), verticalAlignment = Alignment.CenterVertically) {
                EqBandBadge(number = index + 1, highlighted = selected)
                Icon(
                    painter = painterResource(band.type.iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(ShapeIconSize),
                )
            }
        },
        trailingContent = { GlideSwitch(checked = band.enabled, onCheckedChange = { onToggle() }) },
    )
}

@Composable
internal fun EqBandBadge(
    number: Int,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier =
            modifier
                .size(BadgeSize)
                .background(if (highlighted) colors.primary else colors.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = if (highlighted) colors.onPrimary else colors.onSecondaryContainer,
        )
    }
}
