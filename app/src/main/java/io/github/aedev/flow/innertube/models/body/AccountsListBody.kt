package io.github.aedev.flow.innertube.models.body

import io.github.aedev.flow.innertube.models.Context
import kotlinx.serialization.Serializable

@Serializable
data class AccountsListBody(
    val context: Context,
    val accountReadMask: AccountReadMask,
) {
    @Serializable
    data class AccountReadMask(
        val returnOwner: Boolean,
        val returnBrandAccounts: Boolean,
        val returnPersonaAccounts: Boolean,
    )
}
