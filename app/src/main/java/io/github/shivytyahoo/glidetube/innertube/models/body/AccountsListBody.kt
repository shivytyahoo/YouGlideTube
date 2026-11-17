package io.github.shivytyahoo.glidetube.innertube.models.body

import io.github.shivytyahoo.glidetube.innertube.models.Context
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
