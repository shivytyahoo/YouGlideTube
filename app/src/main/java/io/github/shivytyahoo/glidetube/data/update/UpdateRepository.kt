package io.github.shivytyahoo.glidetube.data.update

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.shivytyahoo.glidetube.BuildConfig
import io.github.shivytyahoo.glidetube.data.local.LocalDataManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "UpdateRepository"
private const val STABLE_RELEASE_API = "https://api.github.com/repos/shivytyahoo/YouGlideTube/releases/latest"
private const val GITHUB_JSON = "application/vnd.github+json"

/**
 * Where an automatic check may surface a release. A notification announces a version once;
 * the launch page re-offers it on every app open until it is downloaded or skipped.
 */
enum class UpdateAnnouncement { LAUNCH_PAGE, NOTIFICATION }

/** How the automatic checks space themselves out and which releases they may show. */
internal object UpdateSchedule {
    val COOLDOWN_MS: Long = TimeUnit.HOURS.toMillis(12)

    fun isDue(
        lastCheckMs: Long,
        nowMs: Long,
    ): Boolean = nowMs < lastCheckMs || nowMs - lastCheckMs >= COOLDOWN_MS

    fun mayAnnounce(
        version: String,
        skippedVersion: String?,
        announcedVersion: String?,
    ): Boolean = version != skippedVersion && version != announcedVersion
}

/**
 * The one place YouGlideTube asks GitHub about releases. The launch check, the background worker and the
 * Settings button all come through here, so a cold start never fetches twice and a skipped version
 * stays skipped everywhere.
 */
@Singleton
class UpdateRepository
    @Inject
    constructor(
        private val client: OkHttpClient,
        private val dataManager: LocalDataManager,
        @ApplicationContext private val appContext: Context,
    ) {
        private val mutex = Mutex()
        private val _latest = MutableStateFlow<AppRelease?>(null)

        /** The newest release found this process, or null when none is known or this build is current. */
        val latest: StateFlow<AppRelease?> = _latest.asStateFlow()

        /** Asks GitHub now. Throws when the request fails, so a check the user started can say so. */
        suspend fun fetch(): AppRelease? = mutex.withLock { fetchLocked() }

        /**
         * For the automatic checks: fetches only once the cooldown has passed, and returns a release
         * only if [announcement] has not shown this version before and the user has not skipped it.
         * The launch check passes [bypassCooldown] so opening the app always asks GitHub now
         * instead of waiting out the cooldown a background check already spent.
         *
         * The launch page re-offers a release on every app open until the user downloads it or
         * skips that version: backing out of the update page is "later", not "never". Marking a
         * version announced before it was shown silenced it forever after a single dismissal.
         * The notification still announces a version once.
         */
        suspend fun releaseToAnnounce(
            announcement: UpdateAnnouncement,
            nowMs: Long = System.currentTimeMillis(),
            bypassCooldown: Boolean = false,
        ): AppRelease? =
            mutex.withLock {
                if (!bypassCooldown && !UpdateSchedule.isDue(dataManager.lastUpdateCheck.first(), nowMs)) return@withLock null
                val release =
                    runCatching { fetchLocked() }
                        .onFailure { Log.w(TAG, "Update check failed", it) }
                        .getOrNull() ?: return@withLock null
                val skipped = dataManager.skippedUpdateVersion.first()
                if (announcement == UpdateAnnouncement.NOTIFICATION) {
                    val notified = dataManager.notifiedUpdateVersion.first()
                    if (!UpdateSchedule.mayAnnounce(release.version, skipped, notified)) return@withLock null
                    dataManager.setNotifiedUpdateVersion(release.version)
                } else if (release.version == skipped) {
                    return@withLock null
                }
                release
            }

        suspend fun skip(version: String) = dataManager.setSkippedUpdateVersion(version)

        // The running APK's own SHA-256, so a same-version rebuild can be told apart
        // from the installed build: version numbers never move on this release line.
        private var installedSha256Cache: String? = null
        private var installedSha256Computed = false

        private suspend fun installedSha256(): String? {
            if (installedSha256Computed) return installedSha256Cache
            installedSha256Computed = true
            installedSha256Cache =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val digest = MessageDigest.getInstance("SHA-256")
                        FileInputStream(appContext.applicationInfo.sourceDir).use { stream ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var read: Int
                            while (stream.read(buffer).also { read = it } != -1) {
                                digest.update(buffer, 0, read)
                            }
                        }
                        digest.digest().joinToString("") { "%02x".format(it) }
                    }.getOrNull()
                }
            return installedSha256Cache
        }

        private suspend fun fetchLocked(): AppRelease? {
            val body =
                withContext(Dispatchers.IO) {
                    val request =
                        Request
                            .Builder()
                            .url(STABLE_RELEASE_API)
                            .header("Accept", GITHUB_JSON)
                            .cacheControl(CacheControl.FORCE_NETWORK)
                            .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("Update check failed: HTTP ${response.code}")
                        response.body.string()
                    }
                }
            val parsed = parseGitHubRelease(body)
            val release = parsed.toAppReleaseIfNewer(BuildConfig.VERSION_NAME, installedSha256())
            dataManager.setLastUpdateCheck(System.currentTimeMillis())
            _latest.value = release
            return release
        }
    }
