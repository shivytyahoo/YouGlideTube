package io.github.shivytyahoo.glidetube

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import io.github.shivytyahoo.glidetube.data.account.AccountCookieStore
import io.github.shivytyahoo.glidetube.data.local.PersonProfiles
import io.github.shivytyahoo.glidetube.data.local.PlayerPreferences
import io.github.shivytyahoo.glidetube.data.repository.NewPipeDownloader
import io.github.shivytyahoo.glidetube.data.repository.YouTubeRepository
import io.github.shivytyahoo.glidetube.innertube.YouTube
import io.github.shivytyahoo.glidetube.network.AppProxyManager
import io.github.shivytyahoo.glidetube.network.VpnStateMonitor
import io.github.shivytyahoo.glidetube.notification.BackgroundCheckScheduler
import io.github.shivytyahoo.glidetube.notification.NotificationHelper
import io.github.shivytyahoo.glidetube.player.PlaybackShutdown
import io.github.shivytyahoo.glidetube.player.SleepTimerManager
import io.github.shivytyahoo.glidetube.player.stream.PlaybackPrefetcher
import io.github.shivytyahoo.glidetube.ui.screens.home.HomeFeedLocaleReset
import io.github.shivytyahoo.glidetube.utils.AppLanguageManager
import io.github.shivytyahoo.glidetube.utils.GlideCrashHandler
import io.github.shivytyahoo.glidetube.utils.PerformanceDispatcher
import io.github.shivytyahoo.glidetube.utils.StartupSentinel
import io.github.shivytyahoo.glidetube.utils.cipher.PipePipeNsigDecoder
import io.github.shivytyahoo.glidetube.utils.contentRegion
import io.github.shivytyahoo.glidetube.utils.contentYouTubeLocale
import io.github.shivytyahoo.glidetube.utils.newPipeContentCountry
import io.github.shivytyahoo.glidetube.utils.newPipeLocalization
import io.github.shivytyahoo.glidetube.utils.potoken.NewPipePoTokenProvider
import io.github.shivytyahoo.glidetube.utils.potoken.VisitorIdentityStore
import io.github.shivytyahoo.glidetube.utils.potoken.WebPoTokenSession
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
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import java.util.Locale
import javax.inject.Inject

@HiltAndroidApp
class GlideApplication :
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
    lateinit var youTubeRepository: YouTubeRepository

    @Inject
    lateinit var accountCookieStore: AccountCookieStore

    @Inject
    lateinit var accountSessionManager: io.github.shivytyahoo.glidetube.data.account.AccountSessionManager

    @Inject
    lateinit var oauthSessionManager: io.github.shivytyahoo.glidetube.data.account.OAuthSessionManager

    @Inject
    internal lateinit var homeFeedLocaleReset: HomeFeedLocaleReset

    // Created at startup so the guest history disk restore starts before any screen reads it.
    @Inject
    lateinit var sessionHistory: io.github.shivytyahoo.glidetube.data.local.SessionHistory

    // Same reason: the last account snapshot must be restored before History or Library reads it.
    @Inject
    lateinit var accountHistoryCapture: io.github.shivytyahoo.glidetube.data.local.AccountHistoryCapture

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader

    companion object {
        private const val TAG = "GlideApplication"
        lateinit var appContext: Context
            private set
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguageManager.wrapContext(base, AppLanguageManager.resolveLanguageTag(base)))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        // Before anything opens a settings store: finish or abandon a person switch that was
        // interrupted by a crash or a kill, so no launch starts on a half-swapped set of stores.
        PersonProfiles.recoverInterruptedSwap(this)
        super.onCreate()
        appContext = applicationContext
        purgeRemovedContentStores()
        YouTube.cacheDirectory = cacheDir.resolve("innertube_http_cache")

        val playerPreferences = PlayerPreferences(this)

        // Install crash handler for real-time monitoring
        GlideCrashHandler.install(this)

        // The card warm-up's details side goes through the repository's watch-response cache,
        // so a warmed description is the same request the player screen would have made.
        PlaybackPrefetcher.bindDetailsWarmer { videoId -> youTubeRepository.watchNextResponse(videoId) }

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
            io.github.shivytyahoo.glidetube.utils.cipher.CipherDeobfuscator
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
            BackgroundCheckScheduler.follow(this@GlideApplication, playerPreferences)
        }
        SleepTimerManager.attachExitCallback { PlaybackShutdown.closeApp(this) }

        // Safe mode skips the network warm-ups: a launch that died during startup tries again
        // without them, and the first clean launch turns them back on.
        val warmups = if (StartupSentinel.isSafeMode(this)) null else StartupWarmups(this)
        warmups?.startWithFirstActivity()
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
                    warmups?.onProxyApplied()
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
            warmups?.onIdentityRestored()
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
                // Account restore runs in its own coroutine and can backfill
                // before these tokens exist, so its identity fetch silently
                // misses — re-run it once the OAuth session is restored.
                accountSessionManager.backfillIdentityIfNeeded()
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
                contentYouTubeLocale(appLanguage, contentLanguage, region, AppLanguageManager.deviceLocale(this@GlideApplication))
            }.distinctUntilChanged().collectLatest { newLocale ->
                YouTube.locale = newLocale
                contentRegion = newLocale.gl
                NewPipe.setupLocalization(
                    newPipeLocalization(newLocale.hl),
                    ContentCountry(newLocale.gl),
                )
                Log.d(TAG, "Dynamic YouTube Locale updated: gl=${newLocale.gl}, hl=${newLocale.hl}")
                homeFeedLocaleReset.onContentLocale(newLocale)
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
        config: io.github.shivytyahoo.glidetube.network.AppProxyConfig,
        vpnActive: Boolean,
    ) {
        AppProxyManager.update(config, vpnActive)
        YouTube.proxy = AppProxyManager.currentProxy()
        YouTube.proxyAuth = AppProxyManager.currentHttpProxyAuthorizationHeader()
    }

    override fun onTerminate() {
        super.onTerminate()
        // Clean up performance dispatcher resources
        PerformanceDispatcher.shutdown()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        GlideCrashHandler.recordPhase("memory", "GlideApplication.onLowMemory")
        releaseVolatileMemory()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        GlideCrashHandler.recordPhase("memory", "GlideApplication.onTrimMemory level=$level")
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
private fun GlideApplication.purgeRemovedContentStores() {
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
