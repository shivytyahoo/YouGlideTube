package io.github.aedev.flow

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.repository.NewPipeDownloader
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.discord.DiscordPresenceRuntime
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.network.AppProxyManager
import io.github.aedev.flow.network.VpnStateMonitor
import io.github.aedev.flow.notification.BackgroundCheckScheduler
import io.github.aedev.flow.notification.NotificationHelper
import io.github.aedev.flow.player.PlaybackShutdown
import io.github.aedev.flow.player.SleepTimerManager
import io.github.aedev.flow.ui.screens.home.HomeFeedLanguageReset
import io.github.aedev.flow.utils.AppLanguageManager
import io.github.aedev.flow.utils.FlowCrashHandler
import io.github.aedev.flow.utils.PerformanceDispatcher
import io.github.aedev.flow.utils.cipher.PipePipeNsigDecoder
import io.github.aedev.flow.utils.contentYouTubeLocale
import io.github.aedev.flow.utils.newPipeContentCountry
import io.github.aedev.flow.utils.newPipeLocalization
import io.github.aedev.flow.utils.potoken.NewPipePoTokenProvider
import io.github.aedev.flow.data.account.AccountCookieStore
import io.github.aedev.flow.utils.potoken.VisitorIdentityStore
import io.github.aedev.flow.utils.potoken.WebPoTokenSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.conscrypt.Conscrypt
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import java.security.Security
import java.util.Locale
import javax.inject.Inject

@HiltAndroidApp
class FlowApplication :
    Application(),
    SingletonImageLoader.Factory {
    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var vpnStateMonitor: VpnStateMonitor

    @Inject
    lateinit var visitorIdentityStore: VisitorIdentityStore

    @Inject
    lateinit var accountCookieStore: AccountCookieStore

    @Inject
    lateinit var accountSessionManager: io.github.aedev.flow.data.account.AccountSessionManager

    @Inject
    lateinit var oauthSessionManager: io.github.aedev.flow.data.account.OAuthSessionManager

    @Inject
    internal lateinit var homeFeedLanguageReset: HomeFeedLanguageReset

    // Created at startup so the guest history disk restore starts before any screen reads it.
    @Inject
    lateinit var sessionHistory: io.github.aedev.flow.data.local.SessionHistory

    // Same reason: the last account snapshot must be restored before History or Library reads it.
    @Inject
    lateinit var accountHistoryCapture: io.github.aedev.flow.data.local.AccountHistoryCapture

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader

    companion object {
        private const val TAG = "FlowApplication"
        lateinit var appContext: Context
            private set
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguageManager.wrapContext(base, AppLanguageManager.resolveLanguageTag(base)))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        purgeRemovedContentStores()
        YouTube.cacheDirectory = cacheDir.resolve("innertube_http_cache")

        DiscordPresenceRuntime.initialize(this, okHttpClient)

        val playerPreferences = PlayerPreferences(this)

        // Injects modern TLS/SSL certificates so OkHttp and Ktor don't crash
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.N_MR1) {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }

        // Install crash handler for real-time monitoring
        FlowCrashHandler.install(this)

        try {
            // Seeded from the device locale so the very first extraction is already localized; the
            // stored app-language/region preference takes over as soon as it loads below.
            val localization = newPipeLocalization(Locale.getDefault().toLanguageTag())
            NewPipe.init(
                NewPipeDownloader.getInstance(this),
                localization,
                newPipeContentCountry(Locale.getDefault().country),
            )
            YoutubeStreamExtractor.setPoTokenProvider(NewPipePoTokenProvider)
            Log.d(TAG, "NewPipe initialized with ${localization.localizationCode}")
        } catch (e: Exception) {
            // Log error but don't crash the app
            Log.e(TAG, "Failed to initialize NewPipe", e)
        }

        try {
            io.github.aedev.flow.utils.cipher.CipherDeobfuscator
                .initialize(this)
            Log.d(TAG, "CipherDeobfuscator initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CipherDeobfuscator", e)
        }

        PipePipeNsigDecoder.initialize(this)

        // Initialize notification channels
        NotificationHelper.createNotificationChannels(this)
        Log.d(TAG, "Notification channels created")

        /*
        try {
            // Initialize YoutubeDL
            com.yausername.youtubedl_android.YoutubeDL.getInstance().init(this)
            Log.d(TAG, "YoutubeDL initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize YoutubeDL", e)
        }
         */

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            BackgroundCheckScheduler.follow(this@FlowApplication, playerPreferences)
        }
        SleepTimerManager.attachExitCallback { PlaybackShutdown.closeApp(this) }

        val warmups = StartupWarmups(this)
        warmups.startWithFirstActivity()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            playerPreferences.proxyConfig
                .distinctUntilChanged()
                .flatMapLatest { config ->
                    if (config.watchesVpn()) {
                        vpnStateMonitor.vpnActive().map { vpnActive -> config to vpnActive }
                    } else {
                        flowOf(config to false)
                    }
                }.collectLatest { (proxyConfig, vpnActive) ->
                    applyProxyConfig(proxyConfig, vpnActive)
                    warmups.onProxyApplied()
                }
        }

        YouTube.onVisitorDataChanged = visitorIdentityStore::save
        WebPoTokenSession.bindIdentityStore(visitorIdentityStore)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                visitorIdentityStore.restore()?.let { restored ->
                    YouTube.visitorData = restored
                    Log.d(TAG, "visitorData restored")
                }
            } catch (e: Exception) {
                Log.w(TAG, "visitorData restore error: ${e.message}")
            }
            warmups.onIdentityRestored()
        }

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                accountSessionManager.restore()
                Log.d(TAG, "Account session restored")
            } catch (e: Exception) {
                Log.w(TAG, "Account session restore error: ${e.message}")
            }
        }

        oauthSessionManager.bind()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                oauthSessionManager.restore()
            } catch (e: Exception) {
                Log.w(TAG, "OAuth session restore error: ${e.message}")
            }
        }

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            AppLanguageManager.appliedLanguage.filterNotNull().collect { tag ->
                if (playerPreferences.appLanguage.first() != tag) playerPreferences.setAppLanguage(tag)
            }
        }

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            combine(
                AppLanguageManager.appliedLanguage.filterNotNull(),
                playerPreferences.contentLanguage,
                playerPreferences.trendingRegion,
            ) { appLanguage, contentLanguage, region ->
                contentYouTubeLocale(appLanguage, contentLanguage, region, AppLanguageManager.deviceLocale(this@FlowApplication))
            }.distinctUntilChanged().collectLatest { newLocale ->
                YouTube.locale = newLocale
                NewPipe.setupLocalization(
                    newPipeLocalization(newLocale.hl),
                    ContentCountry(newLocale.gl),
                )
                Log.d(TAG, "Dynamic YouTube Locale updated: gl=${newLocale.gl}, hl=${newLocale.hl}")
                homeFeedLanguageReset.onContentLanguage(newLocale.hl)
            }
        }

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var lastRegion: String? = null
            playerPreferences.trendingRegion.collectLatest { region ->
                if (lastRegion != null && lastRegion != region) {
                    Log.d(TAG, "Trending region changed from $lastRegion to $region. Invalidate visitor data.")
                    YouTube.visitorData = null

                    YouTube
                        .visitorData()
                        .onSuccess { data ->
                            if (!data.isNullOrEmpty()) {
                                YouTube.visitorData = data
                                Log.d(TAG, "Fresh visitorData fetched for region: $region")
                            }
                        }.onFailure { e ->
                            Log.w(TAG, "Failed to fetch fresh visitorData: ${e.message}")
                        }
                }
                lastRegion = region
            }
        }

   }

    private fun applyProxyConfig(
        config: io.github.aedev.flow.network.AppProxyConfig,
        vpnActive: Boolean,
    ) {
        AppProxyManager.update(config, vpnActive)
        YouTube.proxy = AppProxyManager.currentProxy()
        YouTube.proxyAuth = AppProxyManager.currentHttpProxyAuthorizationHeader()
    }

    override fun onTerminate() {
        DiscordPresenceRuntime.shutdown()
        super.onTerminate()
        // Clean up performance dispatcher resources
        PerformanceDispatcher.shutdown()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        FlowCrashHandler.recordPhase("memory", "FlowApplication.onLowMemory")
        releaseVolatileMemory()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        FlowCrashHandler.recordPhase("memory", "FlowApplication.onTrimMemory level=$level")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            releaseVolatileMemory()
        }
    }

    private fun releaseVolatileMemory() {
        if (::imageLoader.isInitialized) {
            imageLoader.memoryCache?.clear()
        }
        if (::okHttpClient.isInitialized) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                okHttpClient.connectionPool.evictAll()
            }
        }
    }
}

