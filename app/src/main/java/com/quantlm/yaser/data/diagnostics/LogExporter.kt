package com.quantlm.yaser.data.diagnostics

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the Markdown diagnostics bundle to public Downloads so the user can
 * share it through any file picker (Drive, email, paste into a chat, USB pull).
 *
 * Files land in `Downloads/QuantLM_Logs/` with the user-specified filename
 * `QuantLM_log-<N>_dd-MM-yyyy_HH-mm-ss.md`. The `/` and `:` characters from
 * the user's original spec are illegal on Android filesystems, so dates use
 * hyphens.
 *
 * On API ≥ 29 (the app's minSdk) this uses `MediaStore.Downloads` with
 * `RELATIVE_PATH` to create the subfolder atomically — no WRITE_EXTERNAL_STORAGE
 * permission needed. Scoped storage handles everything.
 */
object LogExporter {

    private const val TAG = "LogExporter"
    private const val APP_NAME = "QuantLM"
    private const val SUBFOLDER = "QuantLM_Logs"
    private const val MIME = "text/markdown"

    data class ExportResult(
        val uri: Uri,
        val displayName: String,
    )

    suspend fun saveMarkdown(
        context: Context,
        exportNumber: Int,
    ): Result<ExportResult> = withContext(Dispatchers.IO) {
        AppEventLogger.info(
            component = TAG,
            action = "save_markdown_started",
            details = "exportNumber=$exportNumber"
        )
        try {
            val displayName = buildDisplayName(exportNumber)
            val body = SystemLogDiagnostics.buildMarkdownBundle(context, exportNumber)

            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeViaMediaStore(context, displayName, body)
            } else {
                // The app's minSdk is 29 (Android 10 / Q), so this branch is
                // dead code on shipping builds. Kept defensive so a future
                // minSdk bump doesn't silently break the legacy path.
                writeViaLegacyExternal(displayName, body)
            }

            AppEventLogger.info(
                component = TAG,
                action = "save_markdown_success",
                details = "name=$displayName, bytes=${body.length}, uri=$uri"
            )
            Result.success(ExportResult(uri = uri, displayName = displayName))
        } catch (t: Throwable) {
            AppEventLogger.error(
                component = TAG,
                action = "save_markdown_failed",
                details = "exportNumber=$exportNumber, reason=${t.message ?: "unknown"}",
                throwable = t
            )
            Result.failure(t)
        }
    }

    private fun buildDisplayName(exportNumber: Int): String {
        val date = SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date())
        val time = SimpleDateFormat("HH-mm-ss", Locale.US).format(Date())
        return "${APP_NAME}_log-${exportNumber}_${date}_${time}.md"
    }

    private fun writeViaMediaStore(context: Context, displayName: String, body: String): Uri {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME)
            // RELATIVE_PATH must include the top-level public directory.
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore.insert returned null for $displayName")

        try {
            resolver.openOutputStream(uri)?.use { stream ->
                stream.write(body.toByteArray(Charsets.UTF_8))
                stream.flush()
            } ?: throw IllegalStateException("Could not open output stream for $uri")
        } catch (t: Throwable) {
            // Roll back the pending row so we don't leave a 0-byte ghost.
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }

        // Flip IS_PENDING so the file is visible to other apps / file managers.
        val finalize = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        resolver.update(uri, finalize, null, null)
        return uri
    }

    @Suppress("DEPRECATION")
    private fun writeViaLegacyExternal(displayName: String, body: String): Uri {
        val downloadsRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val subDir = File(downloadsRoot, SUBFOLDER).apply { if (!exists()) mkdirs() }
        val file = File(subDir, displayName)
        file.writeText(body, Charsets.UTF_8)
        return Uri.fromFile(file)
    }
}
