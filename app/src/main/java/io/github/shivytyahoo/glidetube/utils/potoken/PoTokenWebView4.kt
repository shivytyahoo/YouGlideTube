package io.github.shivytyahoo.glidetube.utils.potoken

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.annotation.Keep
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import io.github.shivytyahoo.glidetube.innertube.models.YouTubeClient
import io.github.shivytyahoo.glidetube.network.AppProxyManager
import io.github.shivytyahoo.glidetube.player.error.PlayerDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Ported from NewTube's PoTokenWebView4 (upstream MediaServiceCore 476357b4, a WebView port of
 * bgutil PR #243), with the homepage challenge as the fix for Report 51: the legacy WAA `Create`
 * challenge carries no page, no `ytcfg` and no `EVENT_ID`, and YouTube now refuses tokens minted
 * from it for WEB/MWEB sessions (HTTP 403 TOKEN_REJECTED). This generator fetches
 * `GET https://www.youtube.com`, takes the first `ytcfg.set({...})` and the `window.ytAtN({...}).R`
 * challenge from that same page, injects `yt = { config_: ytcfg }` before running BotGuard, and
 * falls back to `/youtubei/v1/att/get` and then to the legacy [PoTokenWebView].
 *
 * Adaptations to this codebase (the port is otherwise intact):
 * - HTTP goes through the [AppProxyManager] client (the homepage fetch must leave from the same
 *   IP as the downloads), and JSON is kotlinx.serialization, not nanojson.
 * - `generatePoToken`/`attest` are suspend: the blocking latch waits run on Dispatchers.IO, never
 *   on the main thread they post to.
 * - `attest()` re-runs the init chain on the same WebView (the legacy generator does the same),
 *   so [PoTokenGenerator] can re-attest without rebuilding; a failed re-attestation closes the
 *   WebView and the caller builds a fresh one.
 * - `isExpired` also covers a closed generator, so a build never hands a dead one back.
 * - Safe Browsing is disabled through the platform API (minSdk 26), not androidx.webkit.
 * - The init-failure line goes through [PlayerDiagnostics] as well as Log, so it lands in the
 *   Diagnostics report.
 */