/**
 * One-time-per-launch cleanup of the on-disk stores the account-only build removed: the local
 * content DataStores, the recommendation engines' brain/ledger files and the recap/sync stores.
 * The downloads store, the download records and every settings/session store are kept — nothing
 * here touches them. Idempotent: deleting a missing file is a no-op.
 */
private fun FlowApplication.purgeRemovedContentStores() {
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        runCatching {
            val dataStoreDir = java.io.File(filesDir, "datastore")
            val doomedStores =
                listOf(
                    "subscriptions",
                    "liked_videos",
                    "playlists",
                    "search_history",
                    "view_history",
                    "video_queue",
                    "music_queue",
                    "favourite_artists",
                    "flow_recap",
                    "flow_recap_images",
                    "sync_brain_crdt",
                    "sync_identity",
                    "sync_music_brain_crdt",
                    "saved_playlist_sync",
                    "local_media",
                )
            doomedStores.forEach { name -> java.io.File(dataStoreDir, "$name.preferences_pb").delete() }
            val doomedFiles =
                listOf(
                    "flow_neuro_brain_v10.json",
                    "flow_neuro_brain_v9.json",
                    "flow_neuro_brain_v8.json",
                    "flow_neuro_brain_v7.json",
                    "flow_music_brain_v1.json",
                    "flow_music_stats_hot_v1.json",
                    "flow_music_stats_v1.json",
                    "flow_video_stats_hot_v1.json",
                    "flow_video_stats_v1.json",
                )
            doomedFiles.forEach { name -> java.io.File(dataStoreDir, name).delete() }
            java.io.File(filesDir, "user_neuro_brain.json").delete()
            java.io.File(filesDir, "neuro").deleteRecursively()
            java.io.File(filesDir, "flow_recap_images").deleteRecursively()
            noBackupFilesDir
                .listFiles { file -> file.name.startsWith("neuro_brain_before_v") }
                ?.forEach { it.delete() }
        }
    }
}
