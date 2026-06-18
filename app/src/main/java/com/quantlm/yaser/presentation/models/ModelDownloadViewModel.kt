package com.quantlm.yaser.presentation.models

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.quantlm.yaser.data.diagnostics.AppEventLogger
import com.quantlm.yaser.data.repository.ModelDownloadRepository
import com.quantlm.yaser.data.worker.WorkManagerDownloadRepository
import com.quantlm.yaser.data.worker.WorkManagerDownloadStatus
import com.quantlm.yaser.domain.model.AvailableModels
import com.quantlm.yaser.domain.model.DownloadState
import com.quantlm.yaser.domain.model.DownloadableModel
import com.quantlm.yaser.domain.model.isVisionModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.LinkedList
import javax.inject.Inject

private const val TAG = "ModelDownloadViewModel"

/**
 * ViewModel for model downloads.
 * Uses WorkManager exclusively for reliable background downloads that survive app restarts.
 *
 * Fix [5.3]: [Application] is the process-wide context from [AndroidViewModel]; never hold Activity context.
 */
@HiltViewModel
class ModelDownloadViewModel @Inject constructor(
    private val modelRepository: ModelDownloadRepository,
    private val workManagerRepo: WorkManagerDownloadRepository,
    application: Application
) : AndroidViewModel(application) {
    
    private val modelsDir = File(application.filesDir, "models")
    
    private val _availableModels = MutableStateFlow<List<DownloadableModel>>(emptyList())
    val availableModels: StateFlow<List<DownloadableModel>> = _availableModels.asStateFlow()
    
    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadState>> = _downloadStates.asStateFlow()
    
    private val _downloadedModels = MutableStateFlow<Set<String>>(emptySet())
    val downloadedModels: StateFlow<Set<String>> = _downloadedModels.asStateFlow()
    
    private val _completionMessage = MutableStateFlow<String?>(null)
    val completionMessage: StateFlow<String?> = _completionMessage.asStateFlow()
    
    // Pre-fetched model file sizes: modelId -> Pair(mainSize, mmprojSize)
    private val _modelSizes = MutableStateFlow<Map<String, Pair<Long, Long>>>(emptyMap())
    val modelSizes: StateFlow<Map<String, Pair<Long, Long>>> = _modelSizes.asStateFlow()
    
    // Track which models are currently fetching sizes
    private val _fetchingSizes = MutableStateFlow<Set<String>>(emptySet())
    val fetchingSizes: StateFlow<Set<String>> = _fetchingSizes.asStateFlow()
    
    // Callbacks for download completion
    private val pendingCallbacks = mutableMapOf<String, () -> Unit>()

    /**
     * FIFO queue of models waiting to be downloaded.
     * Protected by the viewModelScope dispatcher — only accessed from coroutines.
     */
    private val downloadQueue = LinkedList<Pair<DownloadableModel, () -> Unit>>()
    private val _queuedModelIds = MutableStateFlow<Set<String>>(emptySet())
    val queuedModelIds: StateFlow<Set<String>> = _queuedModelIds.asStateFlow()
    
    init {
        loadAvailableModels()
        refreshDownloadedModels()
        restoreActiveDownloadStates()
        prefetchAllModelSizes()
        
        // Clean up stale download entries from previous app sessions.
        // Suspend: it queries WorkManager with blocking futures (now on IO).
        viewModelScope.launch {
            workManagerRepo.cleanupStaleDownloads()
        }
        
        Log.i(TAG, "ViewModel initialized with WorkManager-only architecture")
    }
    
    /**
     * Restore download states for any active downloads from WorkManager
     */
    private fun restoreActiveDownloadStates() {
        viewModelScope.launch {
            val activeStates = workManagerRepo.getAllDownloadStates()
            if (activeStates.isNotEmpty()) {
                Log.i(TAG, "Restoring ${activeStates.size} active download state(s)")
                
                // Initialize states from WorkManager
                val stateMap = mutableMapOf<String, DownloadState>()
                for ((modelId, stateFlow) in activeStates) {
                    stateMap[modelId] = stateFlow.value
                    
                    // Observe for updates
                    viewModelScope.launch {
                        stateFlow.collect { state ->
                            handleDownloadStateUpdate(modelId, state)
                        }
                    }
                }
                
                _downloadStates.value = _downloadStates.value + stateMap
            }
        }
    }
    
    /**
     * Handle download state updates from WorkManager
     */
    private fun handleDownloadStateUpdate(modelId: String, state: DownloadState) {
        _downloadStates.value = _downloadStates.value.toMutableMap().apply {
            put(modelId, state)
        }
        
        when (state) {
            is DownloadState.Success -> {
                val model = _availableModels.value.find { it.id == modelId }
                Log.i(TAG, "Download completed for ${model?.name ?: modelId}")

                // Show completion message
                _completionMessage.value = "✓ ${model?.name ?: "Model"} downloaded successfully!"
                viewModelScope.launch {
                    delay(5000)
                    if (_completionMessage.value?.contains(model?.name ?: "") == true) {
                        _completionMessage.value = null
                    }
                }

                // Invoke callback
                pendingCallbacks[modelId]?.invoke()
                pendingCallbacks.remove(modelId)

                // Refresh downloaded models list
                refreshDownloadedModels()

                // Clear state after success
                viewModelScope.launch {
                    delay(2000)
                    _downloadStates.value = _downloadStates.value.toMutableMap().apply {
                        remove(modelId)
                    }
                }

                // Drain the queue — start the next pending download
                drainDownloadQueue()
            }
            is DownloadState.Error -> {
                Log.e(TAG, "Download failed for $modelId: ${state.message}")
                pendingCallbacks.remove(modelId)
                drainDownloadQueue()
            }
            is DownloadState.Cancelled -> {
                Log.i(TAG, "Download cancelled for $modelId")
                pendingCallbacks.remove(modelId)
                drainDownloadQueue()
            }
            else -> {}
        }
    }
    
    /**
     * Pre-fetch file sizes for all models that don't have defined sizes.
     */
    private fun prefetchAllModelSizes() {
        viewModelScope.launch {
            _availableModels.value.forEach { model ->
                if (_modelSizes.value.containsKey(model.id)) return@forEach
                if (model.fileSize > 0 && (!model.isVisionModel || model.mmprojFileSize > 0)) {
                    _modelSizes.value = _modelSizes.value.toMutableMap().apply {
                        put(model.id, Pair(model.fileSize, model.mmprojFileSize))
                    }
                    return@forEach
                }
                fetchModelSize(model.id)
            }
        }
    }
    
    /**
     * Fetch file size for a specific model from server.
     */
    fun fetchModelSize(modelId: String) {
        val model = _availableModels.value.find { it.id == modelId } ?: return
        
        if (_fetchingSizes.value.contains(modelId) || _modelSizes.value.containsKey(modelId)) return
        
        viewModelScope.launch {
            _fetchingSizes.value = _fetchingSizes.value + modelId
            try {
                val (mainSize, mmprojSize) = modelRepository.prefetchModelSizes(model)
                _modelSizes.value = _modelSizes.value.toMutableMap().apply {
                    put(modelId, Pair(mainSize, mmprojSize))
                }
                Log.d(TAG, "Fetched size for ${model.name}: main=$mainSize, mmproj=$mmprojSize")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch size for ${model.name}", e)
            } finally {
                _fetchingSizes.value = _fetchingSizes.value - modelId
            }
        }
    }
    
    /**
     * Get the effective file size for a model (defined or fetched).
     */
    fun getEffectiveModelSize(model: DownloadableModel): Long {
        val sizes = _modelSizes.value[model.id]
        if (sizes != null) {
            return sizes.first + sizes.second
        }
        return model.fileSize + model.mmprojFileSize
    }
    
    private fun loadAvailableModels() {
        _availableModels.value = AvailableModels.getAllModels()
    }
    
    fun refreshDownloadedModels() {
        Log.d(TAG, "Refreshing downloaded models list")
        if (!modelsDir.exists()) {
            Log.d(TAG, "Models directory doesn't exist")
            _downloadedModels.value = emptySet()
            return
        }
        
        val downloaded = _availableModels.value
            .filter { model ->
                val modelFile = File(modelsDir, model.fileName)
                val mainModelExists = modelFile.exists() && modelFile.length() > 0
                
                if (!mainModelExists) {
                    Log.d(TAG, "Model file not found: ${model.fileName}")
                    return@filter false
                }
                
                // Check if we have stored size metadata (from successful download)
                val storedSize = modelRepository.getStoredFileSize(model.fileName)
                
                // If we have stored size metadata, the download was completed successfully
                // Use a more lenient tolerance (10%) or just trust if metadata exists
                val mainSizeValid = if (storedSize > 0) {
                    val actualSize = modelFile.length()
                    // If we have metadata, download was tracked - trust it more
                    val sizeDiff = kotlin.math.abs(actualSize - storedSize).toFloat() / storedSize.toFloat()
                    val isValid = sizeDiff < 0.10f // 10% tolerance
                    if (!isValid) {
                        Log.w(TAG, "Size mismatch for ${model.fileName}: actual=$actualSize, stored=$storedSize")
                    }
                    isValid
                } else if (model.fileSize > 0) {
                    val actualSize = modelFile.length()
                    val sizeDiff = kotlin.math.abs(actualSize - model.fileSize).toFloat() / model.fileSize.toFloat()
                    sizeDiff < 0.10f // 10% tolerance for model-defined sizes
                } else {
                    // No expected size known - just check file is reasonably large
                    modelFile.length() > 1_000_000L
                }
                
                if (!mainSizeValid) {
                    Log.d(TAG, "Main model size validation failed for ${model.fileName}")
                    return@filter false
                }
                
                // For vision models, check mmproj file (but be lenient)
                if (model.isVisionModel && model.mmprojFileName != null) {
                    val mmprojFile = File(modelsDir, model.mmprojFileName)
                    if (!mmprojFile.exists() || mmprojFile.length() == 0L) {
                        Log.d(TAG, "Vision model mmproj not found: ${model.mmprojFileName}")
                        // For vision models, still show as downloaded if main model exists
                        // They can use it for text-only even without mmproj
                        return@filter false
                    }
                    
                    // Be lenient with mmproj validation - just check it exists and has content
                    val mmprojStoredSize = modelRepository.getStoredFileSize(model.mmprojFileName)
                    val mmprojMinSize = 100_000L // Mmproj should be at least 100KB
                    
                    if (mmprojFile.length() < mmprojMinSize) {
                        Log.w(TAG, "Mmproj file too small: ${mmprojFile.length()} bytes")
                        return@filter false
                    }
                    
                    Log.d(TAG, "Vision model complete: ${model.name} (mmproj: ${mmprojFile.length()} bytes)")
                }
                
                Log.d(TAG, "Model validated as downloaded: ${model.name}")
                true
            }
            .map { it.id }
            .toSet()
        
        Log.d(TAG, "Downloaded models: ${downloaded.size}")
        _downloadedModels.value = downloaded
        
        // Clear states for downloaded models
        _downloadStates.value = _downloadStates.value.toMutableMap().apply {
            downloaded.forEach { modelId -> remove(modelId) }
        }
    }
    
    /**
     * Enqueue a model for download.
     *
     * If a download is already in progress, the model goes to [DownloadState.Queued]
     * and will start automatically once the current download finishes.
     * If nothing is downloading, the download starts immediately.
     */
    fun enqueueDownload(model: DownloadableModel, onSuccess: () -> Unit = {}) {
        AppEventLogger.info(
            component = TAG,
            action = "enqueue_download",
            details = "id=${model.id}, name=${model.name}, sizeMb=${model.fileSize / (1024 * 1024)}"
        )
        val isActivelyDownloading = _downloadStates.value.values
            .any { it is DownloadState.Downloading }

        if (isActivelyDownloading) {
            Log.i(TAG, "Queuing download for ${model.name}")
            AppEventLogger.info(component = TAG, action = "download_queued", details = "id=${model.id}")
            downloadQueue.add(Pair(model, onSuccess))
            _queuedModelIds.value = _queuedModelIds.value + model.id
            _downloadStates.value = _downloadStates.value.toMutableMap().apply {
                put(model.id, DownloadState.Queued)
            }
        } else {
            downloadModel(model, onSuccess)
        }
    }

    /**
     * Pop the next queued download and start it. Clears the [DownloadState.Queued] state.
     */
    private fun drainDownloadQueue() {
        val next = downloadQueue.poll() ?: return
        val (model, callback) = next
        _queuedModelIds.value = _queuedModelIds.value - model.id
        Log.i(TAG, "Draining queue — starting download for ${model.name}")
        downloadModel(model, callback)
    }

    /**
     * Start downloading a model using WorkManager.
     * The download will persist across app restarts.
     */
    fun downloadModel(model: DownloadableModel, onSuccess: () -> Unit = {}) {
        // Check if already downloading
        if (workManagerRepo.isDownloadInProgress(model.id)) {
            Log.i(TAG, "Download already in progress for ${model.name}, skipping")
            AppEventLogger.warn(component = TAG, action = "download_skipped_already_running", details = "id=${model.id}")
            return
        }

        val currentState = _downloadStates.value[model.id]
        if (currentState is DownloadState.Downloading) {
            Log.i(TAG, "Download already in progress (from state) for ${model.name}, skipping")
            AppEventLogger.warn(component = TAG, action = "download_skipped_state_says_running", details = "id=${model.id}")
            return
        }

        Log.i(TAG, "Starting WorkManager download for ${model.name}")
        AppEventLogger.info(component = TAG, action = "download_start", details = "id=${model.id}, name=${model.name}")
        
        // Store callback
        pendingCallbacks[model.id] = onSuccess
        
        // Calculate total bytes
        val totalBytes = model.fileSize + model.mmprojFileSize + model.extraDataFiles.sumOf { it.sizeInBytes }
        
        // Set initial downloading state
        _downloadStates.value = _downloadStates.value.toMutableMap().apply {
            put(model.id, DownloadState.Downloading(0f, 0L, totalBytes))
        }
        
        // Start download via WorkManager
        // The onStatusUpdated callback is the SINGLE source of truth for progress.
        // Do NOT also collect the StateFlow — the callback is already driven by
        // the LiveData observer in observeWorkerProgress(), and having two paths
        // causes conflicting updates where stale StateFlow values overwrite
        // fresh callback values, making the UI appear frozen.
        viewModelScope.launch {
            workManagerRepo.downloadModel(
                model = model,
                accessToken = model.accessToken
            ) { status ->
                val downloadState = workManagerRepo.toDownloadState(status, totalBytes)
                handleDownloadStateUpdate(model.id, downloadState)
            }
        }
    }
    
    /**
     * Download just the mmproj file for a vision model.
     */
    fun downloadMmprojOnly(model: DownloadableModel, onSuccess: () -> Unit = {}) {
        if (!model.isVisionModel || model.mmprojUrl == null || model.mmprojFileName == null) {
            Log.w(TAG, "Model is not a vision model or missing mmproj info")
            return
        }
        
        Log.i(TAG, "Starting mmproj-only download for ${model.name}")
        
        val mmprojId = "${model.id}_mmproj"
        pendingCallbacks[mmprojId] = onSuccess
        
        // Create a temporary model for just the mmproj
        val mmprojModel = DownloadableModel(
            id = mmprojId,
            name = "${model.name} (Vision Support)",
            description = "Vision projection file for ${model.name}",
            downloadUrl = model.mmprojUrl,
            fileName = model.mmprojFileName,
            quantization = model.quantization,
            creator = model.creator,
            fileSize = model.mmprojFileSize,
            accessToken = model.accessToken
        )
        
        _downloadStates.value = _downloadStates.value.toMutableMap().apply {
            put(mmprojId, DownloadState.Downloading(0f, 0L, model.mmprojFileSize))
        }
        
        viewModelScope.launch {
            workManagerRepo.downloadModel(
                model = mmprojModel,
                accessToken = model.accessToken
            ) { status ->
                val downloadState = workManagerRepo.toDownloadState(status, model.mmprojFileSize)

                _downloadStates.value = _downloadStates.value.toMutableMap().apply {
                    put(mmprojId, downloadState)
                }

                when (status) {
                    is WorkManagerDownloadStatus.Succeeded -> {
                        Log.i(TAG, "Mmproj download complete for ${model.name}")
                        _completionMessage.value = "✓ Vision support downloaded for ${model.name}!"
                        viewModelScope.launch {
                            delay(5000)
                            _completionMessage.value = null
                        }
                        refreshDownloadedModels()
                        pendingCallbacks[mmprojId]?.invoke()
                        pendingCallbacks.remove(mmprojId)
                    }
                    is WorkManagerDownloadStatus.Failed -> {
                        Log.e(TAG, "Mmproj download failed: ${status.errorMessage}")
                        pendingCallbacks.remove(mmprojId)
                    }
                    else -> {}
                }
            }
        }
    }
    
    /**
     * Cancel a download
     */
    fun cancelDownload(model: DownloadableModel) {
        Log.i(TAG, "Cancelling download for ${model.name}")
        AppEventLogger.info(component = TAG, action = "download_cancel", details = "id=${model.id}, name=${model.name}")

        workManagerRepo.cancelDownload(model.id)
        pendingCallbacks.remove(model.id)

        _downloadStates.value = _downloadStates.value.toMutableMap().apply {
            put(model.id, DownloadState.Cancelled)
        }
    }

    /**
     * Retry a stuck or failed download.
     * Cancels existing work, cleans up, and restarts.
     */
    fun retryDownload(model: DownloadableModel, onSuccess: () -> Unit = {}) {
        Log.i(TAG, "Retrying download for ${model.name}")
        AppEventLogger.info(component = TAG, action = "download_retry", details = "id=${model.id}, name=${model.name}")
        
        // Clear error/cancelled state
        _downloadStates.value = _downloadStates.value.toMutableMap().apply {
            remove(model.id)
        }
        
        pendingCallbacks[model.id] = onSuccess
        
        val totalBytes = model.fileSize + model.mmprojFileSize + model.extraDataFiles.sumOf { it.sizeInBytes }
        
        _downloadStates.value = _downloadStates.value.toMutableMap().apply {
            put(model.id, DownloadState.Downloading(0f, 0L, totalBytes))
        }
        
        // Single source of truth: callback from observeWorkerProgress
        viewModelScope.launch {
            workManagerRepo.retryDownload(
                model = model,
                accessToken = model.accessToken
            ) { status ->
                val downloadState = workManagerRepo.toDownloadState(status, totalBytes)
                handleDownloadStateUpdate(model.id, downloadState)
            }
        }
    }
    
    /**
     * Delete a downloaded model
     */
    fun deleteModel(model: DownloadableModel) {
        Log.i(TAG, "Deleting model: ${model.name}")
        AppEventLogger.info(component = TAG, action = "delete_downloaded_model", details = "id=${model.id}, name=${model.name}")
        if (modelRepository.deleteDownloadedModel(model)) {
            _downloadedModels.value = _downloadedModels.value - model.id
            _downloadStates.value = _downloadStates.value.toMutableMap().apply {
                remove(model.id)
            }
            Log.i(TAG, "Model deleted successfully")
            AppEventLogger.info(component = TAG, action = "delete_downloaded_model_success", details = "id=${model.id}")
        } else {
            Log.w(TAG, "Failed to delete model")
            AppEventLogger.warn(component = TAG, action = "delete_downloaded_model_failed", details = "id=${model.id}")
        }
    }
    
    /**
     * Clear download state for a model
     */
    fun clearDownloadState(model: DownloadableModel) {
        _downloadStates.value = _downloadStates.value.toMutableMap().apply {
            remove(model.id)
        }
    }
    
    /**
     * Dismiss completion message
     */
    fun dismissCompletionMessage() {
        _completionMessage.value = null
    }
    
    /**
     * Get file path for a downloaded model
     */
    fun getModelFilePath(model: DownloadableModel): String? {
        return if (modelRepository.isModelDownloaded(model)) {
            modelRepository.getModelFilePath(model)
        } else {
            null
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        pendingCallbacks.clear()
        Log.i(TAG, "ViewModel cleared")
    }
}
