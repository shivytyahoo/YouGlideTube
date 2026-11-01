package io.github.aedev.flow.ui.screens.onboarding

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.shared.FlowActionButton
import io.github.aedev.flow.ui.screens.settings.account.GoogleLoginActivity
import io.github.aedev.flow.ui.screens.settings.account.TvCodeLoginDialog

private val CardPadding = 20.dp
private val SectionSpacing = 16.dp

/**
 * The sign-in step: Google sign-in (the app's WebView flow) or a TV code for
 * big-screen / second-device sign-in. Skippable from the top bar, like YouTube's.
 */
@Composable
internal fun SignInStep(
    state: OnboardingUiState,
    header: LazyListScope.() -> Unit,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    var showTvCode by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(SectionSpacing),
    ) {
        header()
        if (state.signedIn) {
            item(key = "signed-in") {
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(CardPadding),
                        horizontalArrangement = Arrangement.spacedBy(SectionSpacing),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                        )
                        Text(
                            text = stringResource(R.string.onboarding_signin_done_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
            item(key = "signed-in-note") {
                Text(
                    text = stringResource(R.string.onboarding_signin_done_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            item(key = "google") {
                FlowActionButton(
                    text = stringResource(R.string.onboarding_signin_google),
                    onClick = {
                        context.startActivity(Intent(context, GoogleLoginActivity::class.java))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "tv-code") {
                OutlinedButton(
                    onClick = { showTvCode = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.onboarding_signin_tv_code))
                }
            }
        }
    }

    if (showTvCode) {
        TvCodeLoginDialog(onDismiss = { showTvCode = false })
    }
}
