package io.github.koki_dayo39.batterystatus.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory ring buffer of app events, mirrored to logcat. Included in the
 * diagnostics report so users can attach it to bug reports without adb.
 */
object AppLog {
    private const val CAPACITY = 400
    private val lines = ArrayDeque<String>()
    private val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        append("I", tag, message)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        Log.w(tag, message, error)
        append("W", tag, if (error != null) "$message: $error" else message)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        Log.e(tag, message, error)
        append("E", tag, if (error != null) "$message: $error" else message)
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() = synchronized(lines) { lines.clear() }

    private fun append(level: String, tag: String, message: String) {
        val line = "${format.format(Date())} $level/$tag: $message"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > CAPACITY) lines.removeFirst()
        }
    }
}
