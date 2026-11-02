package io.github.aedev.flow.data.account

import kotlinx.serialization.Serializable

/**
 * One signed-in account kept for switching: its session cookie, the dataSyncId captured with
 * it, and the display identity fetched at sign-in. Session data, not content data — it lives in
 * the same backup-excluded preferences file as the single cookie always did.
 */
@Serializable
data class SavedAccount(
    /** Stable identity key: channel handle when known, else email, else account name. */
    val key: String,
    val cookie: String,
    val dataSyncId: String? = null,
    /** Brand page id from accounts_list; empty means checked with no brand page, null means unchecked. */
    val pageIdToken: String? = null,
    val name: String? = null,
    val email: String? = null,
    val channelHandle: String? = null,
    val thumbnailUrl: String? = null,
) {
    val displayName: String get() = name ?: channelHandle ?: email ?: key
}
