package io.github.shivytyahoo.glidetube.ui.screens.settings.appearance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.ui.components.settings.SettingsPage
import io.github.shivytyahoo.glidetube.ui.components.settings.option
import io.github.shivytyahoo.glidetube.ui.components.settings.toggleGroup
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideToggleOption
import io.github.shivytyahoo.glidetube.ui.screens.settings.index.DateTimeIndex
import io.github.shivytyahoo.glidetube.utils.DateContext
import io.github.shivytyahoo.glidetube.utils.DateContextMode
import io.github.shivytyahoo.glidetube.utils.DateDisplayMode
import io.github.shivytyahoo.glidetube.utils.DateDisplaySettings
import io.github.shivytyahoo.glidetube.utils.DateFormatStyle
import io.github.shivytyahoo.glidetube.utils.formatExactDate
import java.time.Duration
import java.time.Instant

private const val SAMPLE_AGE_DAYS = 215L
private val PreviewPadding = 16.dp
private val PreviewSpacing = 4.dp

/** How upload dates read across YouGlideTube, with a live preview of each place they appear. */
@Composable
internal fun DateTimeScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    viewModel: DateTimeViewModel = hiltViewModel(),
) {
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val formatStyle by viewModel.formatStyle.collectAsStateWithLifecycle()
    val listsMode by viewModel.listsMode.collectAsStateWithLifecycle()
    val watchMode by viewModel.watchMode.collectAsStateWithLifecycle()
    val descriptionMode by viewModel.descriptionMode.collectAsStateWithLifecycle()

    val sampleTimestamp = remember { Instant.now().minus(Duration.ofDays(SAMPLE_AGE_DAYS)).toEpochMilli() }
    val sampleRelative = stringResource(R.string.datetime_sample_relative)
    val settings = DateDisplaySettings(mode, formatStyle, listsMode, watchMode, descriptionMode)
    val previews =
        listOf(
            stringResource(R.string.datetime_context_lists) to
                settings.format(sampleRelative, DateContext.LISTS, sampleTimestamp, timestampIsExact = true),
            stringResource(R.string.datetime_context_watch) to
                settings.format(sampleRelative, DateContext.WATCH, sampleTimestamp, timestampIsExact = true),
            stringResource(R.string.datetime_context_description) to
                settings.format(sampleRelative, DateContext.DESCRIPTION, sampleTimestamp, timestampIsExact = true),
        )
    val modeLabels =
        listOf(
            DateDisplayMode.RELATIVE to stringResource(R.string.datetime_mode_relative),
            DateDisplayMode.EXACT to stringResource(R.string.datetime_mode_exact),
            DateDisplayMode.BOTH to stringResource(R.string.datetime_mode_both),
        )
    val formatOptions =
        DateFormatStyle.entries.map { style ->
            val sample = formatExactDate(sampleTimestamp, style)
            Triple(
                style,
                if (style == DateFormatStyle.SYSTEM) stringResource(R.string.datetime_format_system) else sample,
                when (style) {
                    DateFormatStyle.SYSTEM -> sample
                    DateFormatStyle.ISO -> stringResource(R.string.datetime_format_iso_hint)
                    else -> null
                },
            )
        }
    val overrideOptions =
        listOf(
            GlideToggleOption(DateContextMode.DEFAULT, stringResource(R.string.datetime_override_default)),
            GlideToggleOption(DateContextMode.RELATIVE, stringResource(R.string.datetime_override_relative)),
            GlideToggleOption(DateContextMode.EXACT, stringResource(R.string.datetime_override_exact)),
            GlideToggleOption(DateContextMode.BOTH, stringResource(R.string.datetime_override_both)),
        )

    SettingsPage(
        title = stringResource(R.string.datetime_title),
        onBack = onBack,
        highlight = highlight,
    ) {
        item("date_time.preview") { DatePreviewCard(previews) }
        group(key = DateTimeIndex.mode.key, header = R.string.datetime_mode_header) {
            modeLabels.forEach { (value, label) ->
                option("date_time.mode.${value.name}", label, selected = mode == value, onClick = { viewModel.setMode(value) })
            }
        }
        group(key = DateTimeIndex.format.key, header = R.string.datetime_format_header) {
            formatOptions.forEach { (style, label, supporting) ->
                option(
                    key = "date_time.format.${style.name}",
                    label = label,
                    supportingText = supporting,
                    selected = formatStyle == style,
                    onClick = { viewModel.setFormatStyle(style) },
                )
            }
        }
        group(
            key = "date_time.overrides",
            header = R.string.datetime_overrides_header,
            footer = R.string.datetime_overrides_note,
        ) {
            toggleGroup(DateTimeIndex.listsOverride, overrideOptions, listsMode, viewModel::setListsMode)
            toggleGroup(DateTimeIndex.watchOverride, overrideOptions, watchMode, viewModel::setWatchMode)
            toggleGroup(DateTimeIndex.descriptionOverride, overrideOptions, descriptionMode, viewModel::setDescriptionMode)
        }
    }
}

@Composable
private fun DatePreviewCard(previews: List<Pair<String, String>>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(PreviewPadding), verticalArrangement = Arrangement.spacedBy(PreviewSpacing)) {
            Text(
                text = stringResource(R.string.datetime_preview_header),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            previews.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}
