package io.github.shivytyahoo.glidetube.utils.cipher

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * This is based and ported from Metrolist,
 * see https://github.com/MetrolistGroup/Metrolist for the original code and license.
 *
 * Main cipher deobfuscation orchestrator for YouTube stream URLs.
 *
 * Handles both signature deobfuscation (for signatureCipher streams) and
 * n-parameter transformation (for throttle avoidance / 403 fix).
 */
object CipherDeobfuscator {
    private const val TAG = "Flow_CipherDeobfusc"

    /** Player versions whose extracted functions are kept in memory. */
    private const val MAX_CACHED_ANALYSES = 3

    lateinit var appContext: Context
        private set

    fun initialize(context: Context) {
        Log.d(TAG, "CipherDeobfuscator initializing...")
        appContext = context.applicationContext
        Log.d(TAG, "CipherDeobfuscator initialized")
    }

    private var cipherWebView: CipherWebView? = null
    private var currentPlayerHash: String? = null

    // Signature and n callers can arrive together (a song resolving during a video load); without
    // this each would build its own WebView around the same 3 MB script.
    private val webViewMutex = Mutex()

    @Volatile
    private var cachedSignatureTimestamp: Int? = null

    @Volatile
    private var cachedSignatureTimestampHash: String? = null

    @Volatile
    private var lastAnalyzedHash: String? = null

    @Volatile
    private var refetchedForHash: String? = null

    /**
     * The player script whose signature and n-function the patterns in [FunctionNameExtractor]
     * could not find, once a fresh copy has been tried. Non-null means YouTube shipped a player
     * shape this build does not know — the single most useful thing a bug report can carry, and
     * not something the logs otherwise name.
     */
    @Volatile
    var unparseablePlayerHash: String? = null
        private set

