package io.github.aedev.flow.ui.screens.settings.account

import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.settings.SettingsPage
import io.github.aedev.flow.ui.components.settings.info
import io.github.aedev.flow.ui.components.settings.nav
import io.github.aedev.flow.ui.components.settings.option
import io.github.aedev.flow.ui.components.settings.notice
import io.github.aedev.flow.ui.screens.settings.index.AccountIndex

/** The Google account page: sign-in state, and the door to the WebView sign-in flow. */
@Composable
internal fun AccountScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val activeAccount by viewModel.activeAccount.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val statusText =
        stringResource(if (signedIn) R.string.account_status_signed_in else R.string.account_status_signed_out)

    SettingsPage(
        title = stringResource(R.string.account_title),
        onBack = onBack,
        highlight = highlight,
    ) {
        group(key = "account.session", header = R.string.account_title) {
            info(
                key = "account.status",
                title = statusText,
                value = null,
                icon = Icons.Outlined.AccountCircle,
            )
            accounts.forEach { account ->
                option(
                    key = "account.entry.${account.key}",
                    label = account.displayName,
                    supportingText = account.channelHandle ?: account.email,
                    selected = account.key == activeAccount?.key,
                    onClick = { viewModel.switchAccount(account.key) },
                )
            }
            if (signedIn) {
                nav(
                    AccountIndex.addAccount,
                    onClick = { context.startActivity(Intent(context, GoogleLoginActivity::class.java)) },
                    icon = Icons.Outlined.PersonAdd,
                    showChevron = false,
                )
                nav(
                    AccountIndex.signOut,
                    onClick = { viewModel.signOut() },
                    icon = Icons.Outlined.Logout,
                    showChevron = false,
                )
            } else {
                nav(
                    AccountIndex.signIn,
                    onClick = { context.startActivity(Intent(context, GoogleLoginActivity::class.java)) },
                    icon = Icons.Outlined.Login,
                    showChevron = false,
                )
            }
        }
        notice(
            key = "account.body",
            text = {
                stringResource(if (signedIn) R.string.account_body_signed_in else R.string.account_body_signed_out)
            },
        )
    }
}
