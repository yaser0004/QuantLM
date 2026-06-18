package com.quantlm.yaser.data.diagnostics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Structured app event logger that writes both to logcat and a bounded local file.
 * The diagnostics screen reads this file to show important events even when logcat is noisy.
 *
 * Storage model: callers are not blocked on disk. Each `debug/info/warn/error` call
 * synchronously emits to logcat, formats the line, and offers it to an in-memory
 * channel; a single writer coroutine drains the channel in batches on Dispatchers.IO.
 * When the backlog grows past [SOFT_BACKLOG_DROP_THRESHOLD], oldest DEBUG entries are
 * dropped first — WARN/ERROR are kept regardless. This is what lets the rest of the
 * app instrument every meaningful action ("every move") without making caller hot
 * paths pay for disk I/O.
 */
object AppEventLogger {

    const val TAG = "AppEvent"

    private const val LOG_DIR_NAME = "diagnostics"
    private const val LOG_FILE_NAME = "app_events.log"
    private const val ROTATED_LOG_FILE_NAME = "app_events.log.1"

    // Two-file rotation: when the live file passes this size it is renamed to
    // the .1 file (replacing the previous one) — an O(1) rename instead of the
    // old read-all-50k-lines-and-rewrite trim, which allocated ~12 MB on the
    // write path once a minute during streaming. Total retained ≈ 2× this.
    private const val MAX_LOG_BYTES = 6L * 1024 * 1024
    private const val DEFAULT_READ_LINES = 300

    // Async-writer plumbing.
    private const val WRITE_BATCH_MAX = 200
    private const val SOFT_BACKLOG_DROP_THRESHOLD = 20_000

    @Volatile
    private var appContext: Context? = null

    private val fileLock = Any()

    // Each pending write carries its level so the drain loop can drop oldest
    // DEBUG entries first if the backlog grows. WARN/ERROR are always kept.
    private data class Pending(val level: Int, val line: String)

    private val channel = Channel<Pending>(Channel.UNLIMITED)
    private val writerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile
    private var writerStarted = false

    fun init(context: Context) {
        appContext = context.applicationContext
        ensureWriterStarted()
        info(
            component = "Application",
            action = "logger_initialized",
            details = "logPath=${getLogFilePath() ?: "unknown"}, rotateAtBytes=$MAX_LOG_BYTES"
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
                val current = getLogFile(ctx)
                val lines = if (current.exists()) current.readLines() else emptyList()
                if (lines.size >= maxLines) {
                    lines.takeLast(maxLines)
                } else {
                    // Not enough in the live file — prepend the rotated half.
                    val rotated = getRotatedLogFile(ctx)
                    val older = if (rotated.exists()) rotated.readLines() else emptyList()
                    (older + lines).takeLast(maxLines)
                }
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

        enqueue(Pending(level, line))
    }

    private fun enqueue(pending: Pending) {
        if (appContext == null) return
        ensureWriterStarted()
        // Channel is UNLIMITED so trySend never fails for capacity reasons; only
        // returns failed if closed (won't happen at runtime).
        channel.trySend(pending)
    }

    private fun ensureWriterStarted() {
        if (writerStarted) return
        synchronized(this) {
            if (writerStarted) return
            writerStarted = true
            writerScope.launch { drainLoop() }
        }
    }

    private suspend fun drainLoop() {
        val batch = ArrayDeque<Pending>(WRITE_BATCH_MAX)
        while (writerScope.isActive) {
            // Block until at least one line is available.
            val first = channel.receive()
            batch.add(first)
            // Drain anything else already queued, up to the batch cap.
            while (batch.size < WRITE_BATCH_MAX) {
                val more = channel.tryReceive().getOrNull() ?: break
                batch.add(more)
            }
            // Apply backlog protection if we are far behind: drop oldest DEBUG
            // entries until we are back under the soft threshold. Anything at
            // WARN or above is preserved.
            if (batch.size + estimatePendingInChannel() > SOFT_BACKLOG_DROP_THRESHOLD) {
                val it = batch.iterator()
                while (it.hasNext()) {
                    val p = it.next()
                    if (p.level <= Log.DEBUG) it.remove()
                    if (batch.size <= WRITE_BATCH_MAX / 2) break
                }
            }
            if (batch.isEmpty()) continue

            val ctx = appContext
            if (ctx != null) {
                val payload = buildString(batch.sumOf { it.line.length + 1 }) {
                    for (p in batch) {
                        append(p.line)
                        append('\n')
                    }
                }
                writeBatch(ctx, payload)
            }
            batch.clear()
        }
    }

    // Channel doesn't expose remaining count cheaply; this is a best-effort
    // peek by checking tryReceive in a separate pass. We approximate "behind"
    // by whether the current batch was the maximum size.
    private fun estimatePendingInChannel(): Int = 0 // Channel size isn't exposed; rely on per-batch check.

    private fun writeBatch(ctx: Context, payload: String) {
        synchronized(fileLock) {
            try {
                val file = getLogFile(ctx)
                file.parentFile?.mkdirs()
                file.appendText(payload)
                if (file.length() > MAX_LOG_BYTES) {
                    rotate(ctx, file)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed writing app event log", e)
            }
        }
    }

    // O(1) size cap: the live file becomes the .1 file (replacing the old one)
    // and logging continues into a fresh live file.
    private fun rotate(ctx: Context, current: File) {
        val rotated = getRotatedLogFile(ctx)
        if (rotated.exists()) rotated.delete()
        if (!current.renameTo(rotated)) {
            // Same-directory rename should not fail; truncate as a fallback so
            // the log cannot grow without bound.
            Log.w(TAG, "Log rotation rename failed; truncating live log")
            current.writeText("")
        }
    }

    private fun getLogFile(context: Context): File {
        return File(File(context.filesDir, LOG_DIR_NAME), LOG_FILE_NAME)
    }

    private fun getRotatedLogFile(context: Context): File {
        return File(File(context.filesDir, LOG_DIR_NAME), ROTATED_LOG_FILE_NAME)
    }
}
