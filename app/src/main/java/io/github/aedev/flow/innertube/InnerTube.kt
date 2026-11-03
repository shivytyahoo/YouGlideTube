package io.github.aedev.flow.innertube

import io.github.aedev.flow.utils.ShivLog
import io.github.aedev.flow.utils.liveChatSendParams

import android.util.Log
import io.github.aedev.flow.FlowApplication
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.innertube.models.Context
import io.github.aedev.flow.innertube.models.MediaInfo
import io.github.aedev.flow.innertube.models.OAuthIdentity
import io.github.aedev.flow.innertube.models.ReturnYouTubeDislikeResponse
import io.github.aedev.flow.innertube.models.YouTubeClient
import io.github.aedev.flow.innertube.models.YouTubeLocale
import io.github.aedev.flow.innertube.models.body.*
import io.github.aedev.flow.innertube.models.normalizeYouTubeHostLanguage
import io.github.aedev.flow.innertube.models.response.NextResponse
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.innertube.utils.parseCookieString
import io.github.aedev.flow.innertube.utils.sha1
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.IOException
import java.net.Proxy
import java.util.*
import kotlin.io.encoding.Base64

private const val TAG = "InnerTube"

/**
 * Provide access to InnerTube endpoints.
 * For making HTTP requests, not parsing response.
 */
class InnerTube {
    private var httpClient = createClient()

    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

    var locale =
        sanitizeLocale(
            YouTubeLocale(
                gl = Locale.getDefault().country,
                hl = Locale.getDefault().toLanguageTag(),
            ),
        )
        set(value) {
            field = sanitizeLocale(value)
        }
    var visitorData: String? = null
        set(value) {
            if (field == value) return
            field = value
            onVisitorDataChanged?.invoke(value)
        }

    /** Every change of identity, including a refused one dropped to null, so it can be persisted. */
    var onVisitorDataChanged: ((String?) -> Unit)? = null
    var dataSyncId: String? = null
    var pageIdToken: String? = null
    var cookie: String? = null
        set(value) {
            field = value
            cookieMap = if (value == null) emptyMap() else parseCookieString(value)
        }
    private var cookieMap = emptyMap<String, String>()

    /** OAuth access token from a TV-code sign-in; used when there is no cookie session. */
    var oauthAccessToken: String? = null

    var proxy: Proxy? = null
        set(value) {
            if (field == value) return
            field = value
            httpClient.close()
            httpClient = createClient()
        }

    var proxyAuth: String? = null
        set(value) {
            if (field == value) return
            field = value
            httpClient.close()
            httpClient = createClient()
        }

    var cacheDirectory: java.io.File? = null
        set(value) {
            if (field == value) return
            field = value
            httpClient.close()
            httpClient = createClient()
        }

    var useLoginForBrowse: Boolean = false

    private fun sanitizeLocale(value: YouTubeLocale): YouTubeLocale =
        YouTubeLocale(
            gl = sanitizeCountryCode(value.gl),
            hl = sanitizeLanguageCode(value.hl),
        )

    private fun sanitizeCountryCode(value: String): String {
        val normalized = value.trim().uppercase(Locale.US)
        return if (normalized.matches(Regex("[A-Z]{2}"))) {
            normalized
        } else {
            Locale
                .getDefault()
                .country
                .trim()
                .uppercase(Locale.US)
                .takeIf { it.matches(Regex("[A-Z]{2}")) }
                ?: "US"
        }
    }

    private fun sanitizeLanguageCode(value: String): String = normalizeYouTubeHostLanguage(value)

