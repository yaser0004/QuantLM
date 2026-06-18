package com.quantlm.yaser.data.worker

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.quantlm.yaser.data.diagnostics.AppEventLogger
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
import com.quantlm.yaser.data.worker.DownloadWorkerConstants.KEY_MODEL_VERSION
import com.quantlm.yaser.data.worker.DownloadWorkerConstants.KEY_REMAINING_MS
import com.quantlm.yaser.data.worker.DownloadWorkerConstants.KEY_TOTAL_BYTES
import com.quantlm.yaser.data.worker.DownloadWorkerConstants.TAG_MODEL_ID_PREFIX
import com.quantlm.yaser.data.worker.DownloadWorkerConstants.TAG_MODEL_NAME_PREFIX
import com.quantlm.yaser.domain.model.DownloadState
import com.quantlm.yaser.domain.model.DownloadableModel
import com.quantlm.yaser.domain.model.ExtraDataFile
import com.quantlm.yaser.domain.model.isVisionModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WorkManagerDownloadRepo"
private const val PREFS_NAME = "download_tracking"
private const val KEY_ACTIVE_DOWNLOADS = "active_downloads"

/**
 * Download status reported by WorkManager
 */
sealed class WorkManagerDownloadStatus {
    object Enqueued : WorkManagerDownloadStatus()
    object Idle : WorkManagerDownloadStatus()
    data class InProgress(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long,
        val remainingMs: Long
    ) : WorkManagerDownloadStatus()
    object Succeeded : WorkManagerDownloadStatus()
    data class Failed(val errorMessage: String) : WorkManagerDownloadStatus()
    object Cancelled : WorkManagerDownloadStatus()
}

/**
 * Repository for managing model downloads using WorkManager.
 * This is the SINGLE source of truth for download state.
 * 
 * Key features:
 * - Uses ExistingWorkPolicy.KEEP to prevent restarts
 * - Persists active download IDs to SharedPreferences
 * - Thread-safe observation of WorkManager state
 */
