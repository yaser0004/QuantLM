package com.quantlm.yaser.data.repository

import android.content.Context
import android.util.Log
import com.quantlm.yaser.data.notification.DownloadNotificationManager
import com.quantlm.yaser.domain.model.DownloadState
import com.quantlm.yaser.domain.model.DownloadableModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelDownloadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notificationManager: DownloadNotificationManager
) {
    companion object {
        private const val TAG = "ModelDownloadRepo"
        private const val TEMP_SUFFIX = ".tmp"
        private const val SIZE_SUFFIX = ".size"
        private const val BUFFER_SIZE = 256 * 1024 // 256KB buffer for better throughput on large files
        private const val PROGRESS_UPDATE_INTERVAL_MS = 500L // Update UI every 500ms (reduces overhead)
        private const val MAX_DOWNLOAD_RETRIES = 5 // Increased retry count for connection failures
        private const val RETRY_DELAY_MS = 3000L // 3 second base delay between retries
        private const val STALL_TIMEOUT_MS = 60_000L // 60 seconds - consider download stalled if no data received
    }
    
    // Simplified OkHttp client for large file downloads
    // Using default settings that work with all servers
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS) // No overall timeout for large downloads
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    
    private val modelsDir = File(context.filesDir, "models").apply {
        if (!exists()) mkdirs()
    }
    
    private val activeDownloads = mutableMapOf<String, DownloadControl>()
    
    data class DownloadControl(
        var isActive: Boolean = true,
        var isPaused: Boolean = false
    )
    
    // Cache for pre-fetched file sizes (URL -> size)
    private val fileSizeCache = mutableMapOf<String, Long>()
    
    /**
     * Fetch file size from server via HTTP HEAD request.
     * Returns the Content-Length or -1 if unavailable.
     * Public for pre-fetching sizes before download.
     */
    suspend fun getRemoteFileSize(url: String): Long {
        // Check cache first
        fileSizeCache[url]?.let { return it }
        
        return try {
            val request = Request.Builder()
                .url(url)
                .head()
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .addHeader("Accept", "*/*")
                .build()
            val response = client.newCall(request).execute()
            
            // Check if request was successful
            if (!response.isSuccessful) {
                Log.w(TAG, "HEAD request failed for $url: HTTP ${response.code}")
                response.close()
                return -1L
            }
            
            val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
            response.close()
            Log.d(TAG, "Remote file size for $url: $contentLength bytes")
            
            // Cache the result if valid
            if (contentLength > 0) {
                fileSizeCache[url] = contentLength
            }
            contentLength
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get remote file size for $url: ${e.message}", e)
            -1L
        }
    }
    
    /**
     * Pre-fetch file sizes for a model (main model + mmproj if vision).
     * Returns a Pair of (mainSize, mmprojSize). Uses cached values when available.
     */
    suspend fun prefetchModelSizes(model: DownloadableModel): Pair<Long, Long> {
        val mainSize = getRemoteFileSize(model.downloadUrl)
        val mmprojSize = model.mmprojUrl?.let { getRemoteFileSize(it) } ?: 0L
        return Pair(mainSize, mmprojSize)
    }
    
    /**
     * Save file size metadata for a downloaded file.
     */
    private fun saveFileSizeMetadata(fileName: String, size: Long) {
        try {
            val metaFile = File(modelsDir, fileName + SIZE_SUFFIX)
            metaFile.writeText(size.toString())
            Log.d(TAG, "Saved size metadata for $fileName: $size bytes")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save size metadata for $fileName", e)
        }
    }
    
    /**
     * Read stored file size metadata for a file.
     * Returns the stored size or -1 if not available.
     */
    fun getStoredFileSize(fileName: String): Long {
        return try {
            val metaFile = File(modelsDir, fileName + SIZE_SUFFIX)
            if (metaFile.exists()) {
                metaFile.readText().trim().toLongOrNull() ?: -1L
            } else -1L
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read size metadata for $fileName", e)
            -1L
        }
    }
    
    /**
     * Get effective file size - from model definition, stored metadata, or fetch from server.
     */
    private suspend fun getEffectiveFileSize(url: String, fileName: String, definedSize: Long): Long {
        // Priority: defined size > stored metadata > remote fetch
        if (definedSize > 0) return definedSize
        
        val storedSize = getStoredFileSize(fileName)
        if (storedSize > 0) return storedSize
        
        return getRemoteFileSize(url)
    }
    
    fun downloadModel(model: DownloadableModel): Flow<DownloadState> = flow {
        val control = DownloadControl()
        activeDownloads[model.id] = control
        
        val destinationFile = File(modelsDir, model.fileName)
        val tempFile = File(modelsDir, model.fileName + TEMP_SUFFIX)
        
        try {
            // Get effective file size (from model, metadata, or server)
            // If size is unknown, we'll get it from the response body later
            var expectedSize = getEffectiveFileSize(model.downloadUrl, model.fileName, model.fileSize)
            
            Log.i(TAG, "Starting download for ${model.name}, definedSize=${model.fileSize}, expectedSize=$expectedSize")
            
            // Check if final file already exists and is complete (only when we know the size)
            if (expectedSize > 0 && destinationFile.exists() && destinationFile.length() == expectedSize) {
                Log.i(TAG, "Model already downloaded: ${model.name}")
                emit(DownloadState.Success)
                activeDownloads.remove(model.id)
                return@flow
            }
            
            // Also accept files within 1% tolerance (in case of minor metadata discrepancies)
            if (expectedSize > 0 && destinationFile.exists()) {
                val sizeDiff = kotlin.math.abs(destinationFile.length() - expectedSize).toFloat() / expectedSize.toFloat()
                if (sizeDiff < 0.01f) {
                    Log.i(TAG, "Model file exists and is within size tolerance: ${model.fileName}")
                    emit(DownloadState.Success)
                    activeDownloads.remove(model.id)
                    return@flow
                }
            }
            
            // Retry loop for connection failures
            var retryCount = 0
            var lastError: Exception? = null
            
            while (retryCount < MAX_DOWNLOAD_RETRIES && control.isActive) {
                try {
                    // Check existing bytes each iteration (resume support)
                    var existingBytes = if (tempFile.exists()) tempFile.length() else 0L
                    
                    // If temp file is larger than expected (and we know expected size), it's corrupted - delete it
                    if (expectedSize > 0 && existingBytes > expectedSize) {
                        Log.w(TAG, "Temp file corrupted (${existingBytes} > ${expectedSize}), deleting and restarting")
                        tempFile.delete()
                        existingBytes = 0L
                    }
                    
                    if (retryCount > 0) {
                        Log.i(TAG, "Download retry ${retryCount}/$MAX_DOWNLOAD_RETRIES for ${model.name}, resuming from $existingBytes bytes")
                        kotlinx.coroutines.delay(RETRY_DELAY_MS * retryCount) // Exponential backoff
                    } else {
                        Log.i(TAG, "Starting download for ${model.name}, resuming from $existingBytes bytes, expected size: $expectedSize")
                    }
                    
                    // Emit initial progress (use 0 if size unknown)
                    val initialProgress = if (expectedSize > 0) existingBytes.toFloat() / expectedSize.toFloat() else 0f
                    val displaySize = if (expectedSize > 0) expectedSize else 0L
                    emit(DownloadState.Downloading(
                        initialProgress,
                        existingBytes,
                        displaySize,
                        speedBytesPerSecond = 0L,
                        etaSeconds = -1L
                    ))
                    
                    // Build request with Range header for resume support and keep-alive
                    val requestBuilder = Request.Builder()
                        .url(model.downloadUrl)
                        .addHeader("Connection", "keep-alive")
                        .addHeader("Accept-Encoding", "identity") // Avoid compression for large binary files
                        .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    if (existingBytes > 0) {
                        requestBuilder.addHeader("Range", "bytes=$existingBytes-")
                        Log.i(TAG, "Resuming download with Range: bytes=$existingBytes-")
                    }
                    
                    val call = client.newCall(requestBuilder.build())
                    val response = call.execute()
                    
                    // Handle HTTP 416 (Range Not Satisfiable) - temp file is already complete or corrupted
                    if (response.code == 416) {
                        Log.w(TAG, "HTTP 416: Range not satisfiable. Temp file size: $existingBytes, Expected: ${expectedSize}")
                        response.close()
                        
                        // Check if temp file size matches expected
                        if (existingBytes >= expectedSize) {
                            // File is complete, just rename it
                            if (tempFile.renameTo(destinationFile)) {
                                saveFileSizeMetadata(model.fileName, expectedSize)
                                Log.i(TAG, "Temp file was already complete, renamed to final")
                                emit(DownloadState.Success)
                            } else {
                                emit(DownloadState.Error("Failed to finalize complete download"))
                            }
                        } else {
                            // Temp file is corrupted, delete and retry
                            Log.w(TAG, "HTTP 416 with incomplete file, deleting and retrying")
                            tempFile.delete()
                            retryCount++
                            continue
                        }
                        activeDownloads.remove(model.id)
                        return@flow
                    }
                    
                    // Accept both 200 (full content) and 206 (partial content)
                    if (!response.isSuccessful && response.code != 206) {
                        response.close()
                        Log.w(TAG, "Download HTTP error: ${response.code}")
                        retryCount++
                        if (retryCount >= MAX_DOWNLOAD_RETRIES) {
                            emit(DownloadState.Error("Download failed: HTTP ${response.code}"))
                            activeDownloads.remove(model.id)
                            return@flow
                        }
                        continue
                    }
                    
                    val body = response.body
                    if (body == null) {
                        response.close()
                        Log.w(TAG, "Empty response body, retrying")
                        retryCount++
                        continue
                    }
                    
                    // CRITICAL FIX: Use body.contentLength() as primary source of truth for file size
                    // This is more reliable than HEAD requests which may not return Content-Length
                    val bodyContentLength = body.contentLength()
                    var effectiveExpectedSize = expectedSize
                    
                    if (bodyContentLength > 0) {
                        // For 206 Partial Content, bodyContentLength is remaining bytes
                        // For 200 OK, it's the full file size
                        val serverReportedTotalSize = if (response.code == 206) {
                            bodyContentLength + existingBytes
                        } else {
                            bodyContentLength
                        }
                        
                        if (expectedSize <= 0 || kotlin.math.abs(expectedSize - serverReportedTotalSize) > expectedSize * 0.1) {
                            // Either we had no size or server size differs significantly
                            Log.i(TAG, "Updating expected size from $expectedSize to $serverReportedTotalSize (from response body)")
                            effectiveExpectedSize = serverReportedTotalSize
                            expectedSize = serverReportedTotalSize // Update outer scope for retries
                            
                            // Cache this for future reference
                            if (serverReportedTotalSize > 0) {
                                fileSizeCache[model.downloadUrl] = serverReportedTotalSize
                            }
                        }
                    } else if (expectedSize <= 0) {
                        // No content length from server AND no expected size - use chunked transfer fallback
                        Log.w(TAG, "Server did not provide Content-Length. Using model.fileSize=${model.fileSize} as fallback")
                        effectiveExpectedSize = if (model.fileSize > 0) model.fileSize else Long.MAX_VALUE
                    }
                    
                    // Update expectedSize reference for this download session
                    val finalExpectedSize = effectiveExpectedSize
                    
                    val inputStream = body.byteStream()
                    // Use RandomAccessFile for append support
                    val outputFile = RandomAccessFile(tempFile, "rw")
                    if (existingBytes > 0 && response.code == 206) {
                        // Resume: seek to end of existing file
                        outputFile.seek(existingBytes)
                    } else if (response.code == 200) {
                        // Server doesn't support resume, start fresh
                        outputFile.setLength(0)
                        outputFile.seek(0)
                        existingBytes = 0L
                    }
                    
                    val buffer = ByteArray(BUFFER_SIZE)
                    var downloadedBytes = existingBytes
                    var bytesRead: Int
                    var lastNotificationTime = 0L
                    
                    // Speed calculation variables
                    var lastSpeedCalcTime = System.currentTimeMillis()
                    var lastSpeedCalcBytes = existingBytes
                    var currentSpeed = 0L
                    var etaSeconds = -1L
                    
                    try {
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            // Check if download was cancelled
                            if (!control.isActive) {
                                outputFile.close()
                                inputStream.close()
                                response.close()
                                // Don't delete temp file - allow resume later
                                notificationManager.cancelNotification(model.id)
                                Log.i(TAG, "Download cancelled (temp file preserved): ${model.name}")
                                emit(DownloadState.Cancelled)
                                activeDownloads.remove(model.id)
                                return@flow
                            }
                            
                            // Check if download was paused - use delay instead of blocking sleep
                            while (control.isPaused && control.isActive) {
                                kotlinx.coroutines.delay(100)
                            }
                            
                            // If cancelled while paused
                            if (!control.isActive) {
                                outputFile.close()
                                inputStream.close()
                                response.close()
                                notificationManager.cancelNotification(model.id)
                                Log.i(TAG, "Download cancelled while paused: ${model.name}")
                                emit(DownloadState.Paused(downloadedBytes, finalExpectedSize))
                                activeDownloads.remove(model.id)
                                return@flow
                            }
                            
                            outputFile.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            
                            val progress = downloadedBytes.toFloat() / finalExpectedSize.toFloat()
                            
                            // Update notification and emit state at defined interval
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastNotificationTime > PROGRESS_UPDATE_INTERVAL_MS) {
                                // Calculate speed (bytes per second)
                                val timeDelta = currentTime - lastSpeedCalcTime
                                if (timeDelta > 0) {
                                    val bytesDelta = downloadedBytes - lastSpeedCalcBytes
                                    currentSpeed = (bytesDelta * 1000) / timeDelta // bytes per second
                                    
                                    // Calculate ETA
                                    val remainingBytes = finalExpectedSize - downloadedBytes
                                    etaSeconds = if (currentSpeed > 0) {
                                        remainingBytes / currentSpeed
                                    } else {
                                        -1L
                                    }
                                    
                                    lastSpeedCalcTime = currentTime
                                    lastSpeedCalcBytes = downloadedBytes
                                }
                                
                                notificationManager.showDownloadNotification(
                                    model.id,
                                    model.name,
                                    progress,
                                    downloadedBytes,
                                    finalExpectedSize,
                                    currentSpeed,
                                    etaSeconds
                                )
                                emit(DownloadState.Downloading(progress, downloadedBytes, finalExpectedSize, currentSpeed, etaSeconds))
                                lastNotificationTime = currentTime
                            }
                        }
                        
                        outputFile.close()
                        inputStream.close()
                        response.close()
                        
                        // Verify download completed successfully
                        val finalSize = tempFile.length()
                        val sizeTolerance = (finalExpectedSize * 0.01f).toLong() // 1% tolerance
                        
                        Log.i(TAG, "Download finished. Total downloaded: $downloadedBytes, Expected: $finalExpectedSize, Temp file size: $finalSize")
                        
                        if (finalSize >= finalExpectedSize || kotlin.math.abs(finalSize - finalExpectedSize) <= sizeTolerance) {
                            // Rename temp file to final file
                            if (tempFile.renameTo(destinationFile)) {
                                // Save file size metadata for future reference
                                saveFileSizeMetadata(model.fileName, finalExpectedSize)
                                Log.i(TAG, "Download completed successfully: ${model.name}")
                                notificationManager.showDownloadCompleteNotification(model.id, model.name)
                                emit(DownloadState.Success)
                                activeDownloads.remove(model.id)
                                return@flow
                            } else {
                                // Rename failed, try copy and delete
                                try {
                                    tempFile.copyTo(destinationFile, overwrite = true)
                                    tempFile.delete()
                                    saveFileSizeMetadata(model.fileName, finalExpectedSize)
                                    Log.i(TAG, "Download completed (via copy): ${model.name}")
                                    notificationManager.showDownloadCompleteNotification(model.id, model.name)
                                    emit(DownloadState.Success)
                                    activeDownloads.remove(model.id)
                                    return@flow
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to finalize download via copy", e)
                                    emit(DownloadState.Error("Failed to finalize download"))
                                    activeDownloads.remove(model.id)
                                    return@flow
                                }
                            }
                        } else {
                            // Download incomplete - stream ended prematurely, retry
                            Log.w(TAG, "Download incomplete: downloaded=$downloadedBytes, tempFileSize=$finalSize, expected=$finalExpectedSize, retrying...")
                            retryCount++
                            continue
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        Log.w(TAG, "Socket timeout during download, will retry", e)
                        lastError = e
                        try { outputFile.close() } catch (ce: Exception) { Log.w(TAG, "Failed to close output file during socket timeout cleanup", ce) }
                        try { inputStream.close() } catch (ce: Exception) { Log.w(TAG, "Failed to close input stream during socket timeout cleanup", ce) }
                        try { response.close() } catch (ce: Exception) { Log.w(TAG, "Failed to close response during socket timeout cleanup", ce) }
                        retryCount++
                        continue
                    } catch (e: java.net.SocketException) {
                        Log.w(TAG, "Socket error during download, will retry", e)
                        lastError = e
                        try { outputFile.close() } catch (ce: Exception) { Log.w(TAG, "Failed to close output file during socket error cleanup", ce) }
                        try { inputStream.close() } catch (ce: Exception) { Log.w(TAG, "Failed to close input stream during socket error cleanup", ce) }
                        try { response.close() } catch (ce: Exception) { Log.w(TAG, "Failed to close response during socket error cleanup", ce) }
                        retryCount++
                        continue
                    } catch (e: java.io.IOException) {
                        Log.w(TAG, "IO error during download, will retry", e)
                        lastError = e
                        try { outputFile.close() } catch (ce: Exception) { Log.w(TAG, "Failed to close output file during IO error cleanup", ce) }
                        try { inputStream.close() } catch (ce: Exception) { Log.w(TAG, "Failed to close input stream during IO error cleanup", ce) }
                        try { response.close() } catch (ce: Exception) { Log.w(TAG, "Failed to close response during IO error cleanup", ce) }
                        retryCount++
                        continue
                    }
                    
                } catch (e: java.net.SocketTimeoutException) {
                    Log.w(TAG, "Connection timeout, will retry", e)
                    lastError = e
                    retryCount++
                } catch (e: java.net.UnknownHostException) {
                    Log.w(TAG, "Network unavailable, will retry", e)
                    lastError = e
                    retryCount++
                } catch (e: java.net.SocketException) {
                    Log.w(TAG, "Socket error connecting, will retry", e)
                    lastError = e
                    retryCount++
                } catch (e: java.io.IOException) {
                    Log.w(TAG, "IO error connecting, will retry", e)
                    lastError = e
                    retryCount++
                }
            }
            
            // All retries exhausted
            val errorMsg = lastError?.message ?: "Unknown error after $MAX_DOWNLOAD_RETRIES retries"
            Log.e(TAG, "Download failed after $MAX_DOWNLOAD_RETRIES retries for ${model.name}: $errorMsg")
            notificationManager.showDownloadErrorNotification(model.id, model.name, errorMsg)
            emit(DownloadState.Error("Download failed: $errorMsg"))
            
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for ${model.name}", e)
            notificationManager.showDownloadErrorNotification(
                model.id,
                model.name,
                e.message ?: "Unknown error"
            )
            emit(DownloadState.Error("Download failed: ${e.message}"))
        } finally {
            activeDownloads.remove(model.id)
        }
    }.flowOn(Dispatchers.IO)
    
    fun cancelDownload(model: DownloadableModel, deletePartial: Boolean = false) {
        activeDownloads[model.id]?.isActive = false
        activeDownloads.remove(model.id)
        notificationManager.cancelNotification(model.id)
        // Only delete temp file if explicitly requested (keeps partial for resume)
        if (deletePartial) {
            val tempFile = File(modelsDir, model.fileName + TEMP_SUFFIX)
            if (tempFile.exists()) {
                tempFile.delete()
                Log.i(TAG, "Deleted partial download for: ${model.name}")
            }
        }
    }
    
    fun cancelDownload(modelId: String) {
        activeDownloads[modelId]?.isActive = false
        activeDownloads.remove(modelId)
        Log.i(TAG, "Cancelled download (temp preserved): $modelId")
    }
    
    fun pauseDownload(model: DownloadableModel) {
        activeDownloads[model.id]?.isPaused = true
        Log.i(TAG, "Paused download: ${model.name}")
    }
    
    fun pauseDownload(modelId: String) {
        activeDownloads[modelId]?.isPaused = true
        Log.i(TAG, "Paused download: $modelId")
    }
    
    fun resumeDownload(model: DownloadableModel) {
        activeDownloads[model.id]?.isPaused = false
        Log.i(TAG, "Resumed download: ${model.name}")
    }
    
    fun resumeDownload(modelId: String) {
        activeDownloads[modelId]?.isPaused = false
        Log.i(TAG, "Resumed download: $modelId")
    }
    
    fun isDownloadActive(model: DownloadableModel): Boolean {
        return activeDownloads.containsKey(model.id)
    }
    
    fun isDownloadPaused(model: DownloadableModel): Boolean {
        return activeDownloads[model.id]?.isPaused == true
    }
    
    fun getPartialDownloadSize(model: DownloadableModel): Long {
        val tempFile = File(modelsDir, model.fileName + TEMP_SUFFIX)
        return if (tempFile.exists()) tempFile.length() else 0L
    }
    
    fun isModelDownloaded(model: DownloadableModel): Boolean {
        val file = File(modelsDir, model.fileName)
        if (!file.exists() || file.length() == 0L) return false
        
        // Check main model - prioritize stored metadata (actual download size) over defined size
        val storedSize = getStoredFileSize(model.fileName)
        val expectedSize = if (storedSize > 0) storedSize else model.fileSize
        
        val mainModelComplete = if (expectedSize > 0) {
            // Allow up to 5% difference
            val actualSize = file.length()
            val sizeDiff = kotlin.math.abs(actualSize - expectedSize).toFloat() / expectedSize.toFloat()
            sizeDiff < 0.05f
        } else {
            // No expected size, just check file has reasonable content (> 1MB)
            file.length() > 1_000_000L
        }
        
        if (!mainModelComplete) return false
        
        // For vision models, also check mmproj file
        if (model.isVisionModel && model.mmprojFileName != null) {
            val mmprojFile = File(modelsDir, model.mmprojFileName)
            if (!mmprojFile.exists() || mmprojFile.length() == 0L) return false
            
            val mmprojStoredSize = getStoredFileSize(model.mmprojFileName)
            val mmprojExpectedSize = if (mmprojStoredSize > 0) mmprojStoredSize else model.mmprojFileSize
            
            val mmprojComplete = if (mmprojExpectedSize > 0) {
                val actualSize = mmprojFile.length()
                val sizeDiff = kotlin.math.abs(actualSize - mmprojExpectedSize).toFloat() / mmprojExpectedSize.toFloat()
                sizeDiff < 0.05f
            } else {
                mmprojFile.length() > 1_000_000L
            }
            
            return mmprojComplete
        }
        
        return true
    }
    
    fun deleteDownloadedModel(model: DownloadableModel): Boolean {
        val file = File(modelsDir, model.fileName)
        val tempFile = File(modelsDir, model.fileName + TEMP_SUFFIX)
        tempFile.delete() // Also delete temp file if exists
        
        // Also delete mmproj files for vision models
        if (model.isVisionModel && model.mmprojFileName != null) {
            val mmprojFile = File(modelsDir, model.mmprojFileName)
            val mmprojTempFile = File(modelsDir, model.mmprojFileName + TEMP_SUFFIX)
            mmprojTempFile.delete()
            mmprojFile.delete()
        }
        
        return if (file.exists()) {
            file.delete()
        } else {
            false
        }
    }
    
    fun getModelFilePath(model: DownloadableModel): String {
        return File(modelsDir, model.fileName).absolutePath
    }
    
    fun getMmprojFilePath(model: DownloadableModel): String? {
        if (!model.isVisionModel || model.mmprojFileName == null) return null
        return File(modelsDir, model.mmprojFileName).absolutePath
    }
    
    /**
     * Check if the mmproj file for a vision model needs to be downloaded
     */
    fun isMmprojDownloaded(model: DownloadableModel): Boolean {
        if (!model.isVisionModel || model.mmprojFileName == null) return true
        val mmprojFile = File(modelsDir, model.mmprojFileName)
        if (!mmprojFile.exists() || mmprojFile.length() == 0L) return false
        
        // Prioritize stored metadata (actual download size) over defined size
        val storedSize = getStoredFileSize(model.mmprojFileName)
        val expectedSize = if (storedSize > 0) storedSize else model.mmprojFileSize
        
        return if (expectedSize > 0) {
            // Allow up to 5% difference
            val actualSize = mmprojFile.length()
            val sizeDiff = kotlin.math.abs(actualSize - expectedSize).toFloat() / expectedSize.toFloat()
            sizeDiff < 0.05f
        } else {
            // No expected size, just check file has reasonable content (> 1MB)
            mmprojFile.length() > 1_000_000L
        }
    }
    
    /**
     * Download just the mmproj file for a vision model.
     * Includes automatic retry logic with resume capability for better reliability.
     */
    fun downloadMmproj(model: DownloadableModel): Flow<DownloadState> = flow {
        if (!model.isVisionModel || model.mmprojUrl == null || model.mmprojFileName == null) {
            emit(DownloadState.Error("Not a vision model or missing mmproj info"))
            return@flow
        }
        
        val mmprojId = "${model.id}_mmproj"
        val control = DownloadControl()
        activeDownloads[mmprojId] = control
        
        val destinationFile = File(modelsDir, model.mmprojFileName)
        val tempFile = File(modelsDir, model.mmprojFileName + TEMP_SUFFIX)
        
        try {
            // Get effective file size (from model, metadata, or server)
            val expectedSize = getEffectiveFileSize(model.mmprojUrl, model.mmprojFileName, model.mmprojFileSize)
            if (expectedSize <= 0) {
                emit(DownloadState.Error("Unable to determine mmproj file size from server"))
                activeDownloads.remove(mmprojId)
                return@flow
            }
            
            // Check if final file already exists and is complete
            if (destinationFile.exists() && destinationFile.length() == expectedSize) {
                Log.i(TAG, "Mmproj already downloaded: ${model.mmprojFileName}")
                emit(DownloadState.Success)
                activeDownloads.remove(mmprojId)
                return@flow
            }
            
            // Also accept files within 1% tolerance (in case of minor metadata discrepancies)
            if (destinationFile.exists()) {
                val sizeDiff = kotlin.math.abs(destinationFile.length() - expectedSize).toFloat() / expectedSize.toFloat()
                if (sizeDiff < 0.01f) {
                    Log.i(TAG, "Mmproj file exists and is within size tolerance: ${model.mmprojFileName}")
                    emit(DownloadState.Success)
                    activeDownloads.remove(mmprojId)
                    return@flow
                }
            }
            
            // Retry loop for connection failures
            var retryCount = 0
            var lastError: Exception? = null
            
            while (retryCount < MAX_DOWNLOAD_RETRIES && control.isActive) {
                try {
                    // Check existing bytes each iteration (resume support)
                    var existingBytes = if (tempFile.exists()) tempFile.length() else 0L
                    
                    if (existingBytes > expectedSize) {
                        Log.w(TAG, "Temp file larger than expected, deleting: $existingBytes > $expectedSize")
                        tempFile.delete()
                        existingBytes = 0L
                    }
                    
                    if (retryCount > 0) {
                        Log.i(TAG, "Mmproj download retry ${retryCount}/$MAX_DOWNLOAD_RETRIES for ${model.name}, resuming from $existingBytes bytes")
                        kotlinx.coroutines.delay(RETRY_DELAY_MS * retryCount) // Exponential backoff
                    } else {
                        Log.i(TAG, "Starting mmproj download for ${model.name}, resuming from $existingBytes bytes, expected size: $expectedSize")
                    }
                    
                    emit(DownloadState.Downloading(
                        existingBytes.toFloat() / expectedSize.toFloat(),
                        existingBytes,
                        expectedSize,
                        speedBytesPerSecond = 0L,
                        etaSeconds = -1L
                    ))
                    
                    val requestBuilder = Request.Builder()
                        .url(model.mmprojUrl)
                        .addHeader("Connection", "keep-alive")
                        .addHeader("Accept-Encoding", "identity")
                        .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    if (existingBytes > 0) {
                        requestBuilder.addHeader("Range", "bytes=$existingBytes-")
                    }
                    
                    val call = client.newCall(requestBuilder.build())
                    val response = call.execute()
                    
                    if (response.code == 416) {
                        response.close()
                        if (existingBytes >= expectedSize) {
                            if (tempFile.renameTo(destinationFile)) {
                                saveFileSizeMetadata(model.mmprojFileName, expectedSize)
                                Log.i(TAG, "Mmproj already complete (HTTP 416), renamed to final")
                                emit(DownloadState.Success)
                                activeDownloads.remove(mmprojId)
                                return@flow
                            } else {
                                emit(DownloadState.Error("Failed to finalize mmproj download"))
                                activeDownloads.remove(mmprojId)
                                return@flow
                            }
                        } else {
                            // Corrupted temp file, delete and retry
                            Log.w(TAG, "HTTP 416 with incomplete file, deleting and retrying")
                            tempFile.delete()
                            retryCount++
                            continue
                        }
                    }
                    
                    if (!response.isSuccessful && response.code != 206) {
                        response.close()
                        Log.w(TAG, "Mmproj download HTTP error: ${response.code}")
                        retryCount++
                        if (retryCount >= MAX_DOWNLOAD_RETRIES) {
                            emit(DownloadState.Error("Mmproj download failed: HTTP ${response.code}"))
                            activeDownloads.remove(mmprojId)
                            return@flow
                        }
                        continue
                    }
                    
                    val body = response.body
                    if (body == null) {
                        response.close()
                        Log.w(TAG, "Empty response body, retrying")
                        retryCount++
                        continue
                    }
                    
                    // CRITICAL FIX: Use body.contentLength() as primary source of truth
                    val bodyContentLength = body.contentLength()
                    var effectiveExpectedSize = expectedSize
                    
                    if (bodyContentLength > 0) {
                        val serverReportedTotalSize = if (response.code == 206) {
                            bodyContentLength + existingBytes
                        } else {
                            bodyContentLength
                        }
                        
                        if (expectedSize <= 0 || kotlin.math.abs(expectedSize - serverReportedTotalSize) > expectedSize * 0.1) {
                            Log.i(TAG, "Updating mmproj expected size from $expectedSize to $serverReportedTotalSize (from response body)")
                            effectiveExpectedSize = serverReportedTotalSize
                            
                            if (serverReportedTotalSize > 0) {
                                model.mmprojUrl?.let { fileSizeCache[it] = serverReportedTotalSize }
                            }
                        }
                    } else if (expectedSize <= 0) {
                        Log.w(TAG, "Server did not provide Content-Length for mmproj. Using mmprojFileSize=${model.mmprojFileSize} as fallback")
                        effectiveExpectedSize = if (model.mmprojFileSize > 0) model.mmprojFileSize else Long.MAX_VALUE
                    }
                    
                    val finalExpectedSize = effectiveExpectedSize
                    
                    val inputStream = body.byteStream()
                    val outputFile = RandomAccessFile(tempFile, "rw")
                    if (existingBytes > 0 && response.code == 206) {
                        outputFile.seek(existingBytes)
                    } else if (response.code == 200) {
                        outputFile.setLength(0)
                        outputFile.seek(0)
                        existingBytes = 0L
                    }
                    
                    val buffer = ByteArray(BUFFER_SIZE)
                    var downloadedBytes = existingBytes
                    var bytesRead: Int
                    var lastNotificationTime = 0L
                    
                    // Speed calculation variables for mmproj
                    var lastSpeedCalcTime = System.currentTimeMillis()
                    var lastSpeedCalcBytes = existingBytes
                    var currentSpeed = 0L
                    var etaSeconds = -1L
                    
                    try {
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            if (!control.isActive) {
                                outputFile.close()
                                inputStream.close()
                                response.close()
                                notificationManager.cancelNotification(mmprojId)
                                emit(DownloadState.Cancelled)
                                activeDownloads.remove(mmprojId)
                                return@flow
                            }
                            
                            while (control.isPaused && control.isActive) {
                                kotlinx.coroutines.delay(100)
                            }
                            
                            if (!control.isActive) {
                                outputFile.close()
                                inputStream.close()
                                response.close()
                                notificationManager.cancelNotification(mmprojId)
                                emit(DownloadState.Paused(downloadedBytes, finalExpectedSize))
                                activeDownloads.remove(mmprojId)
                                return@flow
                            }
                            
                            outputFile.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            
                            val progress = downloadedBytes.toFloat() / finalExpectedSize.toFloat()
                            
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastNotificationTime > PROGRESS_UPDATE_INTERVAL_MS) {
                                // Calculate speed
                                val timeDelta = currentTime - lastSpeedCalcTime
                                if (timeDelta > 0) {
                                    val bytesDelta = downloadedBytes - lastSpeedCalcBytes
                                    currentSpeed = (bytesDelta * 1000) / timeDelta
                                    
                                    val remainingBytes = finalExpectedSize - downloadedBytes
                                    etaSeconds = if (currentSpeed > 0) remainingBytes / currentSpeed else -1L
                                    
                                    lastSpeedCalcTime = currentTime
                                    lastSpeedCalcBytes = downloadedBytes
                                }
                                
                                notificationManager.showDownloadNotification(
                                    mmprojId,
                                    "${model.name} (Vision)",
                                    progress,
                                    downloadedBytes,
                                    finalExpectedSize,
                                    currentSpeed,
                                    etaSeconds
                                )
                                emit(DownloadState.Downloading(progress, downloadedBytes, finalExpectedSize, currentSpeed, etaSeconds))
                                lastNotificationTime = currentTime
                            }
                        }
                        
                        outputFile.close()
                        inputStream.close()
                        response.close()
                        
                        // Verify download completeness
                        val finalSize = tempFile.length()
                        val sizeTolerance = (finalExpectedSize * 0.01f).toLong() // 1% tolerance
                        
                        if (finalSize >= finalExpectedSize || kotlin.math.abs(finalSize - finalExpectedSize) <= sizeTolerance) {
                            if (tempFile.renameTo(destinationFile)) {
                                saveFileSizeMetadata(model.mmprojFileName, finalExpectedSize)
                                Log.i(TAG, "Mmproj download completed: ${model.mmprojFileName} ($finalSize bytes)")
                                notificationManager.showDownloadCompleteNotification(mmprojId, "${model.name} (Vision)")
                                emit(DownloadState.Success)
                                activeDownloads.remove(mmprojId)
                                return@flow
                            } else {
                                // Rename failed, try copy and delete
                                try {
                                    tempFile.copyTo(destinationFile, overwrite = true)
                                    tempFile.delete()
                                    saveFileSizeMetadata(model.mmprojFileName, finalExpectedSize)
                                    Log.i(TAG, "Mmproj download completed (via copy): ${model.mmprojFileName}")
                                    notificationManager.showDownloadCompleteNotification(mmprojId, "${model.name} (Vision)")
                                    emit(DownloadState.Success)
                                    activeDownloads.remove(mmprojId)
                                    return@flow
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to finalize mmproj via copy", e)
                                    emit(DownloadState.Error("Failed to finalize mmproj download"))
                                    activeDownloads.remove(mmprojId)
                                    return@flow
                                }
                            }
                        } else {
                            // Download incomplete, retry
                            Log.w(TAG, "Mmproj download incomplete: $finalSize < $finalExpectedSize, retrying...")
                            retryCount++
                            continue
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        Log.w(TAG, "Socket timeout during mmproj download, will retry", e)
                        lastError = e
                        try { outputFile.close() } catch (ce: Exception) { Log.w(TAG, "Failed to close output file during mmproj socket timeout cleanup", ce) }
                        try { inputStream.close() } catch (ce: Exception) { Log.w(TAG, "Failed to close input stream during mmproj socket timeout cleanup", ce) }
                        try { response.close() } catch (ce: Exception) { Log.w(TAG, "Failed to close response during mmproj socket timeout cleanup", ce) }
                        retryCount++
                        continue
                    } catch (e: java.io.IOException) {
                        Log.w(TAG, "IO error during mmproj download, will retry", e)
                        lastError = e
                        try { outputFile.close() } catch (ce: Exception) { Log.w(TAG, "Failed to close output file during mmproj IO error cleanup", ce) }
                        try { inputStream.close() } catch (ce: Exception) { Log.w(TAG, "Failed to close input stream during mmproj IO error cleanup", ce) }
                        try { response.close() } catch (ce: Exception) { Log.w(TAG, "Failed to close response during mmproj IO error cleanup", ce) }
                        retryCount++
                        continue
                    }
                    
                } catch (e: java.net.SocketTimeoutException) {
                    Log.w(TAG, "Connection timeout for mmproj, will retry", e)
                    lastError = e
                    retryCount++
                } catch (e: java.net.UnknownHostException) {
                    Log.w(TAG, "Network unavailable for mmproj, will retry", e)
                    lastError = e
                    retryCount++
                } catch (e: java.io.IOException) {
                    Log.w(TAG, "IO error connecting for mmproj, will retry", e)
                    lastError = e
                    retryCount++
                }
            }
            
            // All retries exhausted
            val errorMsg = lastError?.message ?: "Unknown error after $MAX_DOWNLOAD_RETRIES retries"
            Log.e(TAG, "Mmproj download failed after $MAX_DOWNLOAD_RETRIES retries for ${model.name}: $errorMsg")
            notificationManager.showDownloadErrorNotification(mmprojId, "${model.name} (Vision)", errorMsg)
            emit(DownloadState.Error("Mmproj download failed: $errorMsg"))
            
        } catch (e: Exception) {
            Log.e(TAG, "Mmproj download failed for ${model.name}", e)
            emit(DownloadState.Error("Mmproj download failed: ${e.message}"))
        } finally {
            activeDownloads.remove(mmprojId)
        }
    }.flowOn(Dispatchers.IO)
}
