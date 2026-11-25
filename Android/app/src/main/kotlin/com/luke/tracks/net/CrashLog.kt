package com.luke.tracks.net

import android.content.Context
import java.io.File

/**
 * Writes a crash into the Tracks log, so `adb logcat -s Tracks` shows it, and keeps it to log
 * again at the next launch in case the first was missed. Messages pass through [Redact]; the
 * frames are the app's own code and hold no credentials.
 */
object CrashLog {
    private const val FILE = "last-crash.txt"

    fun install(context: Context) {
        val file = File(context.filesDir, FILE)
        runCatching {
            if (file.exists()) {
                file.readText().lines().forEach { Logs.e("Last crash: $it") }
                file.delete()
            }
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val report = describe(thread.name, error)
                report.lines().forEach { Logs.e("Crash: $it") }
                file.writeText(report)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    internal fun describe(thread: String, error: Throwable): String = buildString {
        append("on thread ").append(thread).append('\n')
        generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }.take(4).forEachIndexed { index, cause ->
            if (index > 0) append("caused by ")
            append(cause.javaClass.name).append(": ").append(Redact.text(cause.message)).append('\n')
            cause.stackTrace.take(30).forEach { append("    at ").append(it.toString()).append('\n') }
        }
    }.trimEnd()
}
