package io.github.shivytyahoo.glidetube.innertube.models.body

import io.github.shivytyahoo.glidetube.innertube.models.Context
import kotlinx.serialization.Serializable

@Serializable
data class AccountMenuBody(
    val context: Context,
    val deviceTheme: String = "DEVICE_THEME_SELECTED",
    val userInterfaceTheme: String = "USER_INTERFACE_THEME_DARK",
)