class PoTokenWebView4 private constructor(
    context: Context,
    private var onInitDone: () -> Unit
) : BotGuardGenerator {
    private val webView = WebView(context)
    // A null result means "failed, read initError" (see onInitializationErrorCloseAndCancel)
    private val poTokenEmitters = mutableListOf<Pair<String, (String?) -> Unit>>()
    private var expirationMs: Long = -1
    @Volatile
    var initError: Throwable? = null
    // How the challenge was obtained, for the session-build telemetry line, and which step the
    // build is in, for the failure line. Written on the JavaBridge thread.
    @Volatile
    private var challengeInfo: PoTokenChallengeInfo? = null
    @Volatile
    private var stage = STAGE_LOAD
    // close() is reached from the error path, from the provider and from the init timeout. The
    // WebView is destroyed once, and nothing is evaluated on it afterwards.
    @Volatile
    private var closed = false

    //region Initialization
    init {
        val webViewSettings = webView.settings
        //noinspection SetJavaScriptEnabled we want to use JavaScript!
        webViewSettings.javaScriptEnabled = true
        setSafeBrowsingEnabled(webViewSettings, false)

        webViewSettings.userAgentString = USER_AGENT
        webViewSettings.blockNetworkLoads = true // the WebView does not need internet access

        // so that we can run async functions and get back the result
        webView.addJavascriptInterface(this, JS_INTERFACE)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.message().contains("Uncaught")) {
                    // There should not be any uncaught errors while executing the code, because
                    // everything that can fail is guarded by try-catch. Therefore, this likely
                    // indicates that there was a syntax error in the code, i.e. the WebView only
                    // supports a really old version of JS.

                    val message = m.message()
                        .removePrefix("Uncaught (in promise) ")
                    val fmt = "\"$message\", source: ${m.sourceId()} (${m.lineNumber()})"
                    Log.e(TAG, "This WebView implementation is broken: $fmt")

                    onInitializationErrorCloseAndCancel(BadWebViewException(fmt))
                }
                return super.onConsoleMessage(m)
            }
        }
    }

    private fun setSafeBrowsingEnabled(settings: WebSettings, enabled: Boolean) {
        try {
            settings.safeBrowsingEnabled = enabled
        } catch (e: AbstractMethodError) { // Sometimes happens on Android 8/9
            Log.w(TAG, "Could not set safe browsing: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Must be called right after instantiating [PoTokenWebView4] to perform the actual
     * initialization. This will asynchronously go through all the steps needed to load BotGuard,
     * run it, and obtain an `integrityToken`.
     */
    private fun loadHtmlAndObtainBotguard(context: Context) {
        Log.d(TAG, "loadHtmlAndObtainBotguard() called")

        val html = context.assets.open("po_token2.html").bufferedReader()
            .use { it.readText() }

        webView.loadDataWithBaseURL(
            "https://www.youtube.com",
            html.replaceFirst(
                "</script>",
                // calls downloadAndRunBotguard() when the page has finished loading
                "\n$JS_INTERFACE.downloadAndRunBotguard()</script>"
            ),
            "text/html",
            "utf-8",
            null,
        )
    }

    /**
     * Re-runs the full BotGuard attestation on this WebView and suspends until the minter exists
     * again. Reloading the page replaces the JS context wholesale, so a re-attestation starts as
     * clean as a brand-new instance would. A failed re-attestation closes the WebView; the caller
     * then builds a fresh generator.
     */
    override suspend fun attest() {
        check(!closed) { "attest() called on a closed PoTokenWebView4" }
        withContext(Dispatchers.IO) {
            val latch = CountDownLatch(1)
            initError = null
            stage = STAGE_LOAD
            onInitDone = { latch.countDown() }
            runOnMainThread {
                if (closed) {
                    latch.countDown()
                    return@runOnMainThread
                }
                try {
                    loadHtmlAndObtainBotguard(webView.context)
                } catch (e: Exception) {
                    onInitializationErrorCloseAndCancel(
                        BadWebViewException("${e::class.simpleName}: ${e.message}"))
                }
            }
            val completed = latch.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!completed || closed) {
                throw initError ?: PoTokenException("$TAG: re-attestation timed out")
            }
            initError?.let { throw it }
        }
    }

    /**
     * Called during initialization by the JavaScript snippet appended to the HTML page content in
     * [loadHtmlAndObtainBotguard] after the WebView content has been loaded.
     */
    @JavascriptInterface
    fun downloadAndRunBotguard() {
        Log.d(TAG, "downloadAndRunBotguard() called")

        // Always mint from the homepage's (ytcfg, ytAtN) pair — plugin-passed challenges lack
        // their page's ytcfg/EVENT_ID and /att/get tokens are rejected. BotGuard reads
        // yt.config_.EVENT_ID.
        // NOTE: with ytcfg pot becomes smaller: 120 chars instead of regular 124
        // The homepage path never throws: any failure there moves on to /att/get, as bgutil's
        // try/catch does; /att/get and GenerateIT failures are PoTokenExceptions, so the provider
        // falls back to PoTokenWebView instead of reading them as a broken WebView.
        val (parsedChallengeData, ytcfg) = try {
            getChallengeFromHomepage() ?: getLegacyChallengeData()
        } catch (e: Throwable) {
            onInitializationErrorCloseAndCancel(asInitError(e))
            return
        }

        stage = STAGE_BOTGUARD

        // The page config goes into the script once.
        val ytInit = if (ytcfg != null) "yt = { config_: $ytcfg }" else ""

        runOnMainThread {
            if (closed) return@runOnMainThread
            webView.evaluateJavascript(
                """try {
                    $ytInit
                    runBotGuard($parsedChallengeData).then(function (result) {
                        webPoSignalOutput = result.webPoSignalOutput
                        if (!webPoSignalOutput.length)
                            $JS_INTERFACE.onJsInitializationError("webPoSignalOutput is empty")
                        else
                            $JS_INTERFACE.onRunBotguardResult(result.botguardResponse)
                    }, function (error) {
                        $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                    })
                } catch (error) {
                    $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                }""",
                null
            )
        }
    }

    /**
     * Fetch the YT homepage and extract a self-consistent (ytcfg, ytAtN challenge) pair. Injects
     * yt.config_ into the BotGuard global object so the snapshot sees EVENT_ID. Returns null on
     * any failure (caller falls back). The steps (page GET, [extractHomepageChallenge],
     * interpreter download) and the failure names are [attemptHomepageChallenge], which is
     * unit-tested without a WebView.
     */
    private fun getChallengeFromHomepage(): Pair<String, String?>? {
        stage = STAGE_HOMEPAGE

        val attempt = attemptHomepageChallenge(
            fetchPage = { fetch(HOMEPAGE_URL, null, HOMEPAGE_HEADERS) },
            // downloads the interpreter from the challenge's interpreterUrl
            descramble = { parseDescrambledChallengeData(it) }
        )

        challengeInfo = attempt.info
        val parsedChallengeData = attempt.challengeData

        if (parsedChallengeData == null) {
            Log.w(TAG, "homepage-challenge: ${attempt.info.homepageFailure}, falling back to /att/get")
            return null
        }

        if (attempt.ytcfg == null) {
            Log.w(TAG, "homepage-challenge: no ytcfg found (EVENT_ID missing)")
        }

        Log.d(TAG, "Using challenge from the homepage (patched)")

        return Pair(parsedChallengeData, attempt.ytcfg)
    }

    /**
     * Using challenge from /att/get (legacy fallback). A strict JSON body. No page is read, so
     * there is no ytcfg and no EVENT_ID: the challenge BgUtils #44 reports rejected for sessions
     * in YouTube's experiment on WEB/MWEB. Failures throw; see [downloadAndRunBotguard].
     */
    private fun getLegacyChallengeData(): Pair<String, String?> {
        stage = STAGE_ATT_GET
        val client = YouTubeClient.WEB

        val body = Json.encodeToString(
            JsonObject.serializer(),
            JsonObject(
                mapOf(
                    "context" to JsonObject(
                        mapOf(
                            "client" to JsonObject(
                                mapOf(
                                    "clientName" to JsonPrimitive(client.clientName),
                                    "clientVersion" to JsonPrimitive(client.clientVersion)
                                )
                            )
                        )
                    ),
                    "engagementType" to JsonPrimitive("ENGAGEMENT_TYPE_UNBOUND")
                )
            )
        )

        val responseBody = fetch(
            "https://www.youtube.com/youtubei/v1/att/get?prettyPrint=false",
            body,
            BOTGUARD_HEADERS + ("Content-Type" to "application/json")
        ).body

        Log.d(TAG, "Using challenge from /att/get (legacy fallback)")

        return Pair(parseDescrambledChallengeData(responseBody), null)
    }

    /**
     * Called during initialization by the JavaScript snippets from either
     * [downloadAndRunBotguard] or [onRunBotguardResult].
     */
    @JavascriptInterface
    fun onJsInitializationError(error: String) {
        Log.e(TAG, error)
        onInitializationErrorCloseAndCancel(buildExceptionForJsError(error))
    }

    /**
     * Called during initialization by the JavaScript snippet from [downloadAndRunBotguard] after
     * obtaining the BotGuard execution output [botguardResponse].
     */
    @JavascriptInterface
    fun onRunBotguardResult(botguardResponse: String) {
        // Lengths only, here and below; never log a token value.
        Log.d(TAG, "botguardResponse: ${botguardResponse.length} chars")
        stage = STAGE_GENERATE_IT

        // NOTE: both urls can be used to get botguard response.
        // The first on produces more reliable token (without 403 error) but may hang upon request.
        // "https://jnn-pa.googleapis.com/$rpc/google.internal.waa.v1.Waa/GenerateIT"
        // "https://www.youtube.com/api/jnn/v1/GenerateIT"
        val (integrityToken, expirationTimeInSeconds) = try {
            val responseBody = fetch(
                "https://www.youtube.com/api/jnn/v1/GenerateIT",
                "[ \"${REQUEST_KEY}\", \"$botguardResponse\" ]",
                BOTGUARD_HEADERS
            ).body

            Log.d(TAG, "GenerateIT response: ${responseBody.length} chars")
            parseIntegrityTokenData(responseBody)
        } catch (e: Exception) {
            onInitializationErrorCloseAndCancel(asInitError(e))
            return
        }

        // leave 10 minutes of margin just to be sure
        expirationMs = System.currentTimeMillis() + ((expirationTimeInSeconds - 600) * 1_000)
        challengeInfo = challengeInfo?.withTtl(expirationTimeInSeconds)
        stage = STAGE_MINTER

        runOnMainThread {
            if (closed) return@runOnMainThread
            // `else` — the error branch must not also report success after it.
            webView.evaluateJavascript(
                """try {
                        getMinter = webPoSignalOutput[0]
                        mintCallback = getMinter($integrityToken)
                        if (typeof mintCallback === 'undefined')
                            $JS_INTERFACE.onJsInitializationError("mintCallback is not defined")
                        else
                            ${JS_INTERFACE}.onJsInitializationDone($expirationTimeInSeconds)
                        webPoSignalOutput = null
                        getMinter = null
                    } catch (error) {
                        ${JS_INTERFACE}.onJsInitializationError(error + "\n" + error.stack)
                    }""",
                null
            )
        }
    }

    @JavascriptInterface
    fun onJsInitializationDone(expirationTimeInSeconds: Long) {
        Log.d(TAG, "initialization finished, expiration=${expirationTimeInSeconds}s")
        stage = STAGE_DONE
        onInitDone()
    }
    //endregion

    //region Obtaining poTokens
    override suspend fun generatePoToken(identifier: String): String =
        withContext(Dispatchers.IO) { generatePoTokenBlocking(identifier) }

    private fun generatePoTokenBlocking(identifier: String): String {
        // No identifier in the log (a visitor or a video id)
        Log.d(TAG, "generatePoToken() called")

        if (closed) {
            throw initError ?: PoTokenException("$TAG: generator is closed")
        }

        val latch = CountDownLatch(1)
        var pot: String? = null

        addPoTokenEmitter(identifier) {
            pot = it
            latch.countDown()
        }

        val u8Identifier = stringToU8(identifier)

        runOnMainThread {
            if (closed) return@runOnMainThread
            webView.evaluateJavascript(
                """try {
                        poTokenU8 = obtainPoToken($u8Identifier)
                        poTokenU8String = ""
                        for (i = 0; i < poTokenU8.length; i++) {
                            if (i != 0) poTokenU8String += ","
                            poTokenU8String += poTokenU8[i]
                        }
                        $JS_INTERFACE.onObtainPoTokenResult("$identifier", poTokenU8String)
                        poTokenU8 = null
                        poTokenU8String = null
                    } catch (error) {
                        $JS_INTERFACE.onObtainPoTokenError("$identifier", error + "\n" + error.stack)
                    }""",
                null
            )
        }

        // A mint that does not answer fails with a PoTokenException instead of returning an
        // unassigned value, and a mint error releases its waiter at once.
        try {
            awaitOrThrow(latch, MINT_TIMEOUT_MS, "$TAG mint")
        } catch (e: PoTokenException) {
            popPoTokenEmitter(identifier)
            throw initError ?: e
        }

        initError?.let { throw it }

        return pot ?: throw PoTokenException("$TAG: mint returned no token")
    }

    /**
     * Called by the JavaScript snippet from [generatePoToken] when an error occurs in calling the
     * JavaScript `obtainPoToken()` function.
     */
    @JavascriptInterface
    fun onObtainPoTokenError(identifier: String, error: String) {
        val msg = "onObtainPoTokenError: error=$error"
        Log.e(TAG, msg)
        onInitializationErrorCloseAndCancel(buildExceptionForJsError(msg))
    }

    /**
     * Called by the JavaScript snippet from [generatePoToken] with the original identifier and the
     * result of the JavaScript `obtainPoToken()` function.
     */
    @JavascriptInterface
    fun onObtainPoTokenResult(identifier: String, poTokenU8: String) {
        val poToken = u8ToBase64(poTokenU8)

        Log.d(TAG, "Generated poToken: ${poToken.length} chars")
        popPoTokenEmitter(identifier)?.invoke(poToken)
    }

    override val isExpired: Boolean
        get() = closed || System.currentTimeMillis() > expirationMs

    override val isDestroyed: Boolean
        get() = closed

    override fun diagnostics(): String = challengeInfo?.toLogFields() ?: ""

    //endregion

    //region Handling multiple emitters
    /**
     * Adds the ([identifier], [emitter]) pair to the [poTokenEmitters] list. This makes it so that
     * multiple poToken requests can be generated in parallel, and the results will be notified to
     * the right emitters.
     */
    private fun addPoTokenEmitter(identifier: String, emitter: (String?) -> Unit) {
        synchronized(poTokenEmitters) {
            poTokenEmitters.add(Pair(identifier, emitter))
        }
    }

    /**
     * Extracts and removes from the [poTokenEmitters] list an emitter based on its [identifier].
     * The emitter is supposed to be used immediately after to either signal a success or an error.
     */
    private fun popPoTokenEmitter(identifier: String): ((String?) -> Unit)? {
        return synchronized(poTokenEmitters) {
            poTokenEmitters.indexOfFirst { it.first == identifier }.takeIf { it >= 0 }?.let {
                poTokenEmitters.removeAt(it).second
            }
        }
    }

    /**
     * Clears [poTokenEmitters] and returns its previous contents. The emitters are supposed to be
     * used immediately after to either signal a success or an error.
     */
    private fun popAllPoTokenEmitters(): List<Pair<String, (String?) -> Unit>> {
        return synchronized(poTokenEmitters) {
            val result = poTokenEmitters.toList()
            poTokenEmitters.clear()
            result
        }
    }
    //endregion

    //region Utils
    /**
     * Every non-200 or empty answer throws, and each caller decides what that means: the homepage
     * path falls back to /att/get, while /att/get and GenerateIT failures are PoTokenExceptions,
     * so the provider falls back to PoTokenWebView instead of reading them as a broken WebView.
     * All requests go through the [AppProxyManager] client, so the homepage fetch leaves from the
     * same IP as the downloads.
     */
    private fun fetch(url: String, data: String?, headers: Map<String, String>): PageAnswer {
        val request = Request.Builder()
            .url(url)
            .headers(headers.toHeaders())
            .let { if (data == null) it.get() else it.post(data.toRequestBody()) }
            .build()

        httpClient.newCall(request).execute().use { response ->
            val httpCode = response.code

            if (httpCode != 200) {
                throw HttpCodeException(httpCode)
            }

            val body = response.body?.string()
                ?: throw PoTokenException("Response body is empty. Response code: $httpCode")

            return PageAnswer(body, response.request.url.host)
        }
    }

    /**
     * Handles any error happening during initialization, releasing resources and sending the error
     * to the waiting build or mint.
     */
    private fun onInitializationErrorCloseAndCancel(error: Throwable) {
        initError = error
        // Wake the waiting mints now; they read initError.
        popAllPoTokenEmitters().forEach { it.second(null) }
        runOnMainThread {
            // A throwing cleanup would crash the main thread and skip onInitDone.
            try {
                close()
            } catch (e: Exception) {
                Log.e(TAG, "close() failed: ${e.javaClass.simpleName}")
            } finally {
                // throw error
                onInitDone()
            }
        }
    }

    /**
     * Releases all [webView] resources.
     */
    @MainThread
    override fun close() {
        if (closed) return
        closed = true

        webView.clearHistory()
        // clears RAM cache and disk cache (globally for all WebViews)
        webView.clearCache(true)

        // ensures that the WebView isn't doing anything when destroying it
        webView.loadUrl("about:blank")

        webView.onPause()
        webView.removeAllViews()
        webView.destroy()
    }
    //endregion

    companion object {
        private const val TAG = "PoTokenWebView4"
        // Public API key used by BotGuard, which has been got by looking at BotGuard requests
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw" // NOSONAR
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36(KHTML, like Gecko)"
        private const val JS_INTERFACE = "PoTokenWebView"
        private const val HOMEPAGE_URL = "https://www.youtube.com"
        private const val INIT_TIMEOUT_MS = 20_000L
        private const val MINT_TIMEOUT_MS = 10_000L

        private const val STAGE_LOAD = "load"
        private const val STAGE_HOMEPAGE = "homepage"
        private const val STAGE_ATT_GET = "att-get"
        private const val STAGE_BOTGUARD = "botguard"
        private const val STAGE_GENERATE_IT = "generate-it"
        private const val STAGE_MINTER = "minter"
        private const val STAGE_DONE = "done"

        // The BotGuard/InnerTube endpoints (GenerateIT, /att/get): the ref's defaults.
        private val BOTGUARD_HEADERS = mapOf(
            // replace the downloader user agent
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json",
            "Content-Type" to "application/json+protobuf",
            "x-goog-api-key" to GOOGLE_API_KEY,
            "x-user-agent" to "grpc-web-javascript/0.1",
        )

        /**
         * The homepage GET as a browser page load: bgutil PR #243's three headers, plus yt-dlp's
         * consent cookie. Without a consent cookie an EU cookieless load may be answered by a
         * consent interstitial with no `ytAtN`. No visitor cookie: bgutil's homepage carries none
         * either, and the challenge is bound to the page's EVENT_ID, not to the /player visitor.
         */
        private val HOMEPAGE_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.7",
            "Cookie" to "SOCS=CAI",
        )

        private val httpClient: OkHttpClient
            get() = AppProxyManager.applyTo(OkHttpClient.Builder()).build()

        /**
         * Builds a WebView and runs its first attestation. Callers should hold on to the result
         * and [PoTokenWebView4.attest] it again rather than building another one — see [attest].
         * Runs the blocking build off the main thread it posts to.
         */
        suspend fun create(context: Context): PoTokenWebView4 =
            withContext(Dispatchers.IO) { newPoTokenGenerator(context) }

        private fun newPoTokenGenerator(context: Context): PoTokenWebView4 {
            if (hasThermalServiceBug(context)) {
                throw BadWebViewException("ThermalService isn't available")
            }

            if (hasUsbServiceBug(context)) {
                throw BadWebViewException("Usb service isn't available")
            }

            val latch = CountDownLatch(1)

            // A nullable holder instead of a lateinit, so a timeout before the main thread has
            // built the generator cannot throw UninitializedPropertyAccessException.
            val potWvRef = AtomicReference<PoTokenWebView4?>()
            var initError: Throwable? = null

            runOnMainThread {
                val potWv = try {
                    PoTokenWebView4(context) { latch.countDown() }
                } catch (e: Throwable) {
                    initError = BadWebViewException("${e::class.simpleName}: ${e.message}")
                    latch.countDown()
                    return@runOnMainThread
                }
                potWvRef.set(potWv)
                // An exception here escaped a main-thread runnable (an app crash) instead of
                // failing the build, so it is caught like the constructor above.
                try {
                    potWv.loadHtmlAndObtainBotguard(context)
                } catch (e: Exception) {
                    potWv.onInitializationErrorCloseAndCancel(
                        BadWebViewException("${e::class.simpleName}: ${e.message}"))
                }
            }

            val completed = latch.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)

            initError?.let { throw it }

            val potWv = potWvRef.get()

            // Check `completed` before touching the generator: a timeout before the main thread
            // had built it would otherwise throw. A timed-out generator is also closed: its
            // JavaBridge thread may still finish the homepage or BotGuard later and would
            // otherwise keep a live WebView nobody owns. The close is posted behind the
            // construction above, so it always finds the instance.
            if (!completed || potWv == null) {
                val error = PoTokenException("${TAG}: failed to initialize within ${INIT_TIMEOUT_MS / 1_000} s")
                logInitFailure(potWv, error, timedOut = true)
                runOnMainThread {
                    try {
                        potWvRef.get()?.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "close() failed: ${e.javaClass.simpleName}")
                    }
                }
                throw error
            }

            potWv.initError?.let {
                logInitFailure(potWv, it, timedOut = false)
                throw it
            }

            return potWv
        }

        /**
         * One secret-free line when a build fails, so a fallback to PoTokenWebView can be read
         * against the step that failed and what the homepage returned.
         */
        private fun logInitFailure(potWv: PoTokenWebView4?, error: Throwable, timedOut: Boolean) {
            val message = "web-pot-wv4 failed stage=" + (potWv?.stage ?: STAGE_LOAD) +
                " timeout=" + (if (timedOut) "y" else "n") +
                " error=" + error.javaClass.simpleName +
                (potWv?.diagnostics() ?: "")
            Log.w(TAG, message)
            PlayerDiagnostics.logWarning(TAG, message)
        }

        /**
         * The error an init step failed with, as the provider expects it: a PoTokenException (it
         * then falls back to PoTokenWebView), never a raw network exception.
         */
        private fun asInitError(e: Throwable): Throwable =
            if (e is PoTokenException || e is BadWebViewException) e
            else PoTokenException("${e.javaClass.simpleName}: ${e.message}")

        /**
         * Runs [runnable] on the main thread using `Handler(Looper.getMainLooper()).post()`, and
         * throws if the `post` fails.
         */
        private fun runOnMainThread(
            runnable: Runnable
        ) {
            if (!Handler(Looper.getMainLooper()).post(runnable)) {
                throw PoTokenException("Could not run on main thread")
            }
        }
    }
}

