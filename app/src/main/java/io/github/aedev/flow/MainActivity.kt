package io.github.aedev.flow

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.github.aedev.flow.data.local.AppFontPreferences
import io.github.aedev.flow.data.local.AppUiModePreferences
import io.github.aedev.flow.data.local.LocalDataManager
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.repository.DeArrowRepository
import io.github.aedev.flow.data.repository.OriginalTitleRepository
import io.github.aedev.flow.discord.DiscordPresenceRuntime
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.notification.NotificationHelper
import io.github.aedev.flow.notification.collectFreshSubscriptionVideos
import io.github.aedev.flow.platform.AppIconController
import io.github.aedev.flow.platform.AppUiMode
import io.github.aedev.flow.platform.AppUiRoot
import io.github.aedev.flow.platform.DeviceFormFactorDetector
import io.github.aedev.flow.player.BackgroundPlaybackPolicy
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.player.LifecyclePlaybackPreferences
import io.github.aedev.flow.player.MemoryPressurePolicy
import io.github.aedev.flow.player.PictureInPictureHelper
import io.github.aedev.flow.player.PipQueueNavigation
import io.github.aedev.flow.ui.FlowApp
import io.github.aedev.flow.ui.IncomingLinks
import io.github.aedev.flow.ui.components.shared.ProvideDateDisplaySettings
import io.github.aedev.flow.ui.components.shared.card.ProvideVideoCardState
import io.github.aedev.flow.ui.linkTextOf
import io.github.aedev.flow.ui.musicCollectionRoute
import io.github.aedev.flow.ui.screens.crash.CrashReportScreen
import io.github.aedev.flow.ui.screens.update.UPDATE_ROUTE
import io.github.aedev.flow.ui.startup.FlowTheme
import io.github.aedev.flow.ui.startup.SplashController
import io.github.aedev.flow.ui.startup.ThemeSettings
import io.github.aedev.flow.ui.startup.splashTone
import io.github.aedev.flow.ui.startup.themeSettings
import io.github.aedev.flow.ui.theme.FlowFontFamily
import io.github.aedev.flow.ui.theme.FlowTheme
import io.github.aedev.flow.ui.theme.ThemeMode
import io.github.aedev.flow.ui.theme.ThemeVariant
import io.github.aedev.flow.ui.tv.FlowTvApp
import io.github.aedev.flow.ui.utils.ProvideWindowSizeClass
import io.github.aedev.flow.utils.AppLanguageManager
import io.github.aedev.flow.utils.FlowCrashHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val PORTRAIT_REEL_ASPECT_RATIO = 9f / 16f

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val links = IncomingLinks()

    @Inject
    lateinit var originalTitles: OriginalTitleRepository

    @Inject
    lateinit var deArrow: DeArrowRepository

    @Inject
    lateinit var lifecyclePlaybackPreferences: LifecyclePlaybackPreferences

    @Inject
    lateinit var appIconController: AppIconController

    @Inject
    lateinit var appFontPreferences: AppFontPreferences

    @Inject
    lateinit var videoPlayerManager: dagger.Lazy<EnhancedPlayerManager>

    @Inject
    lateinit var widgetContentSync: dagger.Lazy<io.github.aedev.flow.widget.core.refresh.WidgetContentSync>

    @Inject
    lateinit var nowPlayingWidgetPublisher: dagger.Lazy<io.github.aedev.flow.widget.nowplaying.NowPlayingWidgetPublisher>

    // A recreated activity gets its launch intent again; a playlist file in it was already imported.
    private var isRestoringState = false

    private val splashController = SplashController(this)

    private var pipDismissCheckJob: Job? = null
    private var pendingAutoPip = false
    private var cachedAppUiRoot = AppUiRoot.MOBILE

    private fun videoPlaybackStateName(state: Int?): String =
        when (state) {
            androidx.media3.common.Player.STATE_IDLE -> "IDLE"
            androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
            androidx.media3.common.Player.STATE_READY -> "READY"
            androidx.media3.common.Player.STATE_ENDED -> "ENDED"
            null -> "NO_PLAYER"
            else -> "UNKNOWN($state)"
        }

    private fun lifecyclePlaybackSnapshot(): String {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val playerManager =
            io.github.aedev.flow.player.EnhancedPlayerManager
                .getInstance()
        val playerState = playerManager.playerState.value
        val player = playerManager.getPlayer()
        return "interactive=${powerManager?.isInteractive} lifecycle=${lifecycle.currentState} " +
            "pip=$isInPictureInPictureMode pendingAutoPip=$pendingAutoPip " +
            "bgPref=${lifecyclePlaybackPreferences.settings.backgroundPlayEnabled} " +
            "shortsBgPref=${lifecyclePlaybackPreferences.settings.shortsBackgroundPlay} " +
            "explicitBg=${GlobalPlayerState.isExplicitBackgroundPlaybackActive.value} " +
            "video=${playerState.currentVideoId} exo=${videoPlaybackStateName(player?.playbackState)} " +
            "pwr=${player?.playWhenReady} playing=${player?.isPlaying} buffering=${playerState.isBuffering} " +
            "pos=${player?.currentPosition}/${player?.duration} idx=${player?.currentMediaItemIndex} count=${player?.mediaItemCount}"
    }

    private fun videoLifecycleLog(message: String) {
        Log.w("FlowVideoLifecycle", "$message | ${lifecyclePlaybackSnapshot()}")
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageManager.wrapContext(newBase, AppLanguageManager.resolveLanguageTag(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        splashController.install(installSplashScreen())

        super.onCreate(savedInstanceState)
        DiscordPresenceRuntime.attachActivity(this)

        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

        enableEdgeToEdge(
            statusBarStyle =
                SystemBarStyle.auto(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                ),
            navigationBarStyle =
                SystemBarStyle.auto(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        // Player setup reads DataStore and opens the media cache index, so it runs off the main
        // thread and settles after the first frame instead of blocking onCreate.
        lifecycleScope.launch { GlobalPlayerState.initializeAsync(applicationContext) }

        // Snapshot lives in the process, not the activity: onUserLeaveHint/onStop read it
        // synchronously and must still see it after a recreation they run inside of (#817).
        lifecyclePlaybackPreferences.observeIn(lifecycleScope)

        val dataManager = LocalDataManager(applicationContext)

        lifecycleScope.launch {
            io.github.aedev.flow.widget.core.FlowWidgets
                .observeThemeChanges(applicationContext)
        }
        lifecycleScope.launch(Dispatchers.Default) {
            widgetContentSync.get().startIfPlaced()
            nowPlayingWidgetPublisher.get().repairStalePlayback()
        }

        isRestoringState = savedInstanceState != null
        handleIntent(intent)
        isRestoringState = false

        // Read now, alongside the rest of startup, so the theme is usually known by the first composition.
        val storedTheme = MutableStateFlow<ThemeSettings?>(null)
        lifecycleScope.launch { dataManager.themeSettings(appFontPreferences).collect { storedTheme.value = it } }

        setContent {
            // Nothing is composed until the theme is known: the splash covers the wait, and the app
            // composes once in the right theme instead of twice.
            val theme = storedTheme.collectAsState().value ?: return@setContent

            val context = LocalContext.current
            val configuration = LocalConfiguration.current
            val uiPreferences = remember { AppUiModePreferences(applicationContext) }
            val appUiMode by uiPreferences.mode.collectAsState(initial = AppUiMode.AUTOMATIC)
            val deviceFormFactor =
                remember(configuration.uiMode, context) {
                    DeviceFormFactorDetector.detect(context)
                }
            val appUiRoot = appUiMode.resolve(deviceFormFactor)
            SideEffect { cachedAppUiRoot = appUiRoot }

            // The TV must not follow its own system theme: with the default SYSTEM mode
            // a light-system TV resolves the LIGHT variant, so the TV opens light while
            // the phone opens dark. SYSTEM on TV means the app's default dark theme
            // instead; an explicitly stored mode still wins.
            val rootTheme =
                if (appUiRoot == AppUiRoot.TV && theme.themeMode == ThemeMode.SYSTEM) {
                    theme.copy(themeMode = ThemeMode.DARK, themeVariant = ThemeVariant.DARK)
                } else {
                    theme
                }

            // Check for a crash that happened last session.
            // If found, show the CrashReportScreen instead of the normal UI. It is cleared as soon
            // as it is read, so leaving the screen any way shows it once, not on every launch.
            var pendingCrashLog by remember {
                mutableStateOf(
                    FlowCrashHandler.getLastCrash(applicationContext)?.also {
                        FlowCrashHandler.clearLastCrash(applicationContext)
                    },
                )
            }

            if (pendingCrashLog != null) {
                SideEffect { splashController.contentReady = true }
                FlowTheme(rootTheme.copy(fontFamily = FlowFontFamily)) {
                    CrashReportScreen(
                        report = pendingCrashLog!!,
                        onContinue = { pendingCrashLog = null },
                    )
                }
                return@setContent
            }

            FlowTheme(rootTheme) {
                val splashTone = splashTone(MaterialTheme.colorScheme.background)
                LaunchedEffect(splashTone) { splashController.rememberTheme(splashTone, appIconController.activeSuffix()) }

                // Date preferences: five DataStore flows used to be opened per video card,
                // metadata line, info section, description sheet and info dialog.
                ProvideWindowSizeClass {
                    ProvideDateDisplaySettings {
                        // Card preferences and watch progress are collected once here. Cards used to
                        // collect them individually, so a feed of ten opened ten Room observers and
                        // fifty DataStore collectors.
                        ProvideVideoCardState(originalTitles, deArrow) {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .semantics { testTagsAsResourceId = true },
                            ) {
                                // 1. MAIN APP (Home/NavHost)
                                // This loads *behind* the splash screen immediately.
                                // By the time splash fades, this is ready.
                                val deeplinkVideoId by links.videoId
                                val isDeeplinkShort by links.isShort
                                val isDeeplinkShared by links.isShared
                                val openMusicPlayerRequest by links.musicPlayerRequest
                                val pendingRoute by links.route

                                if (appUiRoot == AppUiRoot.TV) {
                                    SideEffect { splashController.contentReady = true }
                                    FlowTvApp(
                                        deeplinkVideoId = deeplinkVideoId,
                                        isShort = isDeeplinkShort,
                                        onDeeplinkConsumed = links::consumeVideo,
                                    )
                                } else {
                                    FlowApp(
                                        currentTheme = theme.themeMode,
                                        themeVariant = theme.themeVariant,
                                        systemLightThemeMode = theme.systemLightThemeMode,
                                        systemDarkThemeMode = theme.systemDarkThemeMode,
                                        deeplinkVideoId = deeplinkVideoId,
                                        isShort = isDeeplinkShort,
                                        isSharedLink = isDeeplinkShared,
                                        openMusicPlayerRequest = openMusicPlayerRequest,
                                        onDeeplinkConsumed = links::consumeVideo,
                                        pendingRoute = pendingRoute,
                                        onPendingRouteConsumed = links::consumeRoute,
                                        onStartDestinationKnown = { splashController.contentReady = true },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        DiscordPresenceRuntime.setAppForeground(true)
        // Opening the app checks for new subscription videos (throttled), so the bell badge
        // is live on open instead of only after a manual pull-to-refresh in the panel.
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val preferences = PlayerPreferences(applicationContext)
                if (!preferences.notificationsEnabled.first() || !preferences.notifNewVideosEnabled.first()) return@launch
                if (!YouTube.isSignedIn()) return@launch
                val last = preferences.lastForegroundSubscriptionCheckMs.first()
                if (System.currentTimeMillis() - last < FOREGROUND_CHECK_MIN_INTERVAL_MS) return@launch
                preferences.setLastForegroundSubscriptionCheckMs(System.currentTimeMillis())
                collectFreshSubscriptionVideos(applicationContext)
                // The inbox changed, so the tray summary (and the launcher badge) must follow.
                runCatching { NotificationHelper.syncSubscriptionSummary(applicationContext) }
            }
        }
    }

    override fun onDestroy() {
        videoLifecycleLog("onDestroy")
        DiscordPresenceRuntime.detachActivity(this)
        val playerManager =
            io.github.aedev.flow.player.EnhancedPlayerManager
                .getInstance()
        val playerState = playerManager.playerState.value
        val hasActiveVideo =
            playerState.currentVideoId != null &&
                (playerState.playWhenReady || playerState.isPlaying || playerState.isBuffering)
        val shouldKeepBackgroundPlayback =
            BackgroundPlaybackPolicy.shouldKeepPlaybackInBackground(
                backgroundPlaybackPreferenceEnabled = lifecyclePlaybackPreferences.settings.backgroundPlayEnabled,
                explicitBackgroundPlaybackActive = GlobalPlayerState.isExplicitBackgroundPlaybackActive.value,
                hasActiveVideo = hasActiveVideo,
            )

        if (shouldKeepBackgroundPlayback) {
            handOffVideoPlaybackToBackground()
        } else if (!isChangingConfigurations) {
            GlobalPlayerState.release()
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.getBooleanExtra(NotificationHelper.EXTRA_OPEN_UPDATE, false)) {
            intent.removeExtra(NotificationHelper.EXTRA_OPEN_UPDATE)
            links.openRoute(UPDATE_ROUTE)
            return
        }
        val notificationVideoId = intent.getStringExtra("notification_video_id") ?: intent.getStringExtra("video_id")

        val widgetRoute =
            intent.getStringExtra(
                io.github.aedev.flow.widget.core.action.WidgetDeepLink.EXTRA_WIDGET_ROUTE,
            )
        if (widgetRoute != null) {
            intent.removeExtra(io.github.aedev.flow.widget.core.action.WidgetDeepLink.EXTRA_WIDGET_ROUTE)
            links.openRoute(widgetRoute)
            return
        }

        val linkText = linkTextOf(intent)
        if (linkText != null) {
            val understood = links.openLink(linkText, isShared = intent.action == Intent.ACTION_SEND, isRestoring = isRestoringState)
            if (!understood && !isRestoringState) Toast.makeText(this, R.string.link_not_supported, Toast.LENGTH_LONG).show()
            return
        }

        if (intent.getBooleanExtra("open_music_player", false)) {
            links.openMusicPlayer()
            intent.removeExtra("notification_video_id")
            intent.removeExtra("video_id")
            return
        }

        if (intent.getBooleanExtra("open_video_player", false)) {
            intent.removeExtra("open_video_player")
            val currentVideoId = GlobalPlayerState.currentVideo.value?.id
            if (currentVideoId != null) links.openVideo(currentVideoId)
            return
        }

        val isShort = intent.getBooleanExtra("is_short", false) || intent.getBooleanExtra("is_shorts", false)
        notificationVideoId?.let {
            // Opening a video from its tray notification marks it read, so the bell drops with it.
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { PlayerPreferences(applicationContext).markInboxRead(it) }
                runCatching { NotificationHelper.syncSubscriptionSummary(applicationContext) }
            }
            links.openVideo(it, isShort = isShort)
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        videoLifecycleLog("onPictureInPictureModeChanged pip=$isInPictureInPictureMode")
        GlobalPlayerState.setPipMode(isInPictureInPictureMode)
        pendingAutoPip = false

        clearWindowBrightnessOverride()
        restoreVideoForPipWindow(isInPictureInPictureMode)

        // Exiting PiP restored nothing, so a player that went audio-only (or left a
        // surface restore pending) while in PiP stayed that way on fullscreen: the
        // picture hung while the session played on. restoreVideoOutput defers itself
        // when the surface is not ready yet, so this is safe to run on every exit.
        if (!isInPictureInPictureMode) {
            val playerManager = videoPlayerManager.get()
            if (playerManager.isInAudioOnlyMode() || playerManager.isVideoSurfaceRestorePending()) {
                playerManager.restoreVideoOutput()
            }
        }

        pipDismissCheckJob?.cancel()
        if (!isInPictureInPictureMode) {
            pipDismissCheckJob =
                lifecycleScope.launch {
                    delay(350L)
                    val stillBackgrounded = !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                    if (stillBackgrounded && !isInPictureInPictureMode) {
                        GlobalPlayerState.requestDismiss()
                        io.github.aedev.flow.player.EnhancedPlayerManager
                            .getInstance()
                            .stop()
                        io.github.aedev.flow.player.EnhancedPlayerManager
                            .getInstance()
                            .stopBackgroundService()
                        // onStop ran while the window was still in PiP, so it skipped this (#1152).
                        pauseShortsPlayers()
                    }
                }
        }
    }

    private fun pauseShortsPlayers() {
        io.github.aedev.flow.player.shorts.ShortsPlayerPool
            .getInstance()
            .pauseAll()
    }

    private fun restoreVideoForPipWindow(isInPictureInPictureMode: Boolean) {
        val playerManager = videoPlayerManager.get()
        if (
            MemoryPressurePolicy.shouldRestoreVideoOnPipEntry(
                isInPictureInPictureMode = isInPictureInPictureMode,
                isAudioOnly = playerManager.isInAudioOnlyMode(),
                isVideoRestorePending = playerManager.isVideoSurfaceRestorePending(),
                explicitBackgroundPlaybackActive = GlobalPlayerState.isExplicitBackgroundPlaybackActive.value,
            )
        ) {
            videoLifecycleLog("restoreVideoOutput for PiP window")
            playerManager.restoreVideoOutput()
        }
    }

    private fun releaseOrientationLock() {
        if (requestedOrientation != android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun clearWindowBrightnessOverride() {
        val layoutParams = window.attributes
        if (layoutParams.screenBrightness != android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
            layoutParams.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = layoutParams
        }
    }

    override fun onResume() {
        super.onResume()
        FlowCrashHandler.recordPhase("activity", "onResume pip=$isInPictureInPictureMode")
        videoLifecycleLog("onResume")
        // GlobalPlayerState outlives this Activity, so an instance destroyed straight out of PiP
        // without an onPictureInPictureModeChanged(false) would leave the flag latched true for
        // the rest of the process. Re-reading the real value here is the only reset path.
        GlobalPlayerState.setPipMode(isInPictureInPictureMode)
        pendingAutoPip = false
        pipDismissCheckJob?.cancel()
        PictureInPictureHelper.dismissPopup(this)
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            io.github.aedev.flow.player.PlayerHardwareController.fullscreenVideoActive.value
        ) {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                val direction =
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                        AudioManager.ADJUST_RAISE
                    } else {
                        AudioManager.ADJUST_LOWER
                    }
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    direction,
                    if (io.github.aedev.flow.player.PlayerHardwareController.inAppVolumeOverlayEnabled.value) {
                        0
                    } else {
                        AudioManager.FLAG_SHOW_UI
                    },
                )
                if (io.github.aedev.flow.player.PlayerHardwareController.inAppVolumeOverlayEnabled.value) {
                    io.github.aedev.flow.player.PlayerHardwareController
                        .notifyVolumeKey()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            io.github.aedev.flow.player.PlayerHardwareController.fullscreenVideoActive.value
        ) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onStop() {
        DiscordPresenceRuntime.setAppForeground(false)
        super.onStop()
        FlowCrashHandler.recordPhase(
            "activity",
            "onStop pip=$isInPictureInPictureMode " +
                "backgroundPlay=${lifecyclePlaybackPreferences.settings.backgroundPlayEnabled} " +
                "shortsBackground=${lifecyclePlaybackPreferences.settings.shortsBackgroundPlay}",
        )
        videoLifecycleLog("onStop")
        if (!isInPictureInPictureMode && !PictureInPictureHelper.isPopupActive) {
            if (cachedAppUiRoot == AppUiRoot.MOBILE) {
                releaseOrientationLock()
            }
            if (!lifecyclePlaybackPreferences.settings.shortsBackgroundPlay) {
                pauseShortsPlayers()
            }

            if (pendingAutoPip) {
                lifecycleScope.launch {
                    delay(800L)
                    if (
                        pendingAutoPip &&
                        !isInPictureInPictureMode &&
                        !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                    ) {
                        pendingAutoPip = false
                        handleBackgroundPlaybackOnStop()
                    }
                }
            } else {
                handleBackgroundPlaybackOnStop()
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (cachedAppUiRoot == AppUiRoot.TV) return
        val explicitBackgroundPlaybackActive =
            GlobalPlayerState.isExplicitBackgroundPlaybackActive.value
        FlowCrashHandler.recordPhase(
            "activity",
            "onUserLeaveHint autoPip=${lifecyclePlaybackPreferences.settings.autoPipEnabled} " +
                "explicitBackground=$explicitBackgroundPlaybackActive",
        )
        videoLifecycleLog("onUserLeaveHint")
        // Only enter PiP mode if video is playing and has progressed
        // We use the EnhancedPlayerManager directly to get the immediate state
        val playerManager =
            io.github.aedev.flow.player.EnhancedPlayerManager
                .getInstance()
        val musicManager = io.github.aedev.flow.player.EnhancedMusicPlayerManager

        val isVideoPlaying =
            playerManager.playerState.value.isPlaying &&
                playerManager.playerState.value.currentVideoId != null &&
                playerManager.getCurrentPosition() > 500 // At least 0.5s in
        val isMusicPlaying = musicManager.playerState.value.isPlaying

        // Only enter PiP for video, not for music (which uses background service)
        val shouldEnterAutoPip =
            BackgroundPlaybackPolicy.shouldEnterAutoPip(
                autoPipEnabled = lifecyclePlaybackPreferences.settings.autoPipEnabled,
                isVideoPlaying = isVideoPlaying,
                explicitBackgroundPlaybackActive = explicitBackgroundPlaybackActive,
            )
        if (shouldEnterAutoPip && !isMusicPlaying) {
            enterPlayerPictureInPictureMode(
                aspectRatio = PictureInPictureHelper.currentVideoAspectRatio,
                isPlaying = true,
            )
            return
        }

        if (!lifecyclePlaybackPreferences.settings.shortsPipEnabled || isMusicPlaying) return
        val shortsPool =
            io.github.aedev.flow.player.shorts.ShortsPlayerPool
                .getInstance()
        if (!shortsPool.isPlaying()) return
        enterPlayerPictureInPictureMode(
            aspectRatio = shortsPool.activeVideoAspectRatio() ?: PORTRAIT_REEL_ASPECT_RATIO,
            isPlaying = true,
            navigation = PictureInPictureHelper.shortsNavigation,
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        FlowCrashHandler.recordPhase("memory", "MainActivity.onTrimMemory level=$level")
        videoPlayerManager.get().handleMemoryPressure(
            trimLevel = level,
            videoVisible = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || isInPictureInPictureMode,
        )
    }

    fun enterPlayerPictureInPictureMode(
        aspectRatio: Float = PictureInPictureHelper.currentVideoAspectRatio,
        isPlaying: Boolean = true,
        openSettingsOnDenied: Boolean = false,
        navigation: PipQueueNavigation? = null,
    ): Boolean {
        if (cachedAppUiRoot == AppUiRoot.TV) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (!PictureInPictureHelper.isPipAllowed(this)) {
            if (openSettingsOnDenied) {
                PictureInPictureHelper.openPipSettings(this)
            }
            return false
        }

        pendingAutoPip = true
        val entered =
            PictureInPictureHelper.enterPipMode(
                activity = this,
                aspectRatio = aspectRatio,
                isPlaying = isPlaying,
                autoEnterEnabled = false,
                navigation = navigation,
            )
        if (!entered) {
            pendingAutoPip = false
        }
        return entered
    }

    private fun handOffVideoPlaybackToBackground() {
        FlowCrashHandler.recordPhase("background-handoff", "handOffVideoPlaybackToBackground")
        videoLifecycleLog("handOffVideoPlaybackToBackground")
        val playerManager =
            io.github.aedev.flow.player.EnhancedPlayerManager
                .getInstance()
        val playerState = playerManager.playerState.value
        if (
            playerState.currentVideoId != null &&
            (playerState.playWhenReady || playerState.isPlaying || playerState.isBuffering)
        ) {
            val video = GlobalPlayerState.currentVideo.value
            playerManager.startBackgroundService(
                videoId = video?.id ?: playerState.currentVideoId,
                title = video?.title?.ifEmpty { "Playing..." } ?: "Playing...",
                channel = video?.channelName ?: "",
                thumbnail = video?.thumbnailUrl ?: "",
            )
            playerManager.continueVideoPlaybackInBackground()
        }
    }

    private fun handleBackgroundPlaybackOnStop() {
        FlowCrashHandler.recordPhase("background-handoff", "handleBackgroundPlaybackOnStop")
        videoLifecycleLog("handleBackgroundPlaybackOnStop")
        val playerManager =
            io.github.aedev.flow.player.EnhancedPlayerManager
                .getInstance()
        val playerState = playerManager.playerState.value
        val hasActiveVideo =
            playerState.currentVideoId != null &&
                (playerState.playWhenReady || playerState.isPlaying || playerState.isBuffering)

        if (!hasActiveVideo) return

        val shouldKeepBackgroundPlayback =
            BackgroundPlaybackPolicy.shouldKeepPlaybackInBackground(
                backgroundPlaybackPreferenceEnabled = lifecyclePlaybackPreferences.settings.backgroundPlayEnabled,
                explicitBackgroundPlaybackActive = GlobalPlayerState.isExplicitBackgroundPlaybackActive.value,
                hasActiveVideo = hasActiveVideo,
            )

        if (shouldKeepBackgroundPlayback) {
            videoLifecycleLog("handleBackgroundPlaybackOnStop handoff")
            handOffVideoPlaybackToBackground()
        } else {
            videoLifecycleLog("handleBackgroundPlaybackOnStop pause")
            playerManager.pause()
            playerManager.parkVideoForBackground()
            playerManager.stopBackgroundService()
        }
    }

    companion object {
        const val EXTRA_BENCHMARK_BYPASS_ONBOARDING = "io.github.aedev.flow.extra.BENCHMARK_BYPASS_ONBOARDING"

        /** Opening the app checks for new subscription videos at most this often. */
        private const val FOREGROUND_CHECK_MIN_INTERVAL_MS = 30L * 60 * 1000
    }
}
