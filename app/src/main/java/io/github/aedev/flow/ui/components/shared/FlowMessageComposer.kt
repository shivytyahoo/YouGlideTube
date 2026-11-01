package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * A single-line message composer: text field plus send button. The send callback reports back
 * through [onPost]; a missing reply can never wedge the row — a 12s watchdog clears the
 * sending state and shows the failure text instead.
 */
@Composable
fun FlowMessageComposer(
    placeholderRes: Int,
    failureRes: Int,
    sendDescriptionRes: Int,
    onPost: (String, (Boolean) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }
    var posting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    if (posting) {
        LaunchedEffect(Unit) {
            delay(12_000)
            if (posting) {
                posting = false
                failed = true
            }
        }
    }

    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    failed = false
                },
                placeholder = { Text(stringResource(placeholderRes)) },
                enabled = !posting,
                maxLines = 4,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                enabled = text.isNotBlank() && !posting,
                onClick = {
                    posting = true
                    onPost(text.trim()) { posted ->
                        posting = false
                        if (posted) {
                            text = ""
                        } else {
                            failed = true
                        }
                    }
                },
            ) {
                Icon(
                    Icons.Rounded.Send,
                    contentDescription = stringResource(sendDescriptionRes),
                )
            }
        }
        if (failed) {
            Text(
                text = stringResource(failureRes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