//region WebView environment pre-checks
private fun hasThermalServiceBug(context: Context): Boolean {
    // Only Android 10 has the issue
    if (Build.VERSION.SDK_INT != 29)
        return false

    val powerService = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false

    return !ThermalServiceApi29.isAvailable(powerService)
}

// Keep API 29 types out of the class verified on older Android versions.
@Keep
@RequiresApi(29)
private object ThermalServiceApi29 {
    fun isAvailable(powerService: PowerManager): Boolean {
        val listener = PowerManager.OnThermalStatusChangedListener {
            // NOP
        }

        return try {
            powerService.addThermalStatusListener(listener)
            powerService.removeThermalStatusListener(listener)
            true
        } catch (e: Exception) {
            Log.e("WebViewUtil", "Thermal service check failed", e)
            false
        }
    }
}

/**
 * NOTE: You will need to declare the android.permission.USB permission in your AndroidManifest.xml
 *
 * Trying to fix: NullPointerException: 'int android.hardware.usb.UsbDevice.getInterfaceCount()' on a null object reference
 */
private fun hasUsbServiceBug(context: Context): Boolean {
    // Only Android 12 and up has the issue
    if (Build.VERSION.SDK_INT != 31)
        return false

    val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false

    val deviceList = manager.getDeviceList()
    if (deviceList == null || deviceList.isEmpty()) {
        return false
    }

    for (device in deviceList.values) {
        if (device == null) {
            Log.e("WebViewUtil", "Found a null USB device in the system's device list")
            return true
        }

        try {
            val interfaceCount = device.interfaceCount
            Log.d("WebViewUtil", "Device: " + device.deviceName + ", Interface Count: " + interfaceCount)

            // You could add more checks here, e.g., for specific interfaces
            // or other properties that might be null.
        } catch (e: Exception) {
            return true
        }
    }

    return false
}
//endregion
