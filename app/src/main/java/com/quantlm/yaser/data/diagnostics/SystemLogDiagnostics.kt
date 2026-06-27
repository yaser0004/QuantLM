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
            "Cores: ${Runtime.getRuntime().availableProcessors()} (availableProcessors)",
            "App event log file: ${AppEventLogger.getLogFilePath(context) ?: "not initialized"}",
            "Perf snapshot log file: ${PerformanceSnapshotLogger.getLogFilePath(context) ?: "not initialized"}",
            "── Diagnostics log stream (app events + filtered logcat) ──"
        )
    }

    /**
     * Append the most recent performance-snapshot lines (RSS, MemAvailable,
     * thermal, page faults) to a diagnostics export. Returns an empty list
     * when no sampling has run yet — that's the steady-state condition
     * outside of model loads / generations.
     */
    fun readPerfSnapshots(context: Context? = null, maxLines: Int = 600): List<String> {
        val lines = PerformanceSnapshotLogger.readRecentLines(maxLines = maxLines, context = context)
        return if (lines.isEmpty()) {
            emptyList()
        } else {
            buildList {
                add("── Performance snapshots (sampled during load/inference) ──")
                addAll(lines)
            }
        }
    }

    /**
     * Build a self-contained Markdown bundle that includes the full retained
     * app-event buffer, the full perf-snapshot buffer, and the recent filtered
     * logcat, plus the device/app header. Designed to be saved to disk and
     * pasted into a bug report — opens cleanly in any Markdown viewer.
     *
     * Reuses [buildHeaderLines] and [readFilteredLogcat] so the on-disk file
     * and the in-app share always agree.
     */
    fun buildMarkdownBundle(context: Context, exportNumber: Int): String {
        val pm = context.packageManager
        val pkg = context.packageName
        val ver = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
            ?.let { "${it.versionName} (${it.longVersionCode})" }
            ?: "unknown"
        val rt = Runtime.getRuntime()
        val usedMb = max(0L, (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024))
        val maxMb = max(0L, rt.maxMemory() / (1024 * 1024))
        val timestamp = java.text.SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            java.util.Locale.US
        ).format(java.util.Date())

        val appEvents = AppEventLogger.readRecentLines(maxLines = Int.MAX_VALUE, context = context)
        val perfSnaps = PerformanceSnapshotLogger.readRecentLines(maxLines = Int.MAX_VALUE, context = context)
        val (logcatLines, _) = readFilteredLogcat(context)

        return buildString {
            append("# QuantLM Log #").append(exportNumber).append('\n')
            append('\n')
            append("**Saved:** ").append(timestamp).append("  \n")
            append("**Device:** ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" — Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")  \n")
            append("**App:** QuantLM ").append(ver).append("  \n")
            append("**JVM heap:** ").append(usedMb).append(" MB used / ").append(maxMb).append(" MB max  \n")
            append("**Cores:** ").append(Runtime.getRuntime().availableProcessors()).append("  \n")
            append("**App event log file:** `")
                .append(AppEventLogger.getLogFilePath(context) ?: "not initialized")
                .append("`  \n")
            append("**Perf snapshot log file:** `")
                .append(PerformanceSnapshotLogger.getLogFilePath(context) ?: "not initialized")
                .append("`\n")
            append("\n---\n\n")

            append("## App events (full retained buffer — ")
                .append(appEvents.size).append(" lines)\n\n")
            append("```\n")
            if (appEvents.isEmpty()) {
                append("(empty — logger has not written anything yet)\n")
            } else {
                appEvents.forEach { append(it).append('\n') }
            }
            append("```\n\n")

            append("## Performance snapshots (load / inference only — ")
                .append(perfSnaps.size).append(" lines)\n\n")
            append("```\n")
            if (perfSnaps.isEmpty()) {
                append("(empty — no model load or generation has happened in this session)\n")
            } else {
                perfSnaps.forEach { append(it).append('\n') }
            }
            append("```\n\n")

            // Per-session logs: each app launch's full history. Saved to private
            // internal storage (invisible to the user otherwise), so surface them
            // here. Newest first; the current session is always included in full,
            // older ones until a byte budget is hit so the export can't balloon.
            val sessionFiles = AppEventLogger.listSessionFiles(context)
            append("## Session logs (").append(sessionFiles.size).append(" file(s))\n\n")
            if (sessionFiles.isEmpty()) {
                append("(none yet — session logging is off, or no file has been written this launch)\n\n")
            } else {
                val current = AppEventLogger.currentSessionFile()
                var budget = 1_500_000L // ~1.5 MB of session text across the export
                sessionFiles.forEach { f ->
                    val isCurrent = current != null && f.absolutePath == current.absolutePath
                    val lines = AppEventLogger.readSessionFileLines(f)
                    append("### ").append(f.name)
                    if (isCurrent) append("  *(current session)*")
                    append("  — ").append(f.length() / 1024).append(" KB, ")
                        .append(lines.size).append(" lines\n\n")
                    if (isCurrent || budget > 0) {
                        append("```\n")
                        if (lines.isEmpty()) append("(empty)\n") else lines.forEach { append(it).append('\n') }
                        append("```\n\n")
                        budget -= f.length()
                    } else {
                        append("_(omitted to keep the export small)_\n\n")
                    }
                }
            }

            append("## Recent logcat (filtered to QuantLM PID — ")
                .append(logcatLines.size).append(" lines)\n\n")
            append("```\n")
            if (logcatLines.isEmpty()) {
                append("(empty — logcat read returned nothing)\n")
            } else {
                logcatLines.forEach { append(it).append('\n') }
            }
            append("```\n")
        }
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

            val perfLines = PerformanceSnapshotLogger.readRecentLines(maxLines = 400, context = context)

            val combined = buildList {
                if (appEventLines.isNotEmpty()) {
                    add("── App events (persistent, recent) ──")
                    addAll(appEventLines)
                    add("")
                }
                if (perfLines.isNotEmpty()) {
                    add("── Performance snapshots (load/inference only) ──")
                    addAll(perfLines)
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
