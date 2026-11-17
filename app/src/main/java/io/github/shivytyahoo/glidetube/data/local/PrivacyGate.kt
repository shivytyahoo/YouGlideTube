package io.github.shivytyahoo.glidetube.data.local

import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What Deep Glide allows to be recorded. Writers ask here instead of reading
 * the preferences themselves, so every one of them follows the same rules.
 */
@Singleton
class PrivacyGate internal constructor(
    private val deepGlideActive: suspend () -> Boolean,
    private val scrobbleDuringDeepGlide: suspend () -> Boolean,
) {
    @Inject
    constructor(preferences: PlayerPreferences) : this(
        deepGlideActive = preferences::isDeepGlideCurrentlyActive,
        scrobbleDuringDeepGlide = { preferences.deepGlideScrobble.first() },
    )

    /** Deep Glide is on and its timer has not run out. */
    suspend fun isDeepGlideActive(): Boolean = deepGlideActive()

    /** Listens go to scrobbling services unless Deep Glide is on and the user kept them private. */
    suspend fun allowsScrobbling(): Boolean = !deepGlideActive() || scrobbleDuringDeepGlide()
}
