package com.quantlm.yaser.data.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Structured app event logger that writes both to logcat and a bounded local file.
 * The diagnostics screen reads this file to show important events even when logcat is noisy.
 */
object AppEventLogger {

    const val TAG = "AppEvent"

    private const val LOG_DIR_NAME = "diagnostics"
    private const val LOG_FILE_NAME = "app_events.log"
    private const val MAX_LOG_LINES = 4000
    private const val DEFAULT_READ_LINES = 300

    @Volatile
    private var appContext: Context? = null

    private val fileLock = Any()

    fun init(context: Context) {
        appContext = context.applicationContext
        info(
            component = "Application",
            action = "logger_initialized",
            details = "logPath=${getLogFilePath() ?: "unknown"}"
        )
    }

    fun getLogFilePath(context: Context? = null): String? {
        val ctx = context?.applicationContext ?: appContext ?: return null
        return getLogFile(ctx).absolutePath
    }

    fun readRecentLines(maxLines: Int = DEFAULT_READ_LINES, context: Context? = null): List<String> {
        val ctx = context?.applicationContext ?: appContext ?: return emptyList()
        return synchronized(fileLock) {
            try {
                val file = getLogFile(ctx)
                if (!file.exists()) return@synchronized emptyList()
                file.readLines().takeLast(maxLines)
            } catch (e: Exception) {
                listOf("Could not read app event log: ${e.message ?: "unknown error"}")
            }
        }
    }

    fun debug(component: String, action: String, details: String = "") {
        log(level = Log.DEBUG, component = component, action = action, details = details)
    }

    fun info(component: String, action: String, details: String = "") {
        log(level = Log.INFO, component = component, action = action, details = details)
    }

    fun warn(component: String, action: String, details: String = "") {
        log(level = Log.WARN, component = component, action = action, details = details)
    }

    fun error(component: String, action: String, details: String = "", throwable: Throwable? = null) {
        log(
            level = Log.ERROR,
            component = component,
            action = action,
            details = details,
            throwable = throwable
        )
    }

    private fun log(
        level: Int,
        component: String,
        action: String,
        details: String,
        throwable: Throwable? = null
    ) {
        val printable = buildString {
            append(component)
            append("::")
            append(action)
            if (details.isNotBlank()) {
                append(" | ")
                append(details)
            }
        }

        if (throwable != null) {
            Log.println(level, TAG, "$printable | ${throwable.javaClass.simpleName}: ${throwable.message}")
        } else {
            Log.println(level, TAG, printable)
        }

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val levelChar = when (level) {
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            else -> "U"
        }
        val line = buildString {
            append(timestamp)
            append(" ")
            append(levelChar)
            append("/")
            append(component)
            append(" ")
            append(action)
            if (details.isNotBlank()) {
                append(" | ")
                append(details)
            }
            append(" | thread=")
            append(Thread.currentThread().name)
            if (throwable != null) {
                append(" | error=")
                append(throwable.javaClass.simpleName)
                val msg = throwable.message?.replace("\n", " ")?.trim()
                if (!msg.isNullOrEmpty()) {
                    append(": ")
                    append(msg)
                }
            }
        }

        appendToFile(line)
    }

    private fun appendToFile(line: String) {
        val ctx = appContext ?: return
        synchronized(fileLock) {
            try {
                val file = getLogFile(ctx)
                file.parentFile?.mkdirs()
                file.appendText(line + "\n")
                trimIfNeeded(file)
            } catch (e: Exception) {
                Log.w(TAG, "Failed writing app event log", e)
            }
        }
    }

    private fun trimIfNeeded(file: File) {
        if (!file.exists()) return
        val lines = file.readLines()
        if (lines.size <= MAX_LOG_LINES) return
        file.writeText(lines.takeLast(MAX_LOG_LINES).joinToString(separator = "\n", postfix = "\n"))
    }

    private fun getLogFile(context: Context): File {
        return File(File(context.filesDir, LOG_DIR_NAME), LOG_FILE_NAME)
    }
}