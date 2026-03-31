package com.quantlm.yaser.presentation.models

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private const val TAG = "ModelImportDialog"

/** Supported model file extensions for local import — Fix [2.4] */
private val SUPPORTED_EXTENSIONS = setOf("gguf", "task", "tflite", "litertlm", "literlm")

/** Minimum file size in bytes considered a valid model (1 MB) */
private const val MIN_MODEL_SIZE_BYTES = 1_048_576L

/**
 * Dialog for importing a locally stored model file into the app's internal models directory.
 *
 * Features:
 * - File picker limited to supported extensions
 * - Validation: extension check, minimum size check, available storage check
 * - Non-blocking file copy with a progress indicator
 * - Specific error messages for each failure mode
 * - Calls [onImportSuccess] with the final internal path on completion
 */
@Composable
fun ModelImportDialog(
    onDismiss: () -> Unit,
    onImportSuccess: (internalPath: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf("") }
    var isImporting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // File picker — allows any file type so the user can choose from their file manager.
    // Extension validation happens after selection.
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        errorMessage = null
        val name = resolveFileName(context, uri)
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in SUPPORTED_EXTENSIONS) {
            errorMessage = "Unsupported file type '.$ext'. Supported: ${SUPPORTED_EXTENSIONS.joinToString()}"
            return@rememberLauncherForActivityResult
        }
        selectedUri = uri
        selectedFileName = name
    }

    AlertDialog(
        onDismissRequest = { if (!isImporting) onDismiss() },
        icon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
        title = { Text("Import Local Model") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Pick a .gguf, .task, .tflite, .litertlm, or .literlm file from your device's storage.",
                    style = MaterialTheme.typography.bodyMedium
                )

                OutlinedButton(
                    onClick = { filePicker.launch("*/*") },
                    enabled = !isImporting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (selectedFileName.isEmpty()) "Choose file…" else selectedFileName)
                }

                AnimatedVisibility(visible = isImporting) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text("Copying to internal storage…", style = MaterialTheme.typography.bodySmall)
                    }
                }

                AnimatedVisibility(visible = errorMessage != null) {
                    Text(
                        text = errorMessage ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = selectedUri != null && !isImporting,
                onClick = {
                    val uri = selectedUri ?: return@Button
                    isImporting = true
                    errorMessage = null
                    scope.launch {
                        val result = importModelFile(context, uri, selectedFileName)
                        isImporting = false
                        result.fold(
                            onSuccess = { path ->
                                Log.i(TAG, "Model imported successfully: $path")
                                onImportSuccess(path)
                            },
                            onFailure = { e ->
                                Log.e(TAG, "Model import failed", e)
                                errorMessage = e.message ?: "Import failed"
                            }
                        )
                    }
                }
            ) { Text("Import") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isImporting) { Text("Cancel") }
        }
    )
}

// ──────────────────────────────── Helpers ────────────────────────────────────

/**
 * Resolves a human-readable filename from a content [Uri].
 * Falls back to the last path segment if the cursor returns nothing.
 */
private fun resolveFileName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1) return cursor.getString(nameIndex)
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "model.gguf"
}

/**
 * Copies the model file identified by [uri] into the app's internal `models/` directory.
 *
 * Validates:
 * 1. File is not empty or too small
 * 2. There is enough free space on the internal storage
 *
 * Returns a [Result] with the absolute path of the copied file on success,
 * or a descriptive exception on failure.
 */
private suspend fun importModelFile(
    context: Context,
    uri: Uri,
    displayName: String
): Result<String> = withContext(Dispatchers.IO) {
    try {
        val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
        val destFile = File(modelsDir, displayName)

        // Check source file size first
        val sourceSize = context.contentResolver.openFileDescriptor(uri, "r")?.use {
            it.statSize
        } ?: 0L

        if (sourceSize < MIN_MODEL_SIZE_BYTES) {
            return@withContext Result.failure(
                IOException("File is too small (${sourceSize / 1024} KB). Minimum size is ${MIN_MODEL_SIZE_BYTES / 1024 / 1024} MB.")
            )
        }

        // Check available internal storage
        val freeBytes = context.filesDir.usableSpace
        if (freeBytes < sourceSize * 1.05) { // 5% headroom
            val needed = sourceSize / 1024 / 1024
            val available = freeBytes / 1024 / 1024
            return@withContext Result.failure(
                IOException("Not enough storage. Need ${needed} MB but only ${available} MB available.")
            )
        }

        // Stream copy
        context.contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output, bufferSize = 256 * 1024)
            }
        } ?: return@withContext Result.failure(IOException("Could not open source file for reading."))

        Result.success(destFile.absolutePath)

    } catch (e: IOException) {
        Result.failure(IOException("Copy failed: ${e.message}", e))
    } catch (e: SecurityException) {
        Result.failure(IOException("Permission denied reading the selected file.", e))
    } catch (e: Exception) {
        Result.failure(IOException("Unexpected error: ${e.message}", e))
    }
}
