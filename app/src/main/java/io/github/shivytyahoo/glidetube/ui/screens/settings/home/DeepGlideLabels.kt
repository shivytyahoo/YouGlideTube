package io.github.shivytyahoo.glidetube.ui.screens.settings.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.data.local.DEEP_GLIDE_NEVER_EXPIRES_HOURS

/** How long Deep Glide can stay on before it turns itself off. */
internal val DeepGlideDurations = listOf(DEEP_GLIDE_NEVER_EXPIRES_HOURS, 1, 2, 4, 6, 8, 12, 24)

@Composable
internal fun deepGlideDurationLabel(hours: Int): String =
    when (hours) {
        DEEP_GLIDE_NEVER_EXPIRES_HOURS -> stringResource(R.string.deep_glide_duration_never)
        1 -> stringResource(R.string.deep_glide_duration_1h)
        2 -> stringResource(R.string.deep_glide_duration_2h)
        4 -> stringResource(R.string.deep_glide_duration_4h)
        6 -> stringResource(R.string.deep_glide_duration_6h)
        8 -> stringResource(R.string.deep_glide_duration_8h)
        12 -> stringResource(R.string.deep_glide_duration_12h)
        24 -> stringResource(R.string.deep_glide_duration_24h)
        else -> pluralStringResource(R.plurals.deep_glide_duration_hours, hours, hours)
    }
