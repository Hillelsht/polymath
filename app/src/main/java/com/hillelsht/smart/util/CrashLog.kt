package com.hillelsht.smart.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures uncaught exceptions to a file so a crash on a device the developer cannot reach
 * still produces a stack trace.
 *
 * The previous handler is always invoked afterwards, so Android's own crash dialog and
 * reporting behave exactly as before — this only observes.
 */
object CrashLog {

    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(appContext, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val stackTrace = StringWriter().also { writer ->
            PrintWriter(writer).use(throwable::printStackTrace)
        }.toString()

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        file(context).writeText(
            buildString {
                appendLine("Smart crash — $timestamp")
                appendLine("thread: ${thread.name}")
                appendLine()
                append(stackTrace)
            },
        )
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** The last recorded crash, or null if the app has never crashed since install. */
    fun read(context: Context): String? =
        file(context).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        file(context).delete()
    }
}