    // The extracted functions per player version, newest three: a player that rolls back to a
    // version seen recently rebuilds its WebView without re-running the extractors over the
    // 3 MB script. The script itself is cached on disk the same way (see PlayerJsFetcher).
    private val analysisCache =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, FunctionNameExtractor.PlayerAnalysis>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<String, FunctionNameExtractor.PlayerAnalysis>): Boolean =
                    size > MAX_CACHED_ANALYSES
            },
        )

    /**
     * The signature timestamp the WEB/MWEB `/player` request has to send. Cached after the first
     * read, per player version; the player script itself is cached by [PlayerJsFetcher].
     *
     * Reads the script and runs one regex rather than standing up the cipher WebView: the timestamp
     * is a plain number in the script and needs none of the machinery a decipher does. That also
     * makes it independent of signature extraction, which the current player defeats entirely —
     * the timestamp still resolves on players whose cipher cannot be read at all.
     */
    suspend fun ensureSignatureTimestamp(): Int? {
        val (playerJs, hash) = runCatching { PlayerJsFetcher.getPlayerJs() }.getOrNull() ?: return cachedSignatureTimestamp
        if (hash == cachedSignatureTimestampHash) return cachedSignatureTimestamp
        return try {
            FunctionNameExtractor.extractSignatureTimestamp(playerJs)?.also {
                cachedSignatureTimestamp = it
                cachedSignatureTimestampHash = hash
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "ensureSignatureTimestamp failed: ${e.message}")
            cachedSignatureTimestamp
        }
    }

    /**
     * Every distinct n and signature challenge in one script evaluation, for a whole ladder at
     * once. Null when no script is loaded; unanswered challenges are absent from the map, so
     * the caller falls back per challenge instead of losing the whole batch.
     */
    suspend fun solveBatch(
        nValues: List<String>,
        signatures: List<String>,
    ): Map<String, String>? =
        try {
            val webView = webViewMutex.withLock { getOrCreateWebView(forceRefresh = false) } ?: return null
            webView.solveBatch(nValues, signatures)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Batched solve failed: ${e.message}")
            null
        }

    /**
     * The distinct n values of [urls] in one script evaluation; the map is url -> url with its
     * n swapped. URLs whose n the script cannot answer are absent — the caller falls back to
     * the remote decoder for those, one at a time.
     */
    suspend fun transformNBatch(urls: List<String>): Map<String, String> {
        val byN = mutableMapOf<String, MutableList<String>>()
        urls.forEach { url ->
            Regex("[?&]n=([^&]+)").find(url)?.groupValues?.get(1)?.let { encoded ->
                val decoded = runCatching { Uri.decode(encoded) }.getOrNull() ?: encoded
                byN.getOrPut(decoded) { mutableListOf() }.add(url)
            }
        }
        if (byN.isEmpty()) return emptyMap()
        val answers = solveBatch(nValues = byN.keys.toList(), signatures = emptyList()) ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        byN.forEach { (n, urlList) ->
            val transformed = answers["n:$n"]?.takeIf { it != n } ?: return@forEach
            urlList.forEach { url ->
                out[url] = url.replaceFirst(Regex("([?&])n=[^&]+"), "$1n=${Uri.encode(transformed)}")
            }
        }
        return out
    }

    /** The hash of the player script whose signature timestamp the web `/player` requests carry. */
    suspend fun playerHash(): String? = PlayerJsFetcher.getPlayerJs()?.second

    fun invalidateSignatureTimestamp() {
        Log.d(TAG, "Invalidating signature timestamp")
        cachedSignatureTimestamp = null
        cachedSignatureTimestampHash = null
    }

    /**
     * Transform the 'n' parameter in a streaming URL to avoid throttling/403.
     * Returns the URL with the transformed 'n' value, or the original URL if transform fails.
     *
     * IMPORTANT: Must be called for WEB_REMIX, WEB, WEB_CREATOR, TVHTML5 clients.
     */
    suspend fun transformNParamInUrl(url: String): String {
        Log.d(TAG, "transformNParamInUrl: url length=${url.length}")
        return try {
            transformNInternal(url)
        } catch (e: Exception) {
            Log.e(TAG, "N-transform failed, returning original URL: ${e.message}", e)
            url
        }
    }

    private suspend fun transformNInternal(url: String): String {
        val nMatch = Regex("[?&]n=([^&]+)").find(url)
        if (nMatch == null) {
            Log.d(TAG, "No 'n' parameter found in URL, skipping transform")
            return url
        }

        val nValueEncoded = nMatch.groupValues[1]
        val nValue = Uri.decode(nValueEncoded)
        Log.d(TAG, "N-param: encoded=$nValueEncoded, decoded=$nValue")

        val webView =
            webViewMutex.withLock { getOrCreateWebView(forceRefresh = false) } ?: run {
                Log.e(TAG, "Failed to get CipherWebView for n-transform")
                return url
            }

        if (!webView.nFunctionAvailable) {
            Log.e(TAG, "N-transform function was not discovered at init time")
            return url
        }

        val transformedN = webView.transformN(nValue)
        Log.d(TAG, "N-transform: $nValue -> $transformedN")

        return url.replaceFirst(
            Regex("([?&])n=[^&]+"),
            "$1n=${Uri.encode(transformedN)}",
        )
    }

    private suspend fun getOrCreateWebView(forceRefresh: Boolean): CipherWebView? {
        Log.d(TAG, "getOrCreateWebView: forceRefresh=$forceRefresh, existing=${cipherWebView != null}")

        if (!forceRefresh && cipherWebView != null) {
            return cipherWebView
        }

        if (cipherWebView != null) {
            closeWebView()
        }

        buildWebView(forceRefresh)?.let { return it }

        // A player script the extractors cannot read stays cached for six hours, so without this
        // every request in that window fails identically — the device goes a working day with no
        // signature and no n-transform. Re-fetch once per player hash: if YouTube really did ship
        // a shape the patterns miss, the second attempt costs one request and then stops.
        val failedHash = lastAnalyzedHash
        if (forceRefresh || failedHash == null || refetchedForHash == failedHash) return null
        refetchedForHash = failedHash
        Log.w(TAG, "Player JS $failedHash could not be analyzed — dropping the cached copy and refetching once")
        PlayerJsFetcher.invalidateCache()
        return buildWebView(forceRefresh = true)
    }

    private suspend fun buildWebView(forceRefresh: Boolean): CipherWebView? {
        val result = PlayerJsFetcher.getPlayerJs(forceRefresh = forceRefresh)
        if (result == null) {
            Log.e(TAG, "Failed to get player JS")
            return null
        }
        val (playerJs, hash) = result
        Log.d(TAG, "Got player JS: hash=$hash, length=${playerJs.length}")

        // The n-function is extracted once per player version: a rebuild for a version seen
        // recently reuses the analysis instead of re-running the extractors over the script.
        val analysis =
            analysisCache[hash] ?: FunctionNameExtractor.analyzePlayerJs(playerJs, knownHash = hash).also { analysisCache[hash] = it }
        cachedSignatureTimestamp = analysis.signatureTimestamp
        cachedSignatureTimestampHash = hash
        Log.d(TAG, "Extracted signatureTimestamp: $cachedSignatureTimestamp")
        lastAnalyzedHash = hash

        // Reported on a missing signature alone, not only when the n-function is missing too: the
        // signature is the capability that is actually lost, and a player whose indices are computed
        // at runtime cannot be read by any pattern, so the hash is the useful thing to report.
        if (analysis.sigInfo == null) {
            unparseablePlayerHash = hash
            val computed = FunctionNameExtractor.hasComputedArrayIndices(playerJs)
            Log.e(TAG, "No signature function in player JS (hash=$hash, computedArrayIndices=$computed)")
        } else {
            unparseablePlayerHash = null
        }

        if (analysis.sigInfo == null && analysis.nFuncInfo == null) {
            Log.e(TAG, "Could not extract signature or n-function info from player JS (hash=$hash)")
            return null
        }

        if (analysis.sigInfo == null) {
            Log.w(TAG, "Could not extract signature function info from player JS; n-transform may still work")
        }

        if (analysis.nFuncInfo == null) {
            Log.w(TAG, "Could not extract n-function info from player JS (will try brute-force)")
        }

        Log.d(TAG, "Creating CipherWebView: sig=${analysis.sigInfo?.name}, nFunc=${analysis.nFuncInfo?.name}")
        val webView =
            CipherWebView.create(
                context = appContext,
                playerJs = playerJs,
                sigInfo = analysis.sigInfo,
                nFuncInfo = analysis.nFuncInfo,
            )

        Log.d(TAG, "CipherWebView created: nAvailable=${webView.nFunctionAvailable}, sigAvailable=${webView.sigFunctionAvailable}")
        cipherWebView = webView
        currentPlayerHash = hash
        return webView
    }

    private suspend fun closeWebView() {
        withContext(Dispatchers.Main) {
            cipherWebView?.close()
        }
        cipherWebView = null
        currentPlayerHash = null
        Log.d(TAG, "CipherWebView closed")
    }
}
