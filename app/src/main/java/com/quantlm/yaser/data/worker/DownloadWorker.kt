package com.quantlm.yaser.data.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.quantlm.yaser.data.worker.DownloadWorkerConstants.KEY_DOWNLOADED_BYTES
import com.quantlm.yaser.data.worker.DownloadWorkerConstants.KEY_DOWNLOAD_RATE
import com.quantlm.yaser.data.worker.DownloadWorkerConstants.KEY_ERROR_MESSAGE
import com.quantlm.yaser.data.worker.DownloadWorkerConstants.KEY_EXTRA_DATA_FILE_NAMES
import com.quantlm.yaser.data.worker.DownloadWorkerConstants.KEY_EXTRA_DATA_SIZES
import com.quantlm.yaser.data.worker.DownloadWorkerConstants.KEY_EXTRA_DATA_URLS
import com.quantlm.yaser.data.worker.DownloadWorkerConstants.KEY_MODEL_ACCESS_TOKEN
import com.quantlm.yaser.data.worker.DownloadWorkerConstants.KEY_MODEL_FILE_NAME
import com.quantlm.yaser.data.worker.DownloadWorkerConstants.KEY_MODEL_ID
import com.quantlm.yaser.data.worker.DownloadWorkerConstants.KEY_MODEL_NAME
import com.quantlm.yaser.data.worker.DownloadWorkerConstants.KEY_MODEL_TOTAL_BYTES
import com.quantlm.yaser.data.worker.DownloadWorkerConstants.KEY_MODEL_URL
import com.quantlm.yaser.data.worker.DownloadWorkerConstants.KEY_REMAINING_MS
import com.quantlm.yaser.data.worker.DownloadWorkerConstants.KEY_TOTAL_BYTES
import com.quantlm.yaser.data.worker.DownloadWorkerConstants.NOTIFICATION_CHANNEL_ID
import com.quantlm.yaser.data.worker.DownloadWorkerConstants.NOTIFICATION_CHANNEL_NAME
import com.quantlm.yaser.data.worker.DownloadWorkerConstants.TMP_FILE_EXT
import com.quantlm.yaser.data.diagnostics.AppEventLogger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile

private const val TAG = "DownloadWorker"
private const val BUFFER_SIZE = 256 * 1024 // 256KB buffer
private const val PROGRESS_UPDATE_INTERVAL_MS = 500L
private const val MAX_RETRIES = 5
private const val RETRY_DELAY_MS = 3000L
private const val MODEL_MIN_BYTES = 1_000_000L
private const val SIZE_TOLERANCE_RATIO = 0.01f

/**
 * Data class representing a file to download
 */
data class DownloadFile(
    val url: String,
    val fileName: String,
    val sizeInBytes: Long = 0L
)

