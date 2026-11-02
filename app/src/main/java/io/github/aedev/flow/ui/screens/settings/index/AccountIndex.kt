package io.github.aedev.flow.ui.screens.settings.index

import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.settings.SettingEntry
import io.github.aedev.flow.ui.components.settings.SettingsDestination

internal object AccountIndex {
    private val page = SettingsDestination.ACCOUNT

    val signIn =
        SettingEntry(
            key = "account.sign_in",
            title = R.string.account_sign_in,
            summary = R.string.account_sign_in_subtitle,
            keywords = R.string.settings_keywords_account,
            destination = page,
        )

    val addAccount =
        SettingEntry(
            key = "account.add_account",
            title = R.string.account_add_account,
            summary = R.string.account_add_account_subtitle,
            keywords = R.string.settings_keywords_account,
            destination = page,
        )

    val signOut =
        SettingEntry(
            key = "account.sign_out",
            title = R.string.account_sign_out,
            summary = R.string.account_sign_out_subtitle,
            keywords = R.string.settings_keywords_account,
            destination = page,
        )

    val all = listOf(signIn, addAccount, signOut)
}