@Singleton
class WorkManagerDownloadRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val workManager = WorkManager.getInstance(context)
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    
    // Map of model ID to StateFlow for reactive download state updates
    private val downloadStates = mutableMapOf<String, MutableStateFlow<DownloadState>>()
    
    // Map of model ID to worker UUID for tracking
    private val activeWorkerIds = mutableMapOf<String, UUID>()

    // Forever-observers and the LiveData they watch, so cleanup() can actually
    // detach them (observeForever holds them until explicitly removed).
    private val activeObservers = mutableMapOf<String, Pair<LiveData<WorkInfo>, Observer<WorkInfo>>>()
    
    /**
     * Get download state as StateFlow for a model
     */
    fun getDownloadStateFlow(modelId: String): StateFlow<DownloadState>? {
        return downloadStates[modelId]?.asStateFlow()
    }
    
    /**
     * Get all active download states
     */
    fun getAllDownloadStates(): Map<String, StateFlow<DownloadState>> {
        return downloadStates.mapValues { it.value.asStateFlow() }
    }
    
    /**
     * Check if a download is currently active (queued or running)
     */
    fun isDownloadInProgress(modelId: String): Boolean {
        val stateFlow = downloadStates[modelId]
        if (stateFlow != null) {
            val currentState = stateFlow.value
            return currentState is DownloadState.Downloading
        }
        
        // Check WorkManager asynchronously - for sync check, just return from states map
        return false
    }
    
    /**
     * Start downloading a model using WorkManager.
     * Uses KEEP policy to prevent duplicate work and restarts.
     *
     * Suspend: the stale-work cleanup queries WorkManager with a blocking
     * future, which must run on IO — and it must complete BEFORE the enqueue,
     * otherwise KEEP silently ignores re-download requests.
     */
    suspend fun downloadModel(
        model: DownloadableModel,
        accessToken: String? = null,
        onStatusUpdated: ((WorkManagerDownloadStatus) -> Unit)? = null
    ): UUID {
        // Check if already downloading via state
        val existingState = downloadStates[model.id]
        if (existingState != null && existingState.value is DownloadState.Downloading) {
            Log.i(TAG, "Download already in progress for ${model.name}, skipping enqueue")
            AppEventLogger.warn(
                component = TAG,
                action = "download_enqueue_skipped_already_active",
                details = "modelId=${model.id}, modelName=${model.name}"
            )
            return activeWorkerIds[model.id] ?: UUID.randomUUID()
        }
        
        // Clean up any stale terminal-state work before enqueueing.
        // Without this, KEEP policy silently ignores re-download requests
        // after a previous download failed/succeeded/was cancelled.
        cleanupStaleWork(model.id)
        
        // Build input data
        val inputDataBuilder = Data.Builder()
            .putString(KEY_MODEL_ID, model.id)
            .putString(KEY_MODEL_NAME, model.name)
            .putString(KEY_MODEL_URL, model.downloadUrl)
            .putString(KEY_MODEL_FILE_NAME, model.fileName)
            .putLong(KEY_MODEL_TOTAL_BYTES, model.fileSize)
            .putString(KEY_MODEL_VERSION, model.version)
        
        // Add access token if provided
        accessToken?.let {
            inputDataBuilder.putString(KEY_MODEL_ACCESS_TOKEN, it)
        }
        
        // Collect all extra files to download
        val allExtras = mutableListOf<ExtraDataFile>()
        allExtras.addAll(model.extraDataFiles)
        
        // Add mmproj as extra file for vision models
        if (model.isVisionModel && model.mmprojUrl != null && model.mmprojFileName != null) {
            Log.i(TAG, "Adding mmproj for vision model ${model.name}: url=${model.mmprojUrl}, file=${model.mmprojFileName}, size=${model.mmprojFileSize}")
            allExtras.add(ExtraDataFile(
                name = "mmproj",
                url = model.mmprojUrl,
                downloadFileName = model.mmprojFileName,
                sizeInBytes = model.mmprojFileSize
            ))
        } else if (model.isVisionModel) {
            Log.w(TAG, "Vision model ${model.name} missing mmproj info: url=${model.mmprojUrl}, fileName=${model.mmprojFileName}")
        }
        
        if (allExtras.isNotEmpty()) {
            Log.i(TAG, "Total extra files to download: ${allExtras.size} - ${allExtras.map { it.downloadFileName }}")
            inputDataBuilder.putString(KEY_EXTRA_DATA_URLS, allExtras.joinToString(",") { it.url })
            inputDataBuilder.putString(KEY_EXTRA_DATA_FILE_NAMES, allExtras.joinToString(",") { it.downloadFileName })
            inputDataBuilder.putString(KEY_EXTRA_DATA_SIZES, allExtras.joinToString(",") { it.sizeInBytes.toString() })
        }
        
        val inputData = inputDataBuilder.build()
        
        // Create work request with expedited execution
        val downloadRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(inputData)
            .addTag("$TAG_MODEL_NAME_PREFIX${model.name}")
            .addTag("$TAG_MODEL_ID_PREFIX${model.id}")
            .build()
        
        val workerId = downloadRequest.id
        
        // CRITICAL: Use KEEP policy to prevent restarts if already running
        workManager.enqueueUniqueWork(
            model.id,
            ExistingWorkPolicy.KEEP,
            downloadRequest
        )
        
        Log.i(TAG, "Enqueued download for ${model.name} with worker ID: $workerId (KEEP policy)")
        AppEventLogger.info(
            component = TAG,
            action = "download_enqueued",
            details = "modelId=${model.id}, modelName=${model.name}, workerId=$workerId"
        )
        
        // Track active download
        activeWorkerIds[model.id] = workerId
        persistActiveDownloads()
        
        // Initialize download state
        val totalBytes = model.fileSize + model.mmprojFileSize + model.extraDataFiles.sumOf { it.sizeInBytes }
        val stateFlow = MutableStateFlow<DownloadState>(
            DownloadState.Downloading(0f, 0L, totalBytes)
        )
        downloadStates[model.id] = stateFlow
        
        // Observe progress on main thread
        observeWorkerProgress(workerId, model, totalBytes, onStatusUpdated)
        
        return workerId
    }
    
    /**
     * Cancel download for a specific model
     */
    fun cancelDownload(modelId: String) {
        workManager.cancelUniqueWork(modelId)
        downloadStates[modelId]?.value = DownloadState.Cancelled
        cleanup(modelId)
        Log.d(TAG, "Cancelled download for model ID: $modelId")
        AppEventLogger.info(component = TAG, action = "download_cancelled", details = "modelId=$modelId")
    }
    
    /**
     * Cancel download for a specific model
     */
    fun cancelDownload(model: DownloadableModel) {
        cancelDownload(model.id)
    }
    
    /**
     * Retry a stuck or failed download.
     * Cancels existing work, cleans up temp files, and re-enqueues with REPLACE.
     */
    suspend fun retryDownload(
        model: DownloadableModel,
        accessToken: String? = null,
        onStatusUpdated: ((WorkManagerDownloadStatus) -> Unit)? = null
    ): UUID {
        Log.i(TAG, "Retrying download for ${model.name} - cancelling existing work first")
        
        // Cancel any existing work
        workManager.cancelUniqueWork(model.id)
        
        // Clean up tracking state
        cleanup(model.id)
        
        // Give WorkManager a moment to process the cancellation
        // then enqueue fresh download
        return downloadModel(model, accessToken, onStatusUpdated)
    }
    
    /**
     * Clean up stale work entries in terminal states.
     * Prevents KEEP policy from silently ignoring re-download requests
     * after a previous download failed, succeeded, or was cancelled.
     */
    private suspend fun cleanupStaleWork(modelId: String) {
        try {
            // ListenableFuture.get() blocks; keep it off the caller's thread.
            val workInfos = withContext(Dispatchers.IO) {
                workManager.getWorkInfosForUniqueWork(modelId).get()
            }
            for (workInfo in workInfos) {
                if (workInfo.state.isFinished) {
                    Log.d(TAG, "Cleaning up stale work for $modelId (state: ${workInfo.state})")
                    workManager.cancelUniqueWork(modelId)
                    cleanup(modelId)
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking stale work for $modelId", e)
        }
    }
    
    /**
     * Run on app startup to clean up any stale download entries.
     * Checks all persisted active downloads and removes entries
     * for work that is no longer running.
     */
    suspend fun cleanupStaleDownloads() {
        val activeIds = prefs.getStringSet(KEY_ACTIVE_DOWNLOADS, emptySet()) ?: return
        for (modelId in activeIds.toSet()) {
            try {
                val workInfos = withContext(Dispatchers.IO) {
                    workManager.getWorkInfosForUniqueWork(modelId).get()
                }
                val isStillActive = workInfos.any { !it.state.isFinished }
                if (!isStillActive) {
                    Log.d(TAG, "Removing stale download entry: $modelId")
                    cleanup(modelId)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking download state for $modelId, removing", e)
                cleanup(modelId)
            }
        }
    }
    
    /**
     * Cancel all ongoing downloads
     */
    fun cancelAllDownloads(onComplete: (() -> Unit)? = null) {
        workManager.cancelAllWork()
            .result
            .addListener(
                { onComplete?.invoke() },
                executor
            )
        downloadStates.clear()
        activeWorkerIds.clear()
        activeObservers.values.forEach { (liveData, observer) ->
            mainHandler.post { liveData.removeObserver(observer) }
        }
        activeObservers.clear()
        prefs.edit().remove(KEY_ACTIVE_DOWNLOADS).apply()
        Log.d(TAG, "Cancelled all downloads")
    }
    
    /**
     * Check if a download is currently active for a model
     */
    fun isDownloadActive(modelId: String): Boolean {
        return downloadStates.containsKey(modelId)
    }
    
    /**
     * Convert WorkManager download status to DownloadState for UI compatibility
     */
    fun toDownloadState(status: WorkManagerDownloadStatus, totalBytes: Long): DownloadState {
        return when (status) {
            is WorkManagerDownloadStatus.Idle -> DownloadState.Idle
            is WorkManagerDownloadStatus.Enqueued -> DownloadState.Downloading(
                progress = 0f,
                downloadedBytes = 0,
                totalBytes = totalBytes
            )
            is WorkManagerDownloadStatus.InProgress -> DownloadState.Downloading(
                progress = if (status.totalBytes > 0) status.downloadedBytes.toFloat() / status.totalBytes else 0f,
                downloadedBytes = status.downloadedBytes,
                totalBytes = status.totalBytes,
                speedBytesPerSecond = status.bytesPerSecond,
                etaSeconds = status.remainingMs / 1000
            )
            is WorkManagerDownloadStatus.Succeeded -> DownloadState.Success
            is WorkManagerDownloadStatus.Failed -> DownloadState.Error(status.errorMessage)
            is WorkManagerDownloadStatus.Cancelled -> DownloadState.Cancelled
        }
    }
    
    /**
     * Cleanup resources for a model
     */
    private fun cleanup(modelId: String) {
        downloadStates.remove(modelId)
        activeWorkerIds.remove(modelId)
        activeObservers.remove(modelId)?.let { (liveData, observer) ->
            // Detach on the main thread — observeForever was registered there.
            mainHandler.post { liveData.removeObserver(observer) }
        }
        persistActiveDownloads()
    }
    
    /**
     * Persist list of active download IDs
     */
    private fun persistActiveDownloads() {
        val activeIds = activeWorkerIds.keys.toSet()
        prefs.edit().putStringSet(KEY_ACTIVE_DOWNLOADS, activeIds).apply()
    }
    
    /**
     * Observe worker progress - must run on main thread
     */
    private fun observeWorkerProgress(
        workerId: UUID,
        model: DownloadableModel,
        totalBytes: Long,
        onStatusUpdated: ((WorkManagerDownloadStatus) -> Unit)?
    ) {
        val liveData = workManager.getWorkInfoByIdLiveData(workerId)
        val stateFlow = downloadStates[model.id]
        
        val observer = Observer<WorkInfo> { workInfo ->
            if (workInfo == null) return@Observer
            
            val status = when (workInfo.state) {
                WorkInfo.State.ENQUEUED -> {
                    Log.d(TAG, "Download enqueued: ${model.name}")
                    AppEventLogger.debug(
                        component = TAG,
                        action = "work_state_enqueued",
                        details = "modelId=${model.id}, workerId=$workerId"
                    )
                    stateFlow?.value = DownloadState.Downloading(0f, 0L, totalBytes)
                    WorkManagerDownloadStatus.Enqueued
                }
                WorkInfo.State.RUNNING -> {
                    val downloadedBytes = workInfo.progress.getLong(KEY_DOWNLOADED_BYTES, 0L)
                    val reportedTotalBytes = workInfo.progress.getLong(KEY_TOTAL_BYTES, totalBytes)
                    val downloadRate = workInfo.progress.getLong(KEY_DOWNLOAD_RATE, 0L)
                    val remainingMs = workInfo.progress.getLong(KEY_REMAINING_MS, 0L)
                    
                    val effectiveTotalBytes = if (reportedTotalBytes > 0) reportedTotalBytes else totalBytes
                    val progress = if (effectiveTotalBytes > 0) downloadedBytes.toFloat() / effectiveTotalBytes else 0f
                    
                    Log.d(TAG, "Progress: ${model.name} - ${downloadedBytes}/${effectiveTotalBytes} bytes (${(progress * 100).toInt()}%), speed: $downloadRate B/s")

                    if (downloadedBytes > 0L && effectiveTotalBytes > 0L) {
                        val pct = (progress * 100).toInt()
                        if (pct % 10 == 0) {
                            AppEventLogger.debug(
                                component = TAG,
                                action = "work_progress_milestone",
                                details = "modelId=${model.id}, progress=${pct}%, bytes=$downloadedBytes/$effectiveTotalBytes"
                            )
                        }
                    }
                    
                    stateFlow?.value = DownloadState.Downloading(
                        progress = progress,
                        downloadedBytes = downloadedBytes,
                        totalBytes = effectiveTotalBytes,
                        speedBytesPerSecond = downloadRate,
                        etaSeconds = remainingMs / 1000
                    )
                    
                    WorkManagerDownloadStatus.InProgress(
                        downloadedBytes = downloadedBytes,
                        totalBytes = effectiveTotalBytes,
                        bytesPerSecond = downloadRate,
                        remainingMs = remainingMs
                    )
                }
                WorkInfo.State.SUCCEEDED -> {
                    Log.d(TAG, "Download succeeded: ${model.name}")
                    AppEventLogger.info(
                        component = TAG,
                        action = "work_succeeded",
                        details = "modelId=${model.id}, modelName=${model.name}"
                    )
                    stateFlow?.value = DownloadState.Success
                    cleanup(model.id)
                    WorkManagerDownloadStatus.Succeeded
                }
                WorkInfo.State.FAILED -> {
                    val errorMessage = workInfo.outputData.getString(KEY_ERROR_MESSAGE) ?: "Unknown error"
                    Log.e(TAG, "Download failed: ${model.name} - $errorMessage")
                    AppEventLogger.error(
                        component = TAG,
                        action = "work_failed",
                        details = "modelId=${model.id}, modelName=${model.name}, reason=$errorMessage"
                    )
                    stateFlow?.value = DownloadState.Error(errorMessage)
                    cleanup(model.id)
                    WorkManagerDownloadStatus.Failed(errorMessage)
                }
                WorkInfo.State.CANCELLED -> {
                    Log.d(TAG, "Download cancelled: ${model.name}")
                    AppEventLogger.info(
                        component = TAG,
                        action = "work_cancelled",
                        details = "modelId=${model.id}, modelName=${model.name}"
                    )
                    stateFlow?.value = DownloadState.Cancelled
                    cleanup(model.id)
                    WorkManagerDownloadStatus.Cancelled
                }
                else -> null
            }
            
            status?.let { onStatusUpdated?.invoke(it) }
        }
        
        // Store observer for cleanup
        activeObservers[model.id] = liveData to observer

        // Observe on main thread
        mainHandler.post {
            liveData.observeForever(observer)
        }
    }
}
