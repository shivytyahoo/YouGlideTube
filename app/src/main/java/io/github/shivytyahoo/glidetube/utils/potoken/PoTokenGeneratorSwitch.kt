package io.github.shivytyahoo.glidetube.utils.potoken

import java.util.Locale

/**
 * Which BotGuard generator builds the web-pot session.
 *
 * - [V4], the default: PoTokenWebView4, the youtube.com homepage's `ytAtN` challenge with that
 *   page's `ytcfg` injected as `yt.config_`, falling back to `/att/get`, then to the legacy
 *   generator. The homepage pair is what YouTube's WEB/MWEB experiment requires: challenges from
 *   the legacy WAA `Create` call carry no `EVENT_ID` and their tokens are refused (Report 51).
 * - [LEGACY]: PoTokenWebView, the WAA `Create` challenge. No page is read, so BotGuard runs
 *   without `ytcfg`/`EVENT_ID`.
 *
 * Read on every generator build, so a flip takes effect on the next build without restarting.
 * There is no user-facing setting; [select] is the debug/A-B hook.
 */
object PoTokenGeneratorSwitch {
    const val LEGACY = "legacy"
    const val V4 = "v4"

    @Volatile
    private var selection = V4

    /** @return false for an unknown name, which leaves the selection unchanged */
    fun select(name: String?): Boolean {
        val normalized = name?.trim()?.lowercase(Locale.US)

        if (normalized != LEGACY && normalized != V4) {
            return false
        }

        selection = normalized
        return true
    }

    fun getSelected(): String = selection

    fun isV4Selected(): Boolean = selection == V4
}