    @OptIn(ExperimentalSerializationApi::class)
    private fun createClient() =
        HttpClient(OkHttp) {
            expectSuccess = true

            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        explicitNulls = false
                        encodeDefaults = true
                    },
                )
            }

            install(ContentEncoding) {
                gzip(0.9F)
                deflate(0.8F)
            }

            // PERFORMANCE OPTIMIZED: Enhanced network configuration
            engine {
                config {
                    // Aggressive connection pool for faster connection reuse
                    connectionPool(
                        okhttp3.ConnectionPool(
                            15, // Increased from 10 - more connections available
                            5, // keepAliveDuration
                            java.util.concurrent.TimeUnit.MINUTES,
                        ),
                    )

                    // Faster timeout configurations - fail fast, retry smart
                    connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)

                    // Enable HTTP/2 for multiplexing (parallel streams on single connection)
                    protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))

                    // Retry on connection failure
                    retryOnConnectionFailure(true)

                    // High concurrency dispatcher
                    dispatcher(
                        okhttp3.Dispatcher().apply {
                            maxRequests = 48
                            maxRequestsPerHost = 8
                        },
                    )

                    // Cache configuration for better performance
                    cache(
                        okhttp3.Cache(
                            directory = cacheDirectory ?: java.io.File(System.getProperty("java.io.tmpdir"), "http_cache"),
                            maxSize = 50L * 1024L * 1024L, // 50 MB
                        ),
                    )
                }

                // Qualified: a bare `proxy` here is the engine config's own, which starts null.
                this@InnerTube.proxy?.let { proxy = it }

                // Fix proxy auth
                proxyAuth?.let { auth ->
                    config {
                        proxyAuthenticator { _, response ->
                            response.request
                                .newBuilder()
                                .header("Proxy-Authorization", auth)
                                .build()
                        }
                    }
                }
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 45000
                connectTimeoutMillis = 15000
                socketTimeoutMillis = 30000
            }

            defaultRequest {
                url(YouTubeClient.API_URL_YOUTUBE_MUSIC)
                // Add common headers for better compatibility
                header("Accept", "application/json")
                header("Accept-Language", "en-US,en;q=0.9")
                header("Cache-Control", "no-cache")
            }
        }

    /**
     * Simple retry wrapper for transient IO errors (socket aborts, timeouts).
     * Retries the given block up to [maxAttempts] times with exponential backoff.
     * Cancellation is respected since [delay] will throw if the coroutine is cancelled.
     */
    private suspend fun <T> withRetry(
        maxAttempts: Int = 3,
        initialDelay: Long = 500L,
        factor: Double = 2.0,
        block: suspend () -> T,
    ): T {
        var currentDelay = initialDelay
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: IOException) {
                attempt++
                if (attempt >= maxAttempts) throw e
                delay(currentDelay)
                currentDelay = (currentDelay * factor).toLong()
            }
        }
    }

    private fun HttpRequestBuilder.ytClient(
        client: YouTubeClient,
        setLogin: Boolean = false,
        apiUrl: String? = null,
    ) {
        val useMainSite = apiUrl != null && apiUrl != YouTubeClient.API_URL_YOUTUBE_MUSIC
        val origin = if (useMainSite) YouTubeClient.ORIGIN_YOUTUBE else YouTubeClient.ORIGIN_YOUTUBE_MUSIC
        val referer = client.referer
            ?: if (useMainSite) YouTubeClient.REFERER_YOUTUBE else YouTubeClient.REFERER_YOUTUBE_MUSIC
        contentType(ContentType.Application.Json)
        headers {
            append("X-Goog-Api-Format-Version", "1")
            append("X-YouTube-Client-Name", client.clientId)
            append("X-YouTube-Client-Version", client.clientVersion)
            append("X-Origin", origin)
            append("Referer", referer)
            visitorData?.let { append("X-Goog-Visitor-Id", it) }
            if (setLogin && client.loginSupported) {
                cookie?.let { cookie ->
                    append("cookie", cookie)
                    // Current Google sessions often carry the id only under its __Secure-
                    // names. Without a value here no Authorization proof is sent at all —
                    // reads tolerate that; sensitive writes (comment create) do not.
                    val sapisid =
                        cookieMap["SAPISID"]
                            ?: cookieMap["__Secure-3PSAPISID"]
                            ?: cookieMap["__Secure-1PSAPISID"]
                            ?: return@let
                    val currentTime = System.currentTimeMillis() / 1000
                    val sapisidHash = sha1("$currentTime $sapisid $origin")
                    append("Authorization", "SAPISIDHASH ${currentTime}_$sapisidHash")
                }
                if (cookie == null) {
                    // TV-code sign-in: the OAuth bearer token is the whole session.
                    oauthAccessToken?.let { token ->
                        append("Authorization", "Bearer $token")
                    }
                }
            }
        }
        userAgent(client.userAgent)
        parameter("prettyPrint", false)
    }

    suspend fun search(
        client: YouTubeClient,
        query: String? = null,
        params: String? = null,
        continuation: String? = null,
    ) = withRetry {
        httpClient.post("search") {
            ytClient(client, setLogin = useLoginForBrowse)
            setBody(
                SearchBody(
                    context =
                        client.toContext(
                            locale,
                            visitorData,
                            if (useLoginForBrowse) dataSyncId else null,
                        ),
                    query = query,
                    params = params,
                ),
            )
            parameter("continuation", continuation)
            parameter("ctoken", continuation)
        }
    }

    /**
     * Search the main YouTube site (www.youtube.com), not the music endpoint. Needed to
     * reach renderers the music search omits, e.g. the Shorts shelf.
     */
    suspend fun webSearch(
        client: YouTubeClient,
        query: String? = null,
        params: String? = null,
        continuation: String? = null,
        anonymous: Boolean = false,
        includeVisitorData: Boolean = !anonymous,
    ) = withRetry {
        withVisitorDataFallback(includeVisitorData) { requestVisitorData ->
            httpClient.post("https://www.youtube.com/youtubei/v1/search") {
                headers {
                    append("X-YouTube-Client-Name", client.clientId)
                    append("X-YouTube-Client-Version", client.clientVersion)
                    if (anonymous) {
                        append(HttpHeaders.Origin, YouTubeClient.ORIGIN_YOUTUBE)
                        append("Referer", YouTubeClient.ORIGIN_YOUTUBE)
                        append(HttpHeaders.Cookie, "SOCS=CAE=")
                        append(HttpHeaders.AcceptLanguage, "${locale.hl}, en;q=0.9")
                    } else {
                        append("X-Origin", YouTubeClient.ORIGIN_YOUTUBE)
                        append("Referer", YouTubeClient.REFERER_YOUTUBE)
                    }
                    requestVisitorData?.let { append("X-Goog-Visitor-Id", it) }
                }
                contentType(io.ktor.http.ContentType.Application.Json)
                userAgent(client.userAgent)
                parameter("prettyPrint", false)
                setBody(
                    SearchBody(
                        context = client.toContext(locale, requestVisitorData, null),
                        query = query,
                        params = params,
                        continuation = continuation,
                    ),
                )
            }
        }
    }

    /**
     * Typeahead suggestions. Not an InnerTube endpoint — a JSONP array from the suggest host — but
     * it rides the same client so it honours the app's locale, proxy and connection pool.
     */
    suspend fun searchSuggestions(query: String) =
        withRetry {
            httpClient.get("https://suggestqueries-clients6.youtube.com/complete/search") {
                parameter("client", "youtube")
                parameter("ds", "yt")
                parameter("hl", locale.hl)
                parameter("gl", locale.gl)
                parameter("q", query)
                userAgent(YouTubeClient.WEB.userAgent)
            }
        }

    private suspend fun webBrowse(
        client: YouTubeClient,
        body: (String?) -> BrowseBody,
    ) = mainSitePost(client, "browse", body)

    private suspend inline fun <reified T> mainSitePost(
        client: YouTubeClient,
        path: String,
        crossinline body: (String?) -> T,
    ) = withRetry {
        withVisitorDataFallback { requestVisitorData ->
            httpClient.post("https://www.youtube.com/youtubei/v1/$path") {
                headers {
                    append("X-YouTube-Client-Name", client.clientId)
                    append("X-YouTube-Client-Version", client.clientVersion)
                    append("X-Origin", YouTubeClient.ORIGIN_YOUTUBE)
                    append("Referer", YouTubeClient.REFERER_YOUTUBE)
                    requestVisitorData?.let { append("X-Goog-Visitor-Id", it) }
                }
                contentType(ContentType.Application.Json)
                userAgent(client.userAgent)
                parameter("prettyPrint", false)
                setBody(body(requestVisitorData))
            }
        }
    }

    /**
     * Search for videos within a specific YouTube channel using the /browse endpoint.
     * Uses the channel's Search Tab (params = "EgZzZWFyY2jyBgQKAloA") with the query as a
     * top-level body field.
     *
     * @param channelId  The channel's browse ID, e.g. "UCxxxxxxxxxxxxxxxxxx"
     * @param query      The search term
     * @param continuation  Pagination token returned from a previous search call
     */
    suspend fun channelSearch(
        client: YouTubeClient,
        channelId: String,
        query: String,
        continuation: String? = null,
    ) = webBrowse(client) { requestVisitorData ->
        BrowseBody(
            context = client.toContext(locale, requestVisitorData, null),
            browseId = if (continuation == null) channelId else null,
            params = if (continuation == null) "EgZzZWFyY2jyBgQKAloA" else null,
            query = if (continuation == null) query else null,
            continuation = continuation,
        )
    }

    /**
     * Browse a YouTube channel tab through the YouTube.com WEB /browse endpoint.
     *
     * Initial calls pass [channelId] and [params]; continuation calls pass only
     * [continuation].
     */
    suspend fun channelBrowse(
        client: YouTubeClient,
        channelId: String? = null,
        params: String? = null,
        continuation: String? = null,
    ) = webBrowse(client) { requestVisitorData ->
        BrowseBody(
            context = client.toContext(locale, requestVisitorData, null),
            browseId = if (continuation == null) channelId else null,
            params = if (continuation == null) params else null,
            continuation = continuation,
        )
    }

    /**
     * A playlist page browse WITH the account's login. The channel browse above travels
     * through mainSitePost, which sends no cookie and no authorization at all: public
     * playlists answer a guest, but the account's own system lists (Liked videos LL, Liked
     * music LM, Watch later WL) never do — their fetches came back empty, which is why a
     * server-accepted video like never reappeared on reopen (an empty like seed). Same
     * session-client rule as the guide: cookie session speaks WEB, an OAuth-only session
     * speaks TVHTML5.
     */
    suspend fun playlistBrowse(
        browseId: String? = null,
        params: String? = null,
        continuation: String? = null,
    ) = browseMainSite(
        client =
            if (cookie.isNullOrBlank() && oauthAccessToken != null) {
                YouTubeClient.TV_FIRETV
            } else {
                YouTubeClient.WEB.copy(loginSupported = true)
            },
        browseId = browseId,
        params = params,
        continuation = continuation,
        setLogin = true,
    )

    /** What a youtube.com link points at, as the endpoint the site would follow for it. */
    suspend fun resolveUrl(
        client: YouTubeClient,
        url: String,
    ) = mainSitePost(client, "navigation/resolve_url") { requestVisitorData ->
        ResolveUrlBody(context = client.toContext(locale, requestVisitorData, null), url = url)
    }

    private suspend fun <T> withVisitorDataFallback(
        includeVisitorData: Boolean = true,
        block: suspend (String?) -> T,
    ): T {
        val requestVisitorData =
            visitorData?.takeIf { includeVisitorData && it.isNotBlank() }
                ?: return block(null)
        return try {
            block(requestVisitorData)
        } catch (error: ClientRequestException) {
            if (error.response.status != HttpStatusCode.BadRequest) throw error
            val response = block(null)
            if (visitorData == requestVisitorData) {
                visitorData = null
            }
            Log.w(TAG, "InnerTube rejected visitor data; request succeeded without it")
            response
        }
    }

    suspend fun postCommentsBrowse(
        client: YouTubeClient,
        postId: String? = null,
        params: String? = null,
        continuation: String? = null,
    ) = webBrowse(client) { requestVisitorData ->
        BrowseBody(
            context = client.toContext(locale, requestVisitorData, null),
            browseId = if (continuation == null) "FEpost_detail" else null,
            params = if (continuation == null) params else null,
            continuation = continuation,
            canonicalBaseUrl =
                if (continuation == null && params == null) {
                    postId?.let { "/post/$it" }
                } else {
                    null
                },
        )
    }

    suspend fun player(
        client: YouTubeClient,
        videoId: String,
        playlistId: String?,
        signatureTimestamp: Int?,
        poToken: String? = null,
        localeOverride: YouTubeLocale? = null,
        apiUrl: String? = null,
    ) = withRetry {
        httpClient.post(apiUrl?.let { "${it}player" } ?: "player") {
            ytClient(client, setLogin = true, apiUrl = apiUrl)
            setBody(
                PlayerBody(
                    context =
                        client.toContext(localeOverride ?: locale, visitorData, dataSyncId).let {
                            if (client.isEmbedded) {
                                it.copy(
                                    thirdParty =
                                        Context.ThirdParty(
                                            embedUrl = "https://www.youtube.com/watch?v=$videoId",
                                        ),
                                )
                            } else {
                                it
                            }
                        },
                    videoId = videoId,
                    playlistId = playlistId,
                    playbackContext =
                        if (client.useSignatureTimestamp && signatureTimestamp != null) {
                            PlayerBody.PlaybackContext(
                                PlayerBody.PlaybackContext.ContentPlaybackContext(
                                    signatureTimestamp,
                                ),
                            )
                        } else {
                            null
                        },
                    serviceIntegrityDimensions = poToken?.let { PlayerBody.ServiceIntegrityDimensions(it) },
                ),
            )
        }
    }

    /**
     * The attested main-site player request behind the SABR path.
     *
     * [client] must be a web-like client served from `www.youtube.com/youtubei/v1` — WEB or MWEB.
     * The Android/iOS clients live on the gapis host with a different header set and do not belong
     * here.
     */
    suspend fun playerWeb(
        videoId: String,
        signatureTimestamp: Int?,
        poToken: String?,
        visitorData: String?,
        locale: YouTubeLocale,
        cpn: String?,
        reloadToken: String? = null,
        client: YouTubeClient = YouTubeClient.WEB,
        withAuth: Boolean = false,
    ) = withRetry {
        httpClient.post("https://www.youtube.com/youtubei/v1/player") {
            headers {
                append("X-Goog-Api-Format-Version", "1")
                append("X-YouTube-Client-Name", client.clientId)
                append("X-YouTube-Client-Version", client.clientVersion)
                append("X-Origin", "https://www.youtube.com")
                append("Referer", "https://www.youtube.com/")
                visitorData?.let { append("X-Goog-Visitor-Id", it) }
                if (withAuth) {
                    // The tracking fetch wants an ACCOUNT-minted blob, not a
                    // guest-visitor one: the beacon later presents the same
                    // auth, so the server can attribute the blob to the
                    // account's history. Kept off the SABR path (default)
                    // so working streams never change shape.
                    cookie?.let { cookieValue ->
                        append("cookie", cookieValue)
                        val sapisid =
                            cookieMap["SAPISID"]
                                ?: cookieMap["__Secure-3PSAPISID"]
                                ?: cookieMap["__Secure-1PSAPISID"]
                        if (sapisid != null) {
                            val currentTime = System.currentTimeMillis() / 1000
                            val sapisidHash = sha1("$currentTime $sapisid https://www.youtube.com")
                            append("Authorization", "SAPISIDHASH ${currentTime}_$sapisidHash")
                        }
                    }
                    if (cookie == null) {
                        oauthAccessToken?.let { token -> append("Authorization", "Bearer $token") }
                    }
                }
            }
            contentType(ContentType.Application.Json)
            userAgent(client.userAgent)
            parameter("prettyPrint", false)
            setBody(
                PlayerBody(
                    context = client.toContext(locale, visitorData, null),
                    videoId = videoId,
                    playlistId = null,
                    playbackContext =
                        if (signatureTimestamp != null || reloadToken != null) {
                            PlayerBody.PlaybackContext(
                                contentPlaybackContext =
                                    signatureTimestamp?.let {
                                        PlayerBody.PlaybackContext.ContentPlaybackContext(
                                            signatureTimestamp = it,
                                            referer = "https://www.youtube.com/watch?v=$videoId",
                                            vis = 0,
                                            splay = false,
                                            lactMilliseconds = "-1",
                                            html5Preference = "HTML5_PREF_WANTS",
                                        )
                                    },
                                reloadPlaybackContext =
                                    reloadToken?.let {
                                        PlayerBody.PlaybackContext.ReloadPlaybackContext(
                                            PlayerBody.PlaybackContext.ReloadPlaybackContext.ReloadPlaybackParams(it),
                                        )
                                    },
                            )
                        } else {
                            null
                        },
                    serviceIntegrityDimensions = poToken?.let { PlayerBody.ServiceIntegrityDimensions(it) },
                    contentCheckOk = true,
                    racyCheckOk = true,
                    cpn = cpn,
                ),
            )
        }
    }

    suspend fun registerPlayback(
        url: String,
        cpn: String,
        playlistId: String?,
        client: YouTubeClient = YouTubeClient.WEB_REMIX,
    ) = httpClient.get(url) {
        ytClient(client, true)
        parameter("ver", "2")
        parameter("c", client.clientName)
        parameter("cpn", cpn)

        if (playlistId != null) {
            parameter("list", playlistId)
            parameter("referrer", "https://music.youtube.com/playlist?list=$playlistId")
        }
    }

    /**
     * The official client's watch-history beacon (YouTube.js MediaInfo.addToWatchHistory):
     * one GET to the player response's videostatsPlaybackUrl, s. host swapped for www.,
     * with the playback nonce and the fmt/rtn/rt params. This is what makes a watch
     * land in the account's server history; the video player never sent it, so the
     * server never learned of app watches.
     */
    /**
     * Rebuilds a stats URL with the given params SET, not appended — exactly
     * like the official client's (YouTube.js Actions.stats uses
     * URL.searchParams.set). Ktor's parameter() appends, so a tracking URL
     * that already carries cpn/ver/c would go out with each of them twice,
     * and the server may honour the wrong copy. The URL's own cpn is the
     * server-issued session nonce, so it is kept whenever present and the
     * fallback is only used when the URL carries none (0.3.48–0.3.51 all
     * answered 204 with an appended random cpn and recorded nothing).
     */
    private fun statsUrl(
        url: String,
        client: YouTubeClient,
        params: Map<String, String>,
        cpnFallback: String,
    ): String {
        val queryIndex = url.indexOf('?')
        val base = if (queryIndex >= 0) url.substring(0, queryIndex) else url
        val query = LinkedHashMap<String, String>()
        if (queryIndex >= 0) {
            url.substring(queryIndex + 1).split('&').forEach { pair ->
                val equals = pair.indexOf('=')
                if (equals > 0) query.putIfAbsent(pair.substring(0, equals), pair.substring(equals + 1))
            }
        }
        query["ver"] = "2"
        query["c"] = client.clientName.lowercase()
        query["cbrver"] = client.clientVersion
        query["cver"] = client.clientVersion
        params.forEach { (key, value) -> query[key] = value }
        if (!query.containsKey("cpn")) query["cpn"] = cpnFallback
        return base + "?" + query.entries.joinToString("&") { "${it.key}=${it.value}" }
    }

    suspend fun statsPlayback(
        url: String,
        cpn: String,
        client: YouTubeClient,
    ) = httpClient.get(
        statsUrl(url, client, mapOf("fmt" to "251", "rtn" to "0", "rt" to "0"), cpn),
    ) {
        // The stats host is www.youtube.com (after the s. replacement), so the
        // request must carry that origin: with apiUrl=null ytClient computes
        // the SAPISIDHASH for music.youtube.com, which www.youtube.com cannot
        // validate — the ping then goes out effectively unauthenticated and
        // YouTube answers 204 without recording anything (0.3.48–0.3.50).
        ytClient(client, true, apiUrl = YouTubeClient.API_URL_YOUTUBE)
    }

    /** The progress beacon: same session cpn as the playback beacon, position in
     * seconds with three decimals in st, et and cmt alike, always final=1. */
    suspend fun statsWatchtime(
        url: String,
        cpn: String,
        positionSec: Double,
        client: YouTubeClient,
    ) = httpClient.get(
        statsUrl(
            url,
            client,
            mapOf(
                "st" to "%.3f".format(Locale.US, positionSec),
                "et" to "%.3f".format(Locale.US, positionSec),
                "cmt" to "%.3f".format(Locale.US, positionSec),
                "final" to "1",
            ),
            cpn,
        ),
    ) {
        // Same www origin as the playback beacon (see above).
        ytClient(client, true, apiUrl = YouTubeClient.API_URL_YOUTUBE)
    }

    suspend fun browse(
        client: YouTubeClient,
        browseId: String? = null,
        params: String? = null,
        continuation: String? = null,
        setLogin: Boolean = false,
        formData: BrowseBody.FormData? = null,
    ) = withRetry {
        httpClient.post("browse") {
            ytClient(client, setLogin = setLogin || useLoginForBrowse)
            // Same brand-page rule as the main-site browse: X-Goog-Pageid from
            // accounts_list, never AuthUser: 0 (the owner slot, not the brand).
            if (cookie.isNullOrBlank() && oauthAccessToken != null &&
                (setLogin || useLoginForBrowse) && client.loginSupported
            ) {
                pageIdToken?.takeIf { it.isNotBlank() }?.let { header("X-Goog-Pageid", it) }
            }
            setBody(
                BrowseBody(
                    context =
                        client.toContext(
                            locale,
                            visitorData,
                            if (setLogin || useLoginForBrowse) dataSyncId else null,
                        ),
                    browseId = browseId,
                    params = params,
                    continuation = continuation,
                    formData = formData,
                ),
            )
        }
    }

    /**
     * A browse against youtube.com itself, with the session attached when [setLogin]:
     * the plain [browse] above rides the YouTube Music host (this engine's home), which
     * does not serve the main feeds (FEwhat_to_watch, FEsubscriptions, FEhistory) at all.
     * The auth headers come from the same [ytClient] builder the music calls use, with
     * the main site as its origin.
     */
    suspend fun browseMainSite(
        client: YouTubeClient,
        browseId: String? = null,
        params: String? = null,
        continuation: String? = null,
        setLogin: Boolean = false,
        canonicalBaseUrl: String? = null,
    ) = withRetry {
        httpClient.post("https://www.youtube.com/youtubei/v1/browse") {
            ytClient(client, setLogin = setLogin || useLoginForBrowse, apiUrl = YouTubeClient.API_URL_YOUTUBE)
            // A brand channel's identity rides X-Goog-Pageid, learned from
            // accounts_list — AuthUser: 0 points at the owner slot instead,
            // and a Bearer alone selects no brand channel at all.
            if (cookie.isNullOrBlank() && oauthAccessToken != null &&
                (setLogin || useLoginForBrowse) && client.loginSupported
            ) {
                pageIdToken?.takeIf { it.isNotBlank() }?.let { header("X-Goog-Pageid", it) }
            }
            setBody(
                BrowseBody(
                    context =
                        client.toContext(
                            locale,
                            visitorData,
                            if (setLogin || useLoginForBrowse) dataSyncId else null,
                        ),
                    browseId = browseId,
                    params = params,
                    continuation = continuation,
                    canonicalBaseUrl = canonicalBaseUrl,
                ),
            )
        }
    }

    /**
     * The account's guide. Its subscriptions section lists every channel the account
     * follows — the complete membership list the subscription feed only ever samples.
     */
    suspend fun guide() = withRetry {
        // Same client rule as browse: an OAuth (TV-code) token is a TV client's token, and
        // YouTube rejects it in a WEB context with 400 — the guide must speak TVHTML5 too.
        val client =
            if (cookie.isNullOrBlank() && oauthAccessToken != null) {
                YouTubeClient.TV_FIRETV
            } else {
                YouTubeClient.WEB.copy(loginSupported = true)
            }
        httpClient.post("https://www.youtube.com/youtubei/v1/guide") {
            ytClient(client, setLogin = true, apiUrl = YouTubeClient.API_URL_YOUTUBE)
            // Same brand-page rule as browse: X-Goog-Pageid from accounts_list.
            if (cookie.isNullOrBlank() && oauthAccessToken != null) {
                pageIdToken?.takeIf { it.isNotBlank() }?.let { header("X-Goog-Pageid", it) }
            }
            setBody(
                GuideBody(
                    context = client.toContext(locale, visitorData, dataSyncId),
                ),
            )
        }
    }

    suspend fun reel(
        client: YouTubeClient,
        sequenceParams: String,
    ) = mainSitePost(client, "reel/reel_watch_sequence") { requestVisitorData ->
        ReelBody(
            context = client.toContext(locale, requestVisitorData, null),
            sequenceParams = sequenceParams,
        )
    }

    /**
     * One reel's overlay. Only the id is required: the WEB overlay is complete without a signature
     * timestamp or the reel's own `playerParams`, which matter only to the inline player response
     * this never asks for.
     */
    suspend fun reelItemWatch(
        client: YouTubeClient,
        videoId: String,
        playerParams: String? = null,
        disablePlayerResponse: Boolean = true,
    ) = mainSitePost(client, "reel/reel_item_watch") { requestVisitorData ->
        ReelItemWatchBody(
            context = client.toContext(locale, requestVisitorData, null),
            playerRequest = ReelItemWatchBody.PlayerRequest(videoId = videoId, params = playerParams),
            disablePlayerResponse = disablePlayerResponse,
        )
    }

    suspend fun next(
        client: YouTubeClient,
        videoId: String?,
        playlistId: String?,
        playlistSetVideoId: String?,
        index: Int?,
        params: String?,
        continuation: String? = null,
    ) = withRetry {
        httpClient.post("next") {
            ytClient(client, setLogin = true)
            setBody(
                NextBody(
                    context = client.toContext(locale, visitorData, dataSyncId),
                    videoId = videoId,
                    playlistId = playlistId,
                    playlistSetVideoId = playlistSetVideoId,
                    index = index,
                    params = params,
                    continuation = continuation,
                ),
            )
        }
    }

    private fun HttpRequestBuilder.webYouTubeHeaders(client: YouTubeClient) {
        headers {
            append("X-Goog-Api-Format-Version", "1")
            append("X-YouTube-Client-Name", client.clientId)
            append("X-YouTube-Client-Version", client.clientVersion)
            append("X-Origin", "https://www.youtube.com")
            append("Referer", "https://www.youtube.com/")
            visitorData?.let { append("X-Goog-Visitor-Id", it) }
        }
        contentType(ContentType.Application.Json)
        userAgent(client.userAgent)
        parameter("prettyPrint", false)
    }

    suspend fun nextForLiveChat(videoId: String) =
        withRetry {
            val client = YouTubeClient.WEB
            httpClient.post("https://www.youtube.com/youtubei/v1/next") {
                webYouTubeHeaders(client)
                setBody(
                    NextBody(
                        context = client.toContext(locale, visitorData, null),
                        videoId = videoId,
                        playlistId = null,
                        playlistSetVideoId = null,
                        index = null,
                        params = null,
                        continuation = null,
                    ),
                )
            }
        }

    /**
     * The watch page for [videoId] or one of its continuations, on the main site.
     *
     * The comment section only exists on the web watch response, so this posts to the same host
     * and headers as [nextForLiveChat] rather than the music API the default request points at.
     */
    suspend fun nextWatch(
        videoId: String? = null,
        continuation: String? = null,
    ) = withRetry {
        val client = YouTubeClient.WEB
        httpClient.post("https://www.youtube.com/youtubei/v1/next") {
            webYouTubeHeaders(client)
            setBody(
                NextBody(
                    context = client.toContext(locale, visitorData, null),
                    videoId = videoId,
                    playlistId = null,
                    playlistSetVideoId = null,
                    index = null,
                    params = null,
                    continuation = continuation,
                ),
            )
        }
    }

    /**
     * The same `next` call with the account attached. The guest call's comment section has no
     * create box; only the logged-in section carries the params a comment post is made with.
     */
    suspend fun nextWatchAuthed(
        videoId: String? = null,
        continuation: String? = null,
    ) = withRetry {
        // Same client rule as browse/guide: an OAuth (TV-code) token is a TV client's
        // token; in a WEB context YouTube refuses it. Cookie sessions keep WEB.
        val client =
            if (cookie.isNullOrBlank() && oauthAccessToken != null) {
                YouTubeClient.TV_FIRETV
            } else {
                YouTubeClient.WEB.copy(loginSupported = true)
            }
        httpClient.post("https://www.youtube.com/youtubei/v1/next") {
            ytClient(client, setLogin = true, apiUrl = YouTubeClient.API_URL_YOUTUBE)
            // Same brand-page rule as browse: X-Goog-Pageid from accounts_list.
            if (cookie.isNullOrBlank() && oauthAccessToken != null) {
                pageIdToken?.takeIf { it.isNotBlank() }?.let { header("X-Goog-Pageid", it) }
            }
            setBody(
                NextBody(
                    context = client.toContext(locale, visitorData, dataSyncId),
                    videoId = videoId,
                    playlistId = null,
                    playlistSetVideoId = null,
                    index = null,
                    params = null,
                    continuation = continuation,
                ),
            )
        }
    }

    /** Posts a top-level comment; [createCommentParams] is the token the section's create box carried. */
    suspend fun createComment(
        createCommentParams: String,
        commentText: String,
    ): HttpResponse {
        // What the request actually carries, as facts only — the create has answered a bare
        // 404 to a genuine box token from the same session, and every static property of
        // this request matches the working ones. These are the values left to check.
        ShivLog.i("commentCreateReq tokenLen=${createCommentParams.length} " +
                "sapisidExact=${"SAPISID" in cookieMap} " +
                "sapisidAny=${listOf("SAPISID", "__Secure-3PSAPISID", "__Secure-1PSAPISID").any { it in cookieMap }} " +
                "datasync=${dataSyncId != null} visitor=${!visitorData.isNullOrBlank()}",
        )
        val first = runCatching { postCreateComment(createCommentParams, commentText, authUser = false) }
        if (first.isSuccess) return first.getOrThrow()
        val firstError = first.exceptionOrNull()
        val firstStatus = (firstError as? ClientRequestException)?.response?.status
        ShivLog.i("commentCreate attempt=1 failed status=$firstStatus")
        if (firstStatus?.value != 404) throw firstError ?: IllegalStateException("comment create failed")
        // A delegated (brand) channel's box token is bound to the account-slot identity the
        // web sends as X-Goog-AuthUser; without it YouTube cannot resolve the token and
        // answers the same bare 404. Retry once carrying it.
        ShivLog.i("commentCreate attempt=2 with X-Goog-AuthUser")
        return postCreateComment(createCommentParams, commentText, authUser = true)
    }

    private suspend fun postCreateComment(
        createCommentParams: String,
        commentText: String,
        authUser: Boolean,
    ): HttpResponse {
        // Same client rule as nextWatchAuthed: a TV-code (OAuth) token is a TV
        // client's token, and in a WEB context YouTube refuses it — so comment
        // posts from a TV-code session ride TVHTML5. Cookie sessions keep WEB.
        val client =
            if (cookie.isNullOrBlank() && oauthAccessToken != null) {
                YouTubeClient.TV_AUTH
            } else {
                YouTubeClient.WEB.copy(loginSupported = true)
            }
        // The endpoint is comment/create_comment — every working InnerTube client
        // (YouTube.js, SmartTube's MediaServiceCore, OpenCLI) posts there. The old
        // comment/create path is an unregistered route: it answered a bare 404 with an
        // empty body to every request, whatever token, headers or identity it carried
        // (the long comment-post hunt of 0.3.1–0.3.10 was fought against that 404).
        return httpClient.post("https://www.youtube.com/youtubei/v1/comment/create_comment") {
            ytClient(client, setLogin = true, apiUrl = YouTubeClient.API_URL_YOUTUBE)
            if (authUser) header("X-Goog-AuthUser", "0")
            setBody(
                CreateCommentBody(
                    context = client.toContext(locale, visitorData, dataSyncId),
                    createCommentParams = createCommentParams,
                    commentText = commentText,
                ),
            )
        }
    }

    /**
     * Runs one of a comment's own toolbar commands (like/dislike and their undos).
     * [endpointJson] is the command's `performCommentActionEndpoint` object, replayed
     * verbatim: it carries either an `actions` list or a single encoded `action`.
     */
    suspend fun performCommentAction(endpointJson: String): HttpResponse {
        val endpoint =
            Json.parseToJsonElement(endpointJson) as? JsonObject
                ?: throw IllegalArgumentException("comment action endpoint is not an object")
        val actions: List<JsonElement> =
            (endpoint["actions"] as? JsonArray)?.toList()
                ?: endpoint["action"]?.let { listOf(it) }
                ?: throw IllegalArgumentException("comment action endpoint carries no actions")
        ShivLog.i("commentActionReq actions=${actions.size}")
        val first = runCatching { postCommentAction(actions, authUser = false) }
        if (first.isSuccess) return first.getOrThrow()
        val firstError = first.exceptionOrNull()
        val firstStatus = (firstError as? ClientRequestException)?.response?.status
        ShivLog.i("commentAction attempt=1 failed status=$firstStatus")
        if (firstStatus?.value != 404) throw firstError ?: IllegalStateException("comment action failed")
        // Same delegated-channel identity case as comment create: retry carrying it.
        ShivLog.i("commentAction attempt=2 with X-Goog-AuthUser")
        return postCommentAction(actions, authUser = true)
    }

    private suspend fun postCommentAction(
        actions: List<JsonElement>,
        authUser: Boolean,
    ): HttpResponse {
        val client = YouTubeClient.WEB.copy(loginSupported = true)
        return httpClient.post("https://www.youtube.com/youtubei/v1/comment/perform_comment_action") {
            ytClient(client, setLogin = true, apiUrl = YouTubeClient.API_URL_YOUTUBE)
            if (authUser) header("X-Goog-AuthUser", "0")
            setBody(
                PerformCommentActionBody(
                    context = client.toContext(locale, visitorData, dataSyncId),
                    actions = actions,
                ),
            )
        }
    }

    /**
     * One server-issued comment write whose body is a params token plus the text under
     * caller-chosen field names: a reply (`createReplyParams`/`commentText` to
     * `comment/create_comment_reply`), an edit (`updateCommentParams`/`commentText`), or a
     * reply's edit (`updateReplyParams`/`replyText`). Same delegated-channel 404 ladder as
     * the create and the vote: the token is bound to the account-slot identity.
     */
    suspend fun postCommentWrite(
        path: String,
        paramsField: String,
        params: String,
        textField: String,
        text: String,
    ): HttpResponse {
        ShivLog.i("commentWrite path=$path field=$paramsField tok=${params.take(8)} len=${params.length} attempt=1 start")
        val first = runCatching { postCommentWriteAttempt(path, paramsField, params, textField, text, authUser = false) }
        if (first.isSuccess) return first.getOrThrow()
        val firstError = first.exceptionOrNull()
        val firstStatus = (firstError as? ClientRequestException)?.response?.status
        ShivLog.i("commentWrite path=$path attempt=1 failed status=$firstStatus")
        if (firstStatus?.value != 404) throw firstError ?: IllegalStateException("comment write failed")
        ShivLog.i("commentWrite path=$path attempt=2 with X-Goog-AuthUser")
        return postCommentWriteAttempt(path, paramsField, params, textField, text, authUser = true)
    }

    private suspend fun postCommentWriteAttempt(
        path: String,
        paramsField: String,
        params: String,
        textField: String,
        text: String,
        authUser: Boolean,
    ): HttpResponse {
        val client = YouTubeClient.WEB.copy(loginSupported = true)
        // Typed bodies, like the proven create: the client's JSON encodes the context with
        // its defaults on. A hand-built JSON body dropped the context's defaulted parts and
        // the write endpoints refused the shape (the 0.3.16 reply/edit failures).
        val context = client.toContext(locale, visitorData, dataSyncId)
        val body: Any =
            when (paramsField) {
                "createReplyParams" -> CreateReplyBody(context, params, text)
                "updateCommentParams" -> UpdateCommentBody(context, params, text)
                "updateReplyParams" -> UpdateReplyBody(context, params, text)
                else -> throw IllegalArgumentException("unknown comment write field: $paramsField")
            }
        return httpClient.post("https://www.youtube.com/youtubei/v1/$path") {
            ytClient(client, setLogin = true, apiUrl = YouTubeClient.API_URL_YOUTUBE)
            if (authUser) header("X-Goog-AuthUser", "0")
            setBody(body)
        }
    }

    suspend fun getLiveChat(
        continuation: String,
        offsetMs: Long? = null,
    ) = withRetry {
        val client = YouTubeClient.WEB
        httpClient.post("https://www.youtube.com/youtubei/v1/live_chat/get_live_chat") {
            webYouTubeHeaders(client)
            setBody(
                GetLiveChatBody(
                    context = client.toContext(locale, visitorData, null),
                    continuation = continuation,
                    currentPlayerState =
                        offsetMs?.let {
                            GetLiveChatBody.CurrentPlayerState(playerOffsetMs = it.toString())
                        },
                ),
            )
        }
    }

    /**
     * One live-chat message, per YouTube.js `LiveChat.sendMessage`: POST
     * `live_chat/send_message` with the proto-encoded video/channel params. Same WEB+login
     * write path as the proven comment writes. The response carries `addChatItemAction` on
     * success and sometimes `runAttestationCommand` (a bot challenge) instead of the message.
     */
    suspend fun sendLiveChatMessage(
        videoId: String,
        channelId: String,
        text: String,
    ): HttpResponse {
        val client = YouTubeClient.WEB.copy(loginSupported = true)
        val context = client.toContext(locale, visitorData, dataSyncId)
        val body =
            LiveChatSendBody(
                context = context,
                richMessage = LiveChatSendBody.RichMessage(listOf(LiveChatSendBody.TextSegment(text))),
                clientMessageId = UUID.randomUUID().toString(),
                params = liveChatSendParams(videoId, channelId),
            )
        ShivLog.i("liveChatSend video=${videoId.take(8)} start")
        return httpClient.post("https://www.youtube.com/youtubei/v1/live_chat/send_message") {
            ytClient(client, setLogin = true, apiUrl = YouTubeClient.API_URL_YOUTUBE)
            setBody(body)
        }
    }

    /**
     * POSTs one InnerTube endpoint whose body is [fields] plus the signed-in WEB context.
     * The generic replay for menu endpoints (the live-chat item menu, then its DELETE
     * action): the endpoint object itself carries the fields, so no typed body exists for it.
     */
    suspend fun postInnertubeEndpoint(
        apiUrl: String,
        fields: JsonObject,
    ): HttpResponse {
        val client = YouTubeClient.WEB.copy(loginSupported = true)
        // commandMetadata apiUrl values arrive as "/youtubei/v1/<endpoint>";
        // prefixing those again would double the path and 404 every call.
        val path = apiUrl.removePrefix("/").removePrefix("youtubei/v1/")
        val url =
            if (apiUrl.startsWith("http")) apiUrl
            else "https://www.youtube.com/youtubei/v1/$path"
        val body =
            buildJsonObject {
                put("context", json.encodeToJsonElement(client.toContext(locale, visitorData, dataSyncId)))
                fields.forEach { (key, value) -> put(key, value) }
            }
        return httpClient.post(url) {
            ytClient(client, setLogin = true, apiUrl = YouTubeClient.API_URL_YOUTUBE)
            setBody(body)
        }
    }

    suspend fun getSearchSuggestions(
        client: YouTubeClient,
        input: String,
    ) = withRetry {
        httpClient.post("music/get_search_suggestions") {
            ytClient(client)
            setBody(
                GetSearchSuggestionsBody(
                    context = client.toContext(locale, visitorData, null),
                    input = input,
                ),
            )
        }
    }

    suspend fun getQueue(
        client: YouTubeClient,
        videoIds: List<String>?,
        playlistId: String?,
    ) = withRetry {
        httpClient.post("music/get_queue") {
            ytClient(client)
            setBody(
                GetQueueBody(
                    context = client.toContext(locale, visitorData, null),
                    videoIds = videoIds,
                    playlistId = playlistId,
                ),
            )
        }
    }

    suspend fun getTranscript(
        client: YouTubeClient,
        videoId: String,
    ) = httpClient.post("https://music.youtube.com/youtubei/v1/get_transcript") {
        parameter("key", "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX3")
        headers {
            append("Content-Type", "application/json")
        }
        setBody(
            GetTranscriptBody(
                context = client.toContext(locale, null, null),
                params = Base64.Default.encode("\n${11.toChar()}$videoId".toByteArray()),
            ),
        )
    }

    suspend fun getSwJsData() = httpClient.get("https://music.youtube.com/sw.js_data")

    suspend fun accountMenu(client: YouTubeClient) =
        httpClient.post("account/account_menu") {
            ytClient(client, setLogin = true)
            // A brand channel's identity rides X-Goog-Pageid, learned from
            // accounts_list — AuthUser: 0 points at the owner slot instead,
            // and a Bearer alone selects no brand channel at all.
            if (cookie.isNullOrBlank() && oauthAccessToken != null) {
                pageIdToken?.takeIf { it.isNotBlank() }?.let { header("X-Goog-Pageid", it) }
            }
            setBody(AccountMenuBody(client.toContext(locale, visitorData, dataSyncId)))
        }

    /**
     * The TV identity endpoint (SmartTube's accounts_list): the account menu's
     * shape never parses on the TV client, but this returns the account name
     * and, for brand channels, the page id token authed browses must carry.
     * OAuth sessions only; a plain account simply has no page id token.
     */
    suspend fun accountsList(): OAuthIdentity {
        if (!cookie.isNullOrBlank() || oauthAccessToken == null) {
            ShivLog.i("accountsList skipped cookie=${!cookie.isNullOrBlank()} token=${oauthAccessToken != null}")
            error("accounts_list needs an OAuth session")
        }
        ShivLog.i("accountsList start")
        val response =
            httpClient.post("https://www.youtube.com/youtubei/v1/account/accounts_list") {
                ytClient(YouTubeClient.TV_FIRETV, setLogin = true, apiUrl = YouTubeClient.API_URL_YOUTUBE)
                setBody(
                    AccountsListBody(
                        context = YouTubeClient.TV_FIRETV.toContext(locale, visitorData, null),
                        accountReadMask =
                            AccountsListBody.AccountReadMask(
                                returnOwner = true,
                                returnBrandAccounts = true,
                                returnPersonaAccounts = false,
                            ),
                    ),
                )
            }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            ShivLog.i("accountsList FAILED status=${response.status.value} body=${text.take(200)}")
            error("accounts_list ${response.status.value}")
        }
        val root = Json.parseToJsonElement(text).jsonObject
        val items = root.accountsItems()
        val selected =
            items.firstOrNull { it["isSelected"]?.jsonPrimitive?.booleanOrNull == true }
                ?: items.firstOrNull()
        val item = selected ?: run {
            ShivLog.i("accountsList empty items=${items.size} topKeys=${root.keys} selectText=${root["selectText"]?.jsonObject.readText()}")
            error("accounts_list empty")
        }
        val identity =
            OAuthIdentity(
                name = item["accountName"]?.jsonObject.readText(),
                email = item["accountByline"]?.jsonObject.readText(),
                channelHandle = item["channelHandle"]?.jsonObject.readText(),
                pageIdToken =
                    item["serviceEndpoint"]?.jsonObject
                        ?.get("selectActiveIdentityEndpoint")?.jsonObject
                        ?.get("supportedTokens")?.jsonArray
                        ?.firstNotNullOfOrNull { token ->
                            token.jsonObject["pageIdToken"]?.jsonObject
                                ?.get("pageId")?.jsonPrimitive?.contentOrNull
                        },
            )
        ShivLog.i("accountsList name=${identity.name != null} pageId=${identity.pageIdToken != null} items=${items.size}")
        return identity
    }

    private fun JsonObject.accountsItems(): List<JsonObject> {
        val contents = get("contents")?.jsonArray
        val section =
            contents?.firstOrNull()?.jsonObject
                ?.get("accountSectionListRenderer")?.jsonObject
                ?.get("contents")?.jsonArray?.firstOrNull()?.jsonObject
        val exact = section?.get("accountItemSectionRenderer")?.jsonObject?.get("contents")?.jsonArray
            ?.mapNotNull { it.jsonObject["accountItem"]?.jsonObject }
            .orEmpty()
        if (exact.isNotEmpty()) return exact
        return deepAccountItems()
    }

    private fun JsonElement.deepAccountItems(acc: MutableList<JsonObject> = mutableListOf()): List<JsonObject> {
        when (this) {
            is JsonObject -> {
                get("accountItem")?.jsonObject?.let { acc += it }
                values.forEach { it.deepAccountItems(acc) }
            }
            is JsonArray -> forEach { it.deepAccountItems(acc) }
            else -> {}
        }
        return acc
    }

    private fun JsonObject?.readText(): String? {
        if (this == null) return null
        get("simpleText")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
        return get("runs")?.jsonArray
            ?.joinToString("") { run -> run.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            ?.takeIf { it.isNotBlank() }
    }

    suspend fun likeVideo(
        client: YouTubeClient,
        videoId: String,
    ) = httpClient.post("like/like") {
        ytClient(client, setLogin = true)
        setBody(
            LikeBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                target = LikeBody.Target.VideoTarget(videoId),
            ),
        )
    }

    /**
     * Replays a post like toggle's own endpoint, verbatim like youtubei.js's LikeEndpoint:
     * the status picks the path (DISLIKE to `like/dislike`, INDIFFERENT to `like/removelike`,
     * anything else to `like/like`) and the target rides through untouched.
     */
    private fun findLikeEndpoint(element: JsonElement): JsonObject? {
        if (element is JsonArray) {
            for (value in element) {
                findLikeEndpoint(value)?.let { return it }
            }
            return null
        }
        if (element !is JsonObject) return null
        element["likeEndpoint"]?.jsonObject?.let { return it }
        if (element.containsKey("status") &&
            (element.containsKey("target") || element.containsKey("likeParams") ||
                element.containsKey("dislikeParams") || element.containsKey("removeLikeParams"))
        ) {
            return element
        }
        for ((_, value) in element) {
            findLikeEndpoint(value)?.let { return it }
        }
        return null
    }

    /**
     * A post like replays the endpoint YouTube itself sent, verbatim: its target
     * object goes through unchanged and the status picks the params
     * (likeParams/dislikeParams/removeLikeParams), exactly like youtubei.js
     * LikeEndpoint.buildRequest. Rebuilding the target as { postId } was wrong:
     * the device proved the real endpoint carries no postId in its target, so
     * every tap died before any request left the phone.
     *
     * Posts are main-site entities, so the like goes to www.youtube.com like
     * every other working write (comment create) — the client's default host is
     * music.youtube.com, and the music like endpoint answers a post target with
     * 400 INVALID_ARGUMENT.
     *
     * The stored service endpoint may wrap the like endpoint (command executor,
     * nested commands), so it is located by deep search, not just at the top
     * level — a top-level-only read silently sent a context-only body before.
     */
    suspend fun postLike(endpointJson: String): HttpResponse {
        val root =
            Json.parseToJsonElement(endpointJson) as? JsonObject
                ?: throw IllegalArgumentException("post like endpoint is not an object")
        val endpoint =
            findLikeEndpoint(root)
                ?: throw IllegalArgumentException("post like endpoint has no likeEndpoint")
        val status = endpoint["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val target = endpoint["target"]?.jsonObject
        val params =
            when (status) {
                "DISLIKE" -> endpoint["dislikeParams"]
                "INDIFFERENT" -> endpoint["removeLikeParams"]
                else -> endpoint["likeParams"]
            }?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        // Keys only, never values: the shape of the endpoint is the diagnostic
        // that decides the next fix, and tokens must never land in a report.
        ShivLog.i(
            "postLike keys=${root.keys} likeKeys=${endpoint.keys} " +
                "targetKeys=${target?.keys} hasParams=${params != null} status=$status",
        )
        val path =
            when (status) {
                "DISLIKE" -> "like/dislike"
                "INDIFFERENT" -> "like/removelike"
                else -> "like/like"
            }
        val client = YouTubeClient.WEB.copy(loginSupported = true)
        val body =
            buildJsonObject {
                put("context", json.encodeToJsonElement(client.toContext(locale, visitorData, dataSyncId)))
                target?.let { put("target", it) }
                params?.let { put("params", it) }
            }
        return httpClient.post("https://www.youtube.com/youtubei/v1/$path") {
            ytClient(client, setLogin = true, apiUrl = YouTubeClient.API_URL_YOUTUBE)
            setBody(body)
        }
    }

    suspend fun unlikeVideo(
        client: YouTubeClient,
        videoId: String,
    ) = httpClient.post("like/removelike") {
        ytClient(client, setLogin = true)
        setBody(
            LikeBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                target = LikeBody.Target.VideoTarget(videoId),
            ),
        )
    }

    suspend fun subscribeChannel(
        client: YouTubeClient,
        channelId: String,
    ) = httpClient.post("subscription/subscribe") {
        ytClient(client, setLogin = true)
        setBody(
            SubscribeBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                channelIds = listOf(channelId),
            ),
        )
    }

    suspend fun unsubscribeChannel(
        client: YouTubeClient,
        channelId: String,
    ) = httpClient.post("subscription/unsubscribe") {
        ytClient(client, setLogin = true)
        setBody(
            SubscribeBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                channelIds = listOf(channelId),
            ),
        )
    }

    suspend fun likePlaylist(
        client: YouTubeClient,
        playlistId: String,
    ) = httpClient.post("like/like") {
        ytClient(client, setLogin = true)
        setBody(
            LikeBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                target = LikeBody.Target.PlaylistTarget(playlistId),
            ),
        )
    }

    suspend fun unlikePlaylist(
        client: YouTubeClient,
        playlistId: String,
    ) = httpClient.post("like/removelike") {
        ytClient(client, setLogin = true)
        setBody(
            LikeBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                target = LikeBody.Target.PlaylistTarget(playlistId),
            ),
        )
    }

    suspend fun addToPlaylist(
        client: YouTubeClient,
        playlistId: String,
        videoId: String,
    ) = httpClient.post("browse/edit_playlist") {
        ytClient(client, setLogin = true)
        setBody(
            EditPlaylistBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                playlistId = playlistId.removePrefix("VL"),
                actions =
                    listOf(
                        Action.AddVideoAction(addedVideoId = videoId),
                    ),
            ),
        )
    }

    suspend fun addPlaylistToPlaylist(
        client: YouTubeClient,
        playlistId: String,
        addPlaylistId: String,
    ) = httpClient.post("browse/edit_playlist") {
        ytClient(client, setLogin = true)
        setBody(
            EditPlaylistBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                playlistId = playlistId.removePrefix("VL"),
                actions =
                    listOf(
                        Action.AddPlaylistAction(addedFullListId = addPlaylistId),
                    ),
            ),
        )
    }

    suspend fun removeFromPlaylist(
        client: YouTubeClient,
        playlistId: String,
        videoId: String,
        setVideoId: String,
    ) = httpClient.post("browse/edit_playlist") {
        ytClient(client, setLogin = true)
        setBody(
            EditPlaylistBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                playlistId = playlistId.removePrefix("VL"),
                actions =
                    listOf(
                        Action.RemoveVideoAction(
                            removedVideoId = videoId,
                            setVideoId = setVideoId,
                        ),
                    ),
            ),
        )
    }

    suspend fun moveSongPlaylist(
        client: YouTubeClient,
        playlistId: String,
        setVideoId: String,
        successorSetVideoId: String?,
    ) = httpClient.post("browse/edit_playlist") {
        ytClient(client, setLogin = true)
        setBody(
            EditPlaylistBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                playlistId = playlistId,
                actions =
                    listOf(
                        Action.MoveVideoAction(
                            movedSetVideoIdSuccessor = successorSetVideoId,
                            setVideoId = setVideoId,
                        ),
                    ),
            ),
        )
    }

    suspend fun createPlaylist(
        client: YouTubeClient,
        title: String,
    ) = httpClient.post("playlist/create") {
        ytClient(client, true)
        setBody(
            CreatePlaylistBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                title = title,
            ),
        )
    }

    suspend fun renamePlaylist(
        client: YouTubeClient,
        playlistId: String,
        name: String,
    ) = httpClient.post("browse/edit_playlist") {
        ytClient(client, setLogin = true)
        setBody(
            EditPlaylistBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                playlistId = playlistId,
                actions =
                    listOf(
                        Action.RenamePlaylistAction(
                            playlistName = name,
                        ),
                    ),
            ),
        )
    }

    suspend fun getUploadCustomThumbnailLink(
        client: YouTubeClient,
        contentLength: Int,
    ) = httpClient.post("https://music.youtube.com/playlist_image_upload/playlist_custom_thumbnail") {
        ytClient(client, setLogin = true)
        headers {
            append("X-Goog-Upload-Command", "start")
            append("X-Goog-Upload-Protocol", "resumable")
            append("X-Goog-Upload-Header-Content-Length", contentLength.toString())
        }
    }

    suspend fun uploadCustomThumbnail(
        client: YouTubeClient,
        uploadId: String,
        image: ByteArray,
    ) = httpClient.post("https://music.youtube.com/playlist_image_upload/playlist_custom_thumbnail") {
        ytClient(client, setLogin = true)
        parameter("upload_id", uploadId)
        parameter("upload_protocol", "resumable")
        headers {
            append("X-Goog-Upload-Command", "upload, finalize")
            append("X-Goog-Upload-Offset", "0")
        }
        setBody(image)
    }

    suspend fun setThumbnailPlaylist(
        client: YouTubeClient,
        playlistId: String,
        blobId: String,
    ) = httpClient.post("browse/edit_playlist") {
        ytClient(client, setLogin = true)
        setBody(
            EditPlaylistBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                playlistId = playlistId,
                actions =
                    listOf(
                        Action.SetCustomThumbnailAction(
                            addedCustomThumbnail =
                                Action.SetCustomThumbnailAction.AddedCustomThumbnail(
                                    playlistScottyEncryptedBlobId = blobId,
                                ),
                        ),
                    ),
            ),
        )
    }

    suspend fun removeThumbnailPlaylist(
        client: YouTubeClient,
        playlistId: String,
    ) = httpClient.post("browse/edit_playlist") {
        ytClient(client, setLogin = true)
        setBody(
            EditPlaylistBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                playlistId = playlistId,
                actions =
                    listOf(
                        Action.RemoveCustomThumbnailAction(),
                    ),
            ),
        )
    }

    suspend fun deletePlaylist(
        client: YouTubeClient,
        playlistId: String,
    ) = httpClient.post("playlist/delete") {
        println("deleting $playlistId")
        ytClient(client, setLogin = true)
        setBody(
            PlaylistDeleteBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                playlistId = playlistId,
            ),
        )
    }

    suspend fun returnYouTubeDislike(videoId: String) =
        httpClient.get("https://returnyoutubedislikeapi.com/Votes?videoId=$videoId") {
            contentType(ContentType.Application.Json)
        }

    suspend fun getMediaInfo(videoId: String): Result<MediaInfo> =
        runCatching {
            val response = next(client = YouTubeClient.WEB, videoId, null, null, null, null, null).body<NextResponse>()
            val playerResponse =
                player(
                    client = YouTubeClient.ANDROID,
                    videoId = videoId,
                    playlistId = null,
                    signatureTimestamp = null,
                ).body<PlayerResponse>()

            val baseForInfo =
                response.contents.twoColumnWatchNextResults
                    ?.results
                    ?.results
                    ?.content
                    ?.find {
                        it?.videoSecondaryInfoRenderer != null
                    }?.videoSecondaryInfoRenderer

            val baseForTitle =
                response.contents.twoColumnWatchNextResults
                    ?.results
                    ?.results
                    ?.content
                    ?.find {
                        it?.videoPrimaryInfoRenderer != null
                    }?.videoPrimaryInfoRenderer

            val rytdEnabled = PlayerPreferences(FlowApplication.appContext).rytdEnabled.first()
            val returnYouTubeDislikeResponse =
                if (rytdEnabled) {
                    returnYouTubeDislike(videoId).body<ReturnYouTubeDislikeResponse>()
                } else {
                    null
                }

            val bestAudio =
                playerResponse.streamingData
                    ?.adaptiveFormats
                    ?.filter { it.isAudio }
                    ?.maxByOrNull { it.bitrate }
                    ?: playerResponse.streamingData
                        ?.formats
                        ?.filter { it.isAudio }
                        ?.maxByOrNull { it.bitrate }

            return@runCatching MediaInfo(
                videoId = videoId,
                title =
                    baseForTitle
                        ?.title
                        ?.runs
                        ?.firstOrNull()
                        ?.text,
                author =
                    baseForInfo
                        ?.owner
                        ?.videoOwnerRenderer
                        ?.title
                        ?.runs
                        ?.firstOrNull()
                        ?.text,
                authorId =
                    baseForInfo
                        ?.owner
                        ?.videoOwnerRenderer
                        ?.navigationEndpoint
                        ?.browseEndpoint
                        ?.browseId,
                authorThumbnail =
                    baseForInfo
                        ?.owner
                        ?.videoOwnerRenderer
                        ?.thumbnail
                        ?.thumbnails
                        ?.find {
                            it.height == 48
                        }?.url
                        ?.replace("s48", "s960"),
                description = baseForInfo?.attributedDescription?.content,
                subscribers =
                    baseForInfo
                        ?.owner
                        ?.videoOwnerRenderer
                        ?.subscriberCountText
                        ?.simpleText
                        ?.split(" ")
                        ?.firstOrNull(),
                uploadDate = baseForTitle?.dateText?.simpleText,
                viewCount = returnYouTubeDislikeResponse?.viewCount,
                like = returnYouTubeDislikeResponse?.likes,
                dislike = returnYouTubeDislikeResponse?.dislikes,
                durationSeconds =
                    playerResponse.videoDetails?.lengthSeconds?.toIntOrNull()
                        ?: bestAudio
                            ?.approxDurationMs
                            ?.toLongOrNull()
                            ?.div(1000L)
                            ?.toInt(),
                mimeType = bestAudio?.mimeType,
                bitrate = bestAudio?.bitrate?.toLong(),
                sampleRate = bestAudio?.audioSampleRate,
                frameRate = bestAudio?.fps,
                width = bestAudio?.width,
                height = bestAudio?.height,
                contentLength = bestAudio?.contentLength.toString(),
                qualityLabel = bestAudio?.qualityLabel,
                videoId_tag = bestAudio?.itag,
            )
        }
}
