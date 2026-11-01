package io.github.aedev.flow.utils

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's own copy of its GlideFeed diagnostics. The device logcat ring is small, and
 * while a video plays, system W-level spam evicts our lines within about a minute — a
 * diagnostics report read back from logcat kept arriving without the very lines the
 * diagnosis turned on (a hung delete left no trace at all). Every GlideFeed line is
 * therefore also kept in this bounded in-memory ring, and the Diagnostics report reads it
 * from here. Lines still mirror to logcat for live reading.
 */
object ShivLog {
    private const val TAG = "GlideFeed"
    private const val CAPACITY = 600
    private val lines = ArrayDeque<String>()
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun i(message: String) {
        Log.i(TAG, message)
        lines.addLast("${stamp.format(Date())} I GlideFeed: $message")
        while (lines.size > CAPACITY) lines.removeFirst()
    }

    @Synchronized
    fun dump(): String =
        if (lines.isEmpty()) {
            "No app diagnostics recorded yet this session."
        } else {
            lines.joinToString("\n")
        }
}
