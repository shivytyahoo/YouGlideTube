package io.github.shivytyahoo.glidetube.service

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSessionService
import io.github.shivytyahoo.glidetube.player.error.PlayerDiagnostics
import io.github.shivytyahoo.glidetube.utils.GlideCrashHandler

@UnstableApi
internal fun MediaSessionService.recordForegroundStartFailures(tag: String) {
    setListener(
        object : MediaSessionService.Listener {
            @RequiresApi(Build.VERSION_CODES.S)
            override fun onForegroundServiceStartNotAllowedException() {
                GlideCrashHandler.recordPhase(tag, "foreground start refused by platform")
                PlayerDiagnostics.logWarning(
                    tag,
                    "startForegroundService refused: app is in the background without an " +
                        "FGS-start allowance; playback notification stays non-foreground",
                )
            }
        },
    )
}
