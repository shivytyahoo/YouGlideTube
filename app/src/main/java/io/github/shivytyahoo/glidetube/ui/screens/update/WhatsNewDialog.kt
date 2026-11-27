package io.github.shivytyahoo.glidetube.ui.screens.update

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideAlertDialog

private const val RELEASES_PAGE_URL = "https://github.com/shivytyahoo/YouGlideTube/releases"

/** The post-update "What's new" dialog: the new version's changelog, All releases and Close. */
@Composable
fun WhatsNewDialog(
    state: WhatsNewState,
    onClose: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    GlideAlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.whats_new_title, state.version)) },
        text = {
            LazyColumn {
                releaseNotes(state.notes)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } },
        dismissButton = {
            TextButton(
                onClick = {
                    uriHandler.openUri(RELEASES_PAGE_URL)
                    onClose()
                },
            ) { Text(stringResource(R.string.update_all_releases)) }
        },
    )
}
