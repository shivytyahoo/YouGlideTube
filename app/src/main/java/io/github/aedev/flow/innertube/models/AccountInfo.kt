package io.github.aedev.flow.innertube.models

data class AccountInfo(
    val name: String,
    val email: String?,
    val channelHandle: String?,
    val thumbnailUrl: String?,
)

/** One entry of the TV identity endpoint's list: the display identity plus, for brand channels, the page id token. */
data class OAuthIdentity(
    val name: String?,
    val email: String?,
    val channelHandle: String?,
    val pageIdToken: String?,
)
