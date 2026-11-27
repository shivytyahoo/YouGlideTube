package io.github.shivytyahoo.glidetube.ui.screens.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.data.update.AppRelease
import io.github.shivytyahoo.glidetube.data.update.parseReleaseNotes
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideDialogDefaults

/** The launch update prompt: the new version's changelog, with Update now, Skip this version and Later. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatePromptDialog(
    release: AppRelease,
    onUpdateNow: () -> Unit,
    onSkip: () -> Unit,
    onLater: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onLater) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(
                modifier =
                    Modifier.padding(
                        start = GlideDialogDefaults.ContentPadding,
                        top = GlideDialogDefaults.ContentPadding,
                        end = GlideDialogDefaults.ContentPadding,
                        bottom = GlideDialogDefaults.BottomPadding,
                    ),
            ) {
                Text(
                    text = stringResource(R.string.update_title, release.version),
                    style = MaterialTheme.typography.headlineSmall,
                    color = AlertDialogDefaults.titleContentColor,
                    modifier = Modifier.padding(bottom = GlideDialogDefaults.ActionsSpacing * 2),
                )
                Box(modifier = Modifier.weight(weight = 1f, fill = false).padding(bottom = GlideDialogDefaults.ActionsSpacing)) {
                    LazyColumn {
                        releaseNotes(parseReleaseNotes(release.notes))
                    }
                }
                Button(onClick = onUpdateNow, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.update_now))
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(onClick = onSkip) { Text(stringResource(R.string.update_skip_version)) }
                    TextButton(onClick = onLater) { Text(stringResource(R.string.update_later)) }
                }
            }
        }
    }
}
