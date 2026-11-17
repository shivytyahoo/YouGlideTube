package io.github.shivytyahoo.glidetube.player

import android.content.Context
import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.data.local.DEEP_GLIDE_NEVER_EXPIRES_HOURS
import io.github.shivytyahoo.glidetube.data.local.PlayerPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

object DeepGlideManager {
    private enum class DisableReason {
        Manual,
        Timer
    }

    private data class DeepGlideSnapshot(
        val active: Boolean,
        val activatedAt: Long,
        val expireHours: Int
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var observerJob: Job? = null
    private var observedContext: Context? = null
    private var lastKnownActive: Boolean? = null
    private var pendingDisableReason: DisableReason? = null

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        if (observedContext === appContext && observerJob?.isActive == true) return

        observedContext = appContext
        observerJob?.cancel()

        val preferences = PlayerPreferences(appContext)
        observerJob = scope.launch {
            combine(
                preferences.deepGlideActive,
                preferences.deepGlideActivatedAt,
                preferences.deepGlideExpireHours
            ) { active, activatedAt, expireHours ->
                DeepGlideSnapshot(active, activatedAt, expireHours)
            }.collectLatest { snapshot ->
                val previousActive = lastKnownActive
                if (previousActive != null && previousActive != snapshot.active) {
                    pauseCurrentVideoIfNeeded()
                    _messages.emit(appContext.deepGlideMessage(snapshot.active, pendingDisableReason))
                    pendingDisableReason = null
                }
                lastKnownActive = snapshot.active

                if (!snapshot.active || snapshot.activatedAt == 0L || snapshot.expireHours == DEEP_GLIDE_NEVER_EXPIRES_HOURS) {
                    return@collectLatest
                }

                val expiresAt = snapshot.activatedAt + snapshot.expireHours * 3_600_000L
                val remainingMs = expiresAt - System.currentTimeMillis()
                if (remainingMs <= 0L) {
                    pendingDisableReason = DisableReason.Timer
                    preferences.setDeepGlideActive(false)
                    return@collectLatest
                }

                delay(remainingMs)
                pendingDisableReason = DisableReason.Timer
                preferences.setDeepGlideActive(false)
            }
        }
    }

    suspend fun toggle(context: Context): Boolean {
        val preferences = PlayerPreferences(context.applicationContext)
        val nextEnabled = !preferences.deepGlideActive.first()
        setEnabled(context, nextEnabled)
        return nextEnabled
    }

    suspend fun setEnabled(context: Context, enabled: Boolean): Boolean {
        val preferences = PlayerPreferences(context.applicationContext)
        val currentEnabled = preferences.deepGlideActive.first()
        if (currentEnabled == enabled) return enabled

        pendingDisableReason = if (enabled) null else DisableReason.Manual
        preferences.setDeepGlideActive(enabled)
        return enabled
    }

    private fun pauseCurrentVideoIfNeeded() {
        val playerManager = EnhancedPlayerManager.getInstance()
        val playerState = playerManager.playerState.value
        val hasActiveVideo = playerState.currentVideoId != null
        val shouldPause = hasActiveVideo && (playerState.isPlaying || playerState.isBuffering || playerState.playWhenReady)
        if (shouldPause) {
            playerManager.pause()
        }
    }

    private fun Context.deepGlideMessage(active: Boolean, disableReason: DisableReason?): String {
        return when {
            active -> getString(R.string.deep_glide_enabled_message)
            disableReason == DisableReason.Timer -> getString(R.string.deep_glide_expired_message)
            else -> getString(R.string.deep_glide_disabled_message)
        }
    }
}