package com.quantlm.yaser.data.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import kotlin.math.max

/**
 * Builds log views for the in-app diagnostics screen. Uses bounded logcat reads and a small
 * static header — no continuous app-wide logging.
 */
object SystemLogDiagnostics {

    private const val LOGCAT_LINE_CAP = 350
    private const val LOGCAT_TAIL = "1200"
    private val FALLBACK_TAG_HINTS = listOf(
        AppEventLogger.TAG,
        "MediaPipeEngine",
        "LlamaEngine",
        "ModelRepository",
        "InferenceRepo",
        "SendMessageUseCase",
        "ChatRepository",
        "DownloadWorker",
        "WorkManagerDownloadRepo",
        "GenerationPreferences",
        "ModelPreferences",
        "AppPreferences",
        "SettingsViewModel",
        "ChatViewModel",
        "AndroidRuntime",
        "libc"
    )

    private fun readLogcatLines(args: List<String>): List<String> {
        val proc = ProcessBuilder(*args.toTypedArray())
            .redirectErrorStream(true)
            .start()
        return proc.inputStream.bufferedReader().use { it.readLines() }
    }

    fun buildHeaderLines(context: Context): List<String> {
        val pm = context.packageManager
        val pkg = context.packageName
        val pi = try {
            pm.getPackageInfo(pkg, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val ver = pi?.let { "${it.versionName} (${it.longVersionCode})" } ?: "unknown"
        val rt = Runtime.getRuntime()
        val usedMb = max(0L, (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024))
        val maxMb = max(0L, rt.maxMemory() / (1024 * 1024))
        return listOf(
            "── QuantLM snapshot (${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}) ──",
            "Package: $pkg  •  App version: $ver",
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}  •  Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "VM heap (approx): ${usedMb}MB used / ${maxMb}MB max",
            "App event log file: ${AppEventLogger.getLogFilePath(context) ?: "not initialized"}",
            "── Diagnostics log stream (app events + filtered logcat) ──"
        )
    }

    fun readFilteredLogcat(context: Context? = null): Pair<List<String>, String?> {
        return try {
            val appEventLines = AppEventLogger.readRecentLines(maxLines = 250, context = context)
            val pid = Process.myPid()
            val byPidArgs = listOf(
                "/system/bin/logcat",
                "-d",
                "-t",
                LOGCAT_TAIL,
                "--pid=$pid",
                "*:V"
            )
            val byPidLogs = readLogcatLines(byPidArgs)
            val pidFilterUnsupported = byPidLogs.any {
                it.contains("--pid", ignoreCase = true) &&
                    (it.contains("unknown", ignoreCase = true) || it.contains("invalid", ignoreCase = true))
            }

            val filtered = if (byPidLogs.isNotEmpty() && !pidFilterUnsupported) {
                byPidLogs.takeLast(LOGCAT_LINE_CAP)
            } else {
                val fallbackLogs = readLogcatLines(
                    listOf("/system/bin/logcat", "-d", "-t", LOGCAT_TAIL, "*:V")
                )
                fallbackLogs.filter { line ->
                    line.contains("quantlm", ignoreCase = true) ||
                        line.contains("QuantLM", ignoreCase = true) ||
                        line.contains("com.quantlm", ignoreCase = true) ||
                        FALLBACK_TAG_HINTS.any { tag -> line.contains(tag) }
                }.takeLast(LOGCAT_LINE_CAP)
            }

            val combined = buildList {
                if (appEventLines.isNotEmpty()) {
                    add("── App events (persistent, recent) ──")
                    addAll(appEventLines)
                    add("")
                }
                add("── Logcat (filtered) ──")
                addAll(filtered)
            }

            val note = if (combined.isEmpty()) {
                "No diagnostics lines matched filters yet. This view refreshes about every 4 seconds while you stay on this screen."
            } else {
                null
            }
            Pair(combined, note)
        } catch (e: Exception) {
            Pair(
                emptyList(),
                "Could not read logcat: ${e.message ?: "unknown error"}"
            )
        }
    }
}