/**
 * WorkManager-based background download worker.
 * This is the SINGLE source of download logic for the entire app.
 * Supports:
 * - Resume capability for interrupted downloads
 * - Progress reporting via setProgress()
 * - Foreground notifications
 * - Vision model support (main + mmproj files)
 * - Automatic retry on failure
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    private val modelsDir = File(context.filesDir, "models").apply {
        if (!exists()) mkdirs()
    }
    
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    private val notificationId: Int = params.id.hashCode()
    
    // OkHttp client optimized for large file downloads
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS) // No overall timeout
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    
    init {
        createNotificationChannel()
    }
    
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows model download progress"
        }
        notificationManager.createNotificationChannel(channel)
    }
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return@withContext Result.failure()
        val modelName = inputData.getString(KEY_MODEL_NAME) ?: "Model"
        val fileUrl = inputData.getString(KEY_MODEL_URL) ?: return@withContext Result.failure()
        val fileName = inputData.getString(KEY_MODEL_FILE_NAME) ?: return@withContext Result.failure()
        val accessToken = inputData.getString(KEY_MODEL_ACCESS_TOKEN)
        val totalBytes = inputData.getLong(KEY_MODEL_TOTAL_BYTES, 0L)
        
        // Parse extra data files (for vision models - mmproj)
        val extraUrlsRaw = inputData.getString(KEY_EXTRA_DATA_URLS)
        val extraFileNamesRaw = inputData.getString(KEY_EXTRA_DATA_FILE_NAMES)
        val extraSizesRaw = inputData.getString(KEY_EXTRA_DATA_SIZES)
        
        Log.d(TAG, "Extra files raw data - URLs: '$extraUrlsRaw', FileNames: '$extraFileNamesRaw', Sizes: '$extraSizesRaw'")
        
        val extraUrls = extraUrlsRaw?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
        val extraFileNames = extraFileNamesRaw?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
        val extraSizes = extraSizesRaw?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList()
        
        Log.i(TAG, "Parsed extra files: ${extraUrls.size} URLs, ${extraFileNames.size} fileNames, ${extraSizes.size} sizes")
        
        // Build list of all files to download
        val allFiles = mutableListOf<DownloadFile>()
        allFiles.add(DownloadFile(url = fileUrl, fileName = fileName, sizeInBytes = totalBytes))
        
        for (i in extraUrls.indices) {
            val extraFileName = extraFileNames.getOrNull(i) ?: continue
            val extraSize = extraSizes.getOrNull(i) ?: 0L
            Log.i(TAG, "Adding extra file: $extraFileName (size: $extraSize bytes)")
            allFiles.add(DownloadFile(url = extraUrls[i], fileName = extraFileName, sizeInBytes = extraSize))
        }
        
        Log.i(TAG, "Starting download for $modelName with ${allFiles.size} file(s): ${allFiles.map { it.fileName }}")
        AppEventLogger.info(
            component = TAG,
            action = "download_start",
            details = "modelId=$modelId, modelName=$modelName, files=${allFiles.map { it.fileName }}"
        )
        
        return@withContext try {
            // Set as foreground service immediately
            setForeground(createForegroundInfo(0, modelName))
            
            // Calculate overall total bytes (may need fetching from server)
            var overallTotalBytes = allFiles.sumOf { it.sizeInBytes }
            
            // If we don't have total size, fetch it from server
            if (overallTotalBytes <= 0) {
                overallTotalBytes = allFiles.sumOf { file ->
                    getRemoteFileSize(file.url, accessToken)
                }
                Log.i(TAG, "Fetched total size from server: $overallTotalBytes bytes")
            }
            
            // Single shared byte counter for ALL files in this work item. The
            // parallel extra-file downloads each add to it as they stream, so
            // every progress report is monotonic — the old per-file derivation
            // made concurrent reports fight and the progress bar jump around.
            var initialBytes = 0L

            // Check for already downloaded bytes (resume support)
            for (file in allFiles) {
                val tempFile = File(modelsDir, "${file.fileName}.$TMP_FILE_EXT")
                val finalFile = File(modelsDir, file.fileName)
                if (finalFile.exists() && finalFile.length() > 0) {
                    // File already complete
                    initialBytes += finalFile.length()
                } else if (tempFile.exists()) {
                    initialBytes += tempFile.length()
                }
            }
            val totalProgress = AtomicLong(initialBytes)

            // Report initial progress
            reportProgress(totalProgress.get(), overallTotalBytes, 0L, 0L)
            
            // Phase 1: Download the primary model file (tracked for overall progress)
            val primaryFile = allFiles.first()
            Log.i(TAG, "Phase 1: Downloading primary file: ${primaryFile.fileName}")
            val primaryResult = downloadFileWithRetry(
                downloadFile = primaryFile,
                accessToken = accessToken,
                modelName = modelName,
                totalProgress = totalProgress,
                overallTotalBytes = overallTotalBytes
            )
            when (primaryResult) {
                is DownloadResult.Success -> {
                    Log.i(TAG, "Primary file download complete: ${primaryFile.fileName}")
                    AppEventLogger.info(
                        component = TAG,
                        action = "download_primary_complete",
                        details = "modelName=$modelName, file=${primaryFile.fileName}"
                    )
                }
                is DownloadResult.Failure -> {
                    Log.e(TAG, "Primary file download failed: ${primaryResult.error}")
                    AppEventLogger.error(
                        component = TAG,
                        action = "download_primary_failed",
                        details = "modelName=$modelName, file=${primaryFile.fileName}, reason=${primaryResult.error}"
                    )
                    return@withContext Result.failure(
                        Data.Builder().putString(KEY_ERROR_MESSAGE, primaryResult.error).build()
                    )
                }
            }

            // Fix [2.2]: extra mmproj/tokenizer files download in parallel (primary still first for stability)
            val extraFiles = allFiles.drop(1)
            if (extraFiles.isNotEmpty()) {
                Log.i(TAG, "Phase 2: Downloading ${extraFiles.size} extra file(s) in parallel")
                val extraResults = coroutineScope {
                    extraFiles.mapIndexed { index, extraFile ->
                        async {
                            Log.i(TAG, "Starting parallel download: ${extraFile.fileName}")
                            downloadFileWithRetry(
                                downloadFile = extraFile,
                                accessToken = accessToken,
                                modelName = "$modelName (extra ${index + 1}/${extraFiles.size})",
                                totalProgress = totalProgress,
                                overallTotalBytes = overallTotalBytes
                            )
                        }
                    }.awaitAll()
                }
                val failedExtra = extraResults.filterIsInstance<DownloadResult.Failure>().firstOrNull()
                if (failedExtra != null) {
                    Log.e(TAG, "Extra file download failed: ${failedExtra.error}")
                    AppEventLogger.error(
                        component = TAG,
                        action = "download_extra_failed",
                        details = "modelName=$modelName, reason=${failedExtra.error}"
                    )
                    return@withContext Result.failure(
                        Data.Builder().putString(KEY_ERROR_MESSAGE, failedExtra.error).build()
                    )
                }
                Log.i(TAG, "All extra files downloaded successfully")
                AppEventLogger.info(
                    component = TAG,
                    action = "download_extra_complete",
                    details = "modelName=$modelName, extraCount=${extraFiles.size}"
                )
            }
            
            Log.i(TAG, "All downloads completed for $modelName")
            AppEventLogger.info(
                component = TAG,
                action = "download_complete",
                details = "modelName=$modelName, totalBytes=$overallTotalBytes"
            )
            
            // Show completion notification
            showCompletionNotification(modelName)
            
            Result.success()
            
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for $modelName", e)
            AppEventLogger.error(
                component = TAG,
                action = "download_failed_exception",
                details = "modelName=$modelName, reason=${e.message ?: "unknown"}",
                throwable = e
            )
            Result.failure(
                Data.Builder()
                    .putString(KEY_ERROR_MESSAGE, e.message ?: "Unknown error")
                    .build()
            )
        }
    }
    
    private sealed class DownloadResult {
        object Success : DownloadResult()
        data class Failure(val error: String) : DownloadResult()
    }

    /**
     * Download a single file with retry logic and resume support.
     * Streams its byte count into [totalProgress], the work-item-wide counter
     * shared with any concurrently downloading files.
     */
    private suspend fun downloadFileWithRetry(
        downloadFile: DownloadFile,
        accessToken: String?,
        modelName: String,
        totalProgress: AtomicLong,
        overallTotalBytes: Long
    ): DownloadResult {
        var retryCount = 0
        var lastError: String = "Unknown error"

        val destinationFile = File(modelsDir, downloadFile.fileName)
        val tempFile = File(modelsDir, "${downloadFile.fileName}.$TMP_FILE_EXT")

        // Check if already downloaded
        if (destinationFile.exists() && destinationFile.length() > 0) {
            val expectedSize = downloadFile.sizeInBytes
            if (expectedSize <= 0 || destinationFile.length() >= expectedSize * 0.99) {
                Log.i(TAG, "File already exists and appears complete: ${downloadFile.fileName}")
                return DownloadResult.Success
            }
        }
        
        while (retryCount < MAX_RETRIES) {
            try {
                var existingBytes = if (tempFile.exists()) tempFile.length() else 0L

                if (retryCount == 0) {
                    AppEventLogger.debug(
                        component = TAG,
                        action = "file_download_attempt",
                        details = "file=${downloadFile.fileName}, resumeBytes=$existingBytes"
                    )
                }
                
                // Validate temp file size
                if (downloadFile.sizeInBytes > 0 && existingBytes > downloadFile.sizeInBytes) {
                    Log.w(TAG, "Temp file corrupted (larger than expected), deleting")
                    tempFile.delete()
                    // Those bytes were counted (initial scan or earlier attempt).
                    totalProgress.addAndGet(-existingBytes)
                    existingBytes = 0L
                }
                
                if (retryCount > 0) {
                    Log.i(TAG, "Retry $retryCount/$MAX_RETRIES for ${downloadFile.fileName}, resuming from $existingBytes bytes")
                    AppEventLogger.warn(
                        component = TAG,
                        action = "file_download_retry",
                        details = "file=${downloadFile.fileName}, retry=$retryCount/$MAX_RETRIES, resumeBytes=$existingBytes"
                    )
                    delay(RETRY_DELAY_MS * retryCount)
                }
                
                // Build request with resume support
                val requestBuilder = Request.Builder()
                    .url(downloadFile.url)
                    .addHeader("Connection", "keep-alive")
                    .addHeader("Accept-Encoding", "identity")
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                
                if (accessToken != null) {
                    requestBuilder.addHeader("Authorization", "Bearer $accessToken")
                }
                
                if (existingBytes > 0) {
                    requestBuilder.addHeader("Range", "bytes=$existingBytes-")
                    Log.i(TAG, "Resuming from $existingBytes bytes")
                }
                
                val response = client.newCall(requestBuilder.build()).execute()
                
                // Handle HTTP 416 (Range Not Satisfiable)
                if (response.code == 416) {
                    response.close()
                    if (existingBytes > 0) {
                        // Temp file might be complete
                        if (tempFile.renameTo(destinationFile)) {
                            saveFileSizeMetadata(downloadFile.fileName, existingBytes)
                            Log.i(TAG, "Temp file was complete, renamed to final")
                            return DownloadResult.Success
                        }
                    }
                    // Delete and retry
                    tempFile.delete()
                    totalProgress.addAndGet(-existingBytes)
                    retryCount++
                    continue
                }
                
                if (!response.isSuccessful && response.code != 206) {
                    response.close()
                    lastError = "HTTP ${response.code}"
                    retryCount++
                    continue
                }
                
                val body = response.body
                if (body == null) {
                    response.close()
                    lastError = "Empty response body"
                    retryCount++
                    continue
                }

                val contentType = response.header("Content-Type")?.lowercase() ?: ""
                if (isLikelyModelBinary(downloadFile.fileName) && isErrorContentType(contentType)) {
                    val preview = try {
                        body.string().take(160)
                    } catch (_: Exception) {
                        ""
                    }
                    lastError = "Server returned non-model content type '$contentType'"
                    Log.e(TAG, "${downloadFile.fileName}: $lastError, preview='$preview'")
                    response.close()
                    retryCount++
                    continue
                }
                
                // Determine expected size
                val bodyLength = body.contentLength()
                val serverExpectedSize = when {
                    response.code == 206 && bodyLength > 0 -> existingBytes + bodyLength
                    bodyLength > 0 -> bodyLength
                    else -> 0L
                }
                val expectedSize = when {
                    serverExpectedSize > 0 -> serverExpectedSize
                    downloadFile.sizeInBytes > 0 -> downloadFile.sizeInBytes
                    else -> overallTotalBytes
                }
                
                // Setup file output
                val outputFile = RandomAccessFile(tempFile, "rw")
                if (existingBytes > 0 && response.code == 206) {
                    outputFile.seek(existingBytes)
                } else if (response.code == 200) {
                    outputFile.setLength(0)
                    outputFile.seek(0)
                    // Server ignored the Range header — the temp bytes we had
                    // counted are being overwritten from scratch.
                    totalProgress.addAndGet(-existingBytes)
                    existingBytes = 0L
                }
                
                val inputStream = body.byteStream()
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                var fileDownloadedBytes = existingBytes
                var lastProgressUpdate = System.currentTimeMillis()
                var lastSpeedCalcTime = System.currentTimeMillis()
                var lastSpeedCalcBytes = fileDownloadedBytes
                var currentSpeed = 0L
                
                try {
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        // Check if cancelled
                        if (isStopped) {
                            outputFile.close()
                            inputStream.close()
                            response.close()
                            Log.i(TAG, "Download cancelled: ${downloadFile.fileName}")
                            return DownloadResult.Failure("Cancelled")
                        }
                        
                        outputFile.write(buffer, 0, bytesRead)
                        fileDownloadedBytes += bytesRead
                        totalProgress.addAndGet(bytesRead.toLong())

                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastProgressUpdate > PROGRESS_UPDATE_INTERVAL_MS) {
                            // Calculate speed
                            val timeDelta = currentTime - lastSpeedCalcTime
                            if (timeDelta > 0) {
                                val bytesDelta = fileDownloadedBytes - lastSpeedCalcBytes
                                currentSpeed = (bytesDelta * 1000) / timeDelta
                                lastSpeedCalcTime = currentTime
                                lastSpeedCalcBytes = fileDownloadedBytes
                            }

                            // Total downloaded across all files — the shared
                            // counter, so concurrent reporters stay monotonic.
                            val totalDownloaded = totalProgress.get()

                            // Calculate ETA
                            val remainingBytes = overallTotalBytes - totalDownloaded
                            val remainingMs = if (currentSpeed > 0) (remainingBytes * 1000) / currentSpeed else 0L
                            
                            // Report progress
                            reportProgress(totalDownloaded, overallTotalBytes, currentSpeed, remainingMs)
                            
                            // Update notification
                            val progress = if (overallTotalBytes > 0) {
                                (totalDownloaded * 100 / overallTotalBytes).toInt()
                            } else 0
                            setForeground(createForegroundInfo(progress, modelName))
                            
                            lastProgressUpdate = currentTime
                        }
                    }
                } finally {
                    outputFile.close()
                    inputStream.close()
                    response.close()
                }
                
                // Verify download
                val finalSize = tempFile.length()
                Log.i(TAG, "Download finished: $fileDownloadedBytes bytes, temp file: $finalSize bytes")
                AppEventLogger.info(
                    component = TAG,
                    action = "file_download_complete",
                    details = "file=${downloadFile.fileName}, bytes=$finalSize"
                )

                val sizeTolerance = (expectedSize * SIZE_TOLERANCE_RATIO).toLong().coerceAtLeast(64 * 1024L)
                if (expectedSize > 0 && finalSize + sizeTolerance < expectedSize) {
                    lastError = "Incomplete download for ${downloadFile.fileName}: expected=$expectedSize, got=$finalSize"
                    Log.w(TAG, lastError)
                    retryCount++
                    continue
                }

                if (isLikelyModelBinary(downloadFile.fileName) && finalSize < MODEL_MIN_BYTES) {
                    lastError = "Downloaded file too small for model binary: ${downloadFile.fileName} ($finalSize bytes)"
                    Log.w(TAG, lastError)
                    retryCount++
                    continue
                }

                if (!isBinaryStructureValid(tempFile, downloadFile.fileName)) {
                    lastError = "Downloaded file failed binary structure validation: ${downloadFile.fileName}"
                    Log.w(TAG, lastError)
                    retryCount++
                    continue
                }
                
                // Rename temp to final
                if (destinationFile.exists()) destinationFile.delete()
                if (tempFile.renameTo(destinationFile)) {
                    saveFileSizeMetadata(downloadFile.fileName, finalSize)
                    Log.i(TAG, "Successfully renamed to final file: ${downloadFile.fileName}")
                    return DownloadResult.Success
                } else {
                    // Try copy instead
                    try {
                        tempFile.copyTo(destinationFile, overwrite = true)
                        tempFile.delete()
                        saveFileSizeMetadata(downloadFile.fileName, finalSize)
                        return DownloadResult.Success
                    } catch (e: Exception) {
                        lastError = "Failed to finalize download: ${e.message}"
                        retryCount++
                    }
                }
                
            } catch (e: java.net.SocketTimeoutException) {
                lastError = "Connection timeout"
                Log.w(TAG, "Socket timeout, will retry", e)
                retryCount++
            } catch (e: java.net.SocketException) {
                lastError = "Connection error: ${e.message}"
                Log.w(TAG, "Socket error, will retry", e)
                retryCount++
            } catch (e: java.io.IOException) {
                lastError = "IO error: ${e.message}"
                Log.w(TAG, "IO error, will retry", e)
                retryCount++
            } catch (e: Exception) {
                lastError = e.message ?: "Unknown error"
                Log.e(TAG, "Unexpected error", e)
                retryCount++
            }
        }
        
        return DownloadResult.Failure("Download failed after $MAX_RETRIES retries: $lastError")
    }

    private fun isLikelyModelBinary(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".gguf") ||
            lower.endsWith(".task") ||
            lower.endsWith(".tflite") ||
            lower.endsWith(".litertlm") ||
            lower.endsWith(".literlm")
    }

    private fun hasMagicPrefix(file: File, expected: ByteArray): Boolean {
        return try {
            file.inputStream().use { input ->
                val actual = ByteArray(expected.size)
                if (input.read(actual) < expected.size) return@use false
                actual.contentEquals(expected)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isBinaryStructureValid(file: File, fileName: String): Boolean {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".gguf") -> hasMagicPrefix(file, byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
            lower.endsWith(".task") -> {
                if (!hasMagicPrefix(file, byteArrayOf('P'.code.toByte(), 'K'.code.toByte()))) {
                    false
                } else {
                    try {
                        ZipFile(file).use { zip -> zip.entries().hasMoreElements() }
                    } catch (_: Exception) {
                        false
                    }
                }
            }
            else -> true
        }
    }

    private fun isErrorContentType(contentType: String): Boolean {
        if (contentType.isBlank()) return false
        return contentType.contains("text/html") ||
            contentType.contains("text/plain") ||
            contentType.contains("application/json")
    }
    
    /**
     * Get file size from server via HEAD request
     */
    private fun getRemoteFileSize(url: String, accessToken: String?): Long {
        return try {
            val requestBuilder = Request.Builder()
                .url(url)
                .head()
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
            
            if (accessToken != null) {
                requestBuilder.addHeader("Authorization", "Bearer $accessToken")
            }
            
            val response = client.newCall(requestBuilder.build()).execute()
            val size = response.header("Content-Length")?.toLongOrNull() ?: 0L
            response.close()
            size
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get file size for $url: ${e.message}")
            0L
        }
    }
    
    /**
     * Save file size metadata for future reference
     */
    private fun saveFileSizeMetadata(fileName: String, size: Long) {
        try {
            val metaFile = File(modelsDir, "$fileName.size")
            metaFile.writeText(size.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save size metadata: ${e.message}")
        }
    }
    
    /**
     * Report progress via setProgress()
     */
    private suspend fun reportProgress(
        downloadedBytes: Long,
        totalBytes: Long,
        speedBytesPerSecond: Long,
        remainingMs: Long
    ) {
        setProgress(
            Data.Builder()
                .putLong(KEY_DOWNLOADED_BYTES, downloadedBytes)
                .putLong(KEY_TOTAL_BYTES, totalBytes)
                .putLong(KEY_DOWNLOAD_RATE, speedBytesPerSecond)
                .putLong(KEY_REMAINING_MS, remainingMs)
                .build()
        )
    }
    
    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo(0)
    }
    
    private fun createForegroundInfo(progress: Int, modelName: String? = null): ForegroundInfo {
        val title = modelName?.let { "Downloading \"$it\"" } ?: "Downloading model"
        val content = "Download in progress: $progress%"
        
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        
        val pendingIntent = intent?.let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .apply { pendingIntent?.let { setContentIntent(it) } }
            .build()
        
        return ForegroundInfo(
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }
    
    private fun showCompletionNotification(modelName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        
        val pendingIntent = intent?.let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE
            )
        }
        
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Download Complete")
            .setContentText("$modelName is ready to use")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .apply { pendingIntent?.let { setContentIntent(it) } }
            .build()
        
        notificationManager.notify(notificationId + 1, notification)
    }
}
