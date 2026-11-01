package io.github.aedev.flow.ui.screens.settings.account

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.aedev.flow.R
import io.github.aedev.flow.data.account.AccountCookieStore
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.ui.screens.home.HomeFeedCache

/**
 * Google sign-in in a WebView, following the same shape as the Discord login: the user signs
 * in on Google's own page, and once YouTube's cookies show a completed session (SAPISID +
 * LOGIN_INFO, the pair InnerTube's SAPISIDHASH auth needs) the cookie is handed to
 * [AccountCookieStore] and applied to the InnerTube client. The WebView's own cookie store is
 * wiped afterwards, so the stored cookie is the only copy of the session in the app.
 *
 * The frame around Google's page is YouGlideTube's own: a darker-violet status band, a brand bar
 * below it, a saffron load line under that, and a branded loading veil that fades away when the
 * page lands — so the screen reads as part of the app, not a foreign window.
 */
class GoogleLoginActivity : ComponentActivity() {
    private var completed = false
    private lateinit var loginWebView: WebView
    private lateinit var loadVeil: View
    private lateinit var progressBar: ProgressBar
    private var veilLifted = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition()
        styleSystemBars()

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        progressBar =
            ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
                progressTintList = ColorStateList.valueOf(SAFFRON)
                progressBackgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                visibility = View.INVISIBLE
                layoutParams =
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))
            }

        loginWebView =
            WebView(this).apply {
                setBackgroundColor(Color.WHITE)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                // Google refuses sign-in from a WebView user agent ("; wv"); present as a browser.
                settings.userAgentString = settings.userAgentString.replace("; wv", "")
                webChromeClient =
                    object : WebChromeClient() {
                        override fun onProgressChanged(
                            view: WebView,
                            newProgress: Int,
                        ) {
                            progressBar.progress = newProgress
                            progressBar.visibility =
                                if (newProgress in 1..99) View.VISIBLE else View.INVISIBLE
                        }
                    }
                webViewClient =
                    object : WebViewClient() {
                        override fun onPageFinished(
                            view: WebView,
                            url: String,
                        ) {
                            super.onPageFinished(view, url)
                            liftVeil()
                            captureSessionIfSignedIn()
                            // YouTube is a single-page app: later navigations do not always
                            // retrigger onPageFinished, so keep polling the cookie store.
                            view.postDelayed({ if (!completed) captureSessionIfSignedIn() }, 1500)
                            view.postDelayed({ if (!completed) captureSessionIfSignedIn() }, 3500)
                            view.postDelayed({ if (!completed) captureSessionIfSignedIn() }, 6000)
                        }
                    }
                loadUrl(LOGIN_URL)
            }

        loadVeil = buildLoadVeil(::dp)
        val webFrame =
            FrameLayout(this).apply {
                setBackgroundColor(Color.WHITE)
                addView(
                    loginWebView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                addView(
                    loadVeil,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }

        // The status band's own strip: its height tracks the real status inset, so the
        // brand bar always starts below the phone's icons instead of under them.
        val statusStrip =
            View(this).apply {
                setBackgroundColor(DEEP_VIOLET)
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                    )
            }

        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(BRAND_VIOLET)
                addView(statusStrip)
                addView(buildBrandBar(::dp))
                addView(progressBar)
                addView(
                    webFrame,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f,
                    ),
                )
            }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            statusStrip.layoutParams.height = bars.top
            statusStrip.requestLayout()
            // The page ends above the navigation band, so Google's own footer never
            // sits under the system bar. Consumed here so nothing below pads twice.
            webFrame.setPadding(0, 0, 0, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)

        // Back walks the sign-in flow's own history before leaving the activity.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (::loginWebView.isInitialized && loginWebView.canGoBack()) {
                        loginWebView.goBack()
                    } else {
                        finishAnimated()
                    }
                }
            },
        )
    }

    /** The violet bar under the status bar: app icon, name + purpose, and a borderless close. */
    private fun buildBrandBar(dp: (Int) -> Int): View {
        val bar =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(BRAND_VIOLET)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(8), dp(10))
            }
        val icon =
            ImageView(this).apply {
                setImageDrawable(packageManager.getApplicationIcon(applicationInfo))
                layoutParams =
                    LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) }
            }
        val title =
            TextView(this).apply {
                text = getString(R.string.app_name) + " · " + getString(R.string.account_sign_in)
                setTextColor(Color.WHITE)
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        val close =
            ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                setColorFilter(Color.WHITE)
                background =
                    with(TypedValue()) {
                        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, this, true)
                        androidx.core.content.ContextCompat.getDrawable(this@GoogleLoginActivity, resourceId)
                    }
                contentDescription = getString(R.string.back)
                setOnClickListener { finishAnimated() }
            }
        bar.addView(icon)
        bar.addView(title)
        bar.addView(close)
        return bar
    }

    /** The branded loading veil: deep violet, the app mark, and a saffron spinner. */
    private fun buildLoadVeil(dp: (Int) -> Int): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(DEEP_VIOLET)
            val icon =
                ImageView(this@GoogleLoginActivity).apply {
                    setImageDrawable(packageManager.getApplicationIcon(applicationInfo))
                    layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
                }
            val name =
                TextView(this@GoogleLoginActivity).apply {
                    text = getString(R.string.app_name)
                    setTextColor(Color.WHITE)
                    textSize = 20f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(0, dp(14), 0, 0)
                }
            val spinner =
                ProgressBar(this@GoogleLoginActivity).apply {
                    indeterminateTintList = ColorStateList.valueOf(SAFFRON)
                    layoutParams =
                        LinearLayout.LayoutParams(dp(30), dp(30)).apply { topMargin = dp(22) }
                }
            val hint =
                TextView(this@GoogleLoginActivity).apply {
                    text = getString(R.string.account_sign_in_loading)
                    setTextColor(0xFFB9AED6.toInt())
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(10), 0, 0)
                }
            addView(icon)
            addView(name)
            addView(spinner)
            addView(hint)
        }

    private fun liftVeil() {
        if (veilLifted) return
        veilLifted = true
        loadVeil
            .animate()
            .alpha(0f)
            .setDuration(280)
            .withEndAction { loadVeil.visibility = View.GONE }
            .start()
    }

    /**
     * Edge-to-edge on purpose, with the bars' bands owned by the layout itself: targeting
     * SDK 36 means Android 15+ enforces edge-to-edge, and decor fitting no longer insets
     * this Views screen's content at all — which is exactly how the brand bar ended up
     * under the status icons and the page footer under the navigation band. So the
     * window goes transparent and the insets listener above places the bands by hand.
     * Light icons over the dark status strip, dark icons over the page-white nav band.
     */
    private fun styleSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = true
        }
    }

    /**
     * No enter/exit animation: the fade used to cross-dissolve Home under this screen, and
     * that frame read as the two screens overlapping. A clean cut cannot ghost.
     */
    private fun enterTransition() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun finishAnimated() {
        finish()
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun captureSessionIfSignedIn() {
        if (completed) return
        val cookie = CookieManager.getInstance().getCookie(YOUTUBE_URL) ?: return
        // Session proof = SAPISID plus any of SID / HSID / LOGIN_INFO. LOGIN_INFO must NOT be
        // required on its own: it is short-lived and is often already gone by the time
        // youtube.com finishes loading, which used to leave this screen stuck showing the
        // YouTube web page after a successful sign-in.
        val hasSession =
            cookie.contains("SAPISID=") &&
                (cookie.contains("SID=") || cookie.contains("HSID=") || cookie.contains("LOGIN_INFO="))
        if (!hasSession) return
        completed = true

        // The session manager records the account (adding it to the saved accounts), makes
        // it active and resets the account-scoped caches.
        dagger.hilt.android.EntryPointAccessors
            .fromApplication(
                applicationContext,
                io.github.aedev.flow.data.account.AccountSessionEntryPoint::class.java,
            ).accountSessionManager()
            .onWebLogin(cookie)
        // Drop the anonymous home feed so Home rebuilds with the account's feed merged in.
        HomeFeedCache.clear()

        // The stored cookie is the session now; leave no second copy in the WebView sandbox.
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        setResult(RESULT_OK)
        finishAnimated()
    }

    override fun onDestroy() {
        if (::loginWebView.isInitialized) {
            loginWebView.stopLoading()
            loginWebView.destroy()
        }
        super.onDestroy()
    }

    private companion object {
        const val BRAND_VIOLET = 0xFF1B1035.toInt()
        const val DEEP_VIOLET = 0xFF120826.toInt()
        const val SAFFRON = 0xFFFF9933.toInt()
        const val YOUTUBE_URL = "https://www.youtube.com"
        const val LOGIN_URL =
            "https://accounts.google.com/ServiceLogin?service=youtube&continue=https://www.youtube.com/"
    }
}
