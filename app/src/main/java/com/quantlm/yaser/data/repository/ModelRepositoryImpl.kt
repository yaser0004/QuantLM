package com.quantlm.yaser.data.repository

import android.os.Build
import android.util.Log
import com.quantlm.yaser.data.diagnostics.AppEventLogger
import com.quantlm.yaser.data.inference.LlamaEngine
import com.quantlm.yaser.data.inference.MediaPipeEngine
import com.quantlm.yaser.data.inference.ModelManager
import com.quantlm.yaser.data.local.GenerationPreferences
import com.quantlm.yaser.data.local.ModelPreferences
import com.quantlm.yaser.domain.inference.InferenceConfig
import com.quantlm.yaser.domain.model.ModelConfig
import com.quantlm.yaser.domain.model.ModelFormat
import com.quantlm.yaser.domain.model.ModelInfo
import com.quantlm.yaser.domain.model.ModelLoadingState
import com.quantlm.yaser.domain.model.ModelProfileRegistry
import com.quantlm.yaser.domain.repository.ModelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Model repository implementation that supports multiple inference engines.
 * 
 * Engine routing:
 * - GGUF models (.gguf) -> LlamaEngine (llama.cpp)
 * - LiteRT/Task models (.task, .litertlm) -> MediaPipeEngine
 * - TFLite models (.tflite, .literlm) -> MediaPipeEngine
 */
@Singleton
class ModelRepositoryImpl @Inject constructor(
    private val llamaEngine: LlamaEngine,
    private val mediaPipeEngine: MediaPipeEngine,
    private val modelManager: ModelManager,
    private val modelPreferences: ModelPreferences,
    private val generationPreferences: GenerationPreferences
) : ModelRepository {
    
    companion object {
        private const val TAG = "ModelRepository"
        private const val MIN_MODEL_BYTES = 1_000_000L
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Fix [2.5]: serialize load/unload so native engines never overlap in-flight init/cleanup
    private val modelLoadMutex = Mutex()

    private val _loadedModel = MutableStateFlow<ModelInfo?>(null)
    private val _loadingState = MutableStateFlow<ModelLoadingState>(ModelLoadingState.Idle)
    override val loadingState: StateFlow<ModelLoadingState> = _loadingState.asStateFlow()
    
    // Track which engine was used to load the current model
    private var currentEngineType: EngineType = EngineType.NONE
    
    private enum class EngineType {
        NONE, LLAMA, MEDIAPIPE
    }
    
    init {
        // Restore previously loaded model on startup
        scope.launch {
            try {
                val savedModel = modelPreferences.getLoadedModel().firstOrNull()
                if (savedModel != null) {
                    AppEventLogger.info(
                        component = TAG,
                        action = "restore_saved_model_detected",
                        details = "name=${savedModel.name}, path=${savedModel.filePath}, vision=${savedModel.isVisionModel}"
                    )
                    // Verify the model file still exists
                    val file = File(savedModel.filePath)
                    val mmprojFile = savedModel.mmprojPath?.let { File(it) }
                    
                    // For vision models with mmproj, also check mmproj file exists
                    val filesExist = file.exists() && 
                        (!savedModel.isVisionModel || savedModel.mmprojPath == null || mmprojFile?.exists() == true)
                    
                    // Validate file size - corrupted/truncated files (< 1MB) would crash during load
                    val fileSizeValid = file.exists() && file.length() > 1_000_000L
                    
                    if (filesExist && fileSizeValid) {
                        Log.d(TAG, "Restoring model: ${savedModel.name}, isVision: ${savedModel.isVisionModel}")
                        AppEventLogger.info(
                            component = TAG,
                            action = "restore_start",
                            details = "name=${savedModel.name}, bytes=${file.length()}"
                        )
                        // Load the model with saved config including vision support
                        val config = ModelConfig(
                            name = savedModel.name,
                            filePath = savedModel.filePath,
                            size = savedModel.size,
                            isVisionModel = savedModel.isVisionModel,
                            mmprojPath = savedModel.mmprojPath
                        )
                        val restoreResult = loadModel(config)
                        if (restoreResult.isFailure) {
                            AppEventLogger.error(
                                component = TAG,
                                action = "restore_failed",
                                details = "name=${savedModel.name}, reason=${restoreResult.exceptionOrNull()?.message ?: "unknown"}",
                                throwable = restoreResult.exceptionOrNull()
                            )
                            Log.e(
                                TAG,
                                "Failed to restore model ${savedModel.name}, clearing preferences: ${restoreResult.exceptionOrNull()?.message}",
                                restoreResult.exceptionOrNull()
                            )
                            modelPreferences.clearLoadedModel()
                        } else {
                            AppEventLogger.info(
                                component = TAG,
                                action = "restore_success",
                                details = "name=${savedModel.name}"
                            )
                        }
                    } else {
                        if (!filesExist) {
                            Log.w(TAG, "Saved model file not found: ${savedModel.filePath}")
                        } else {
                            Log.w(TAG, "Saved model file too small (likely corrupted): ${file.length()} bytes")
                        }
                        AppEventLogger.warn(
                            component = TAG,
                            action = "restore_skipped_invalid_saved_model",
                            details = "name=${savedModel.name}, filesExist=$filesExist, validSize=$fileSizeValid"
                        )
                        modelPreferences.clearLoadedModel()
                    }
                }
            } catch (e: Exception) {
                AppEventLogger.error(
                    component = TAG,
                    action = "restore_exception",
                    details = "reason=${e.message ?: "unknown"}",
                    throwable = e
                )
                Log.e(TAG, "Error restoring model", e)
                try { modelPreferences.clearLoadedModel() } catch (ce: Exception) { Log.w(TAG, "Failed to clear model preferences during error recovery", ce) }
            }
        }
    }

    private fun findZipMagicOffset(file: File): Int? {
        return try {
            file.inputStream().use { input ->
                val buf = ByteArray(16)
                val read = input.read(buf)
                if (read < 2) return@use null
                for (i in 0 until (read - 1)) {
                    if (buf[i] == 'P'.code.toByte() && buf[i + 1] == 'K'.code.toByte()) {
                        return@use i
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun hasZipMagic(file: File): Boolean = findZipMagicOffset(file) != null

    private fun resolveGpuLayers(
        requestedGpuLayers: Int,
        hardware: GenerationPreferences.HardwareSettings
    ): Int {
        val persistedLayers = hardware.gpuLayers.coerceIn(0, 100)
        val callerLayers = requestedGpuLayers.coerceIn(0, 100)

        return when (hardware.accelerationMode) {
            GenerationPreferences.HardwareAccelerationMode.CPU -> 0
            GenerationPreferences.HardwareAccelerationMode.GPU -> {
                when {
                    callerLayers > 0 -> callerLayers
                    persistedLayers > 0 -> persistedLayers
                    else -> 1
                }
            }
        }
    }

    private fun readMagicHex(file: File, length: Int = 8): String {
        return try {
            file.inputStream().use { input ->
                val buf = ByteArray(length)
                val read = input.read(buf)
                if (read <= 0) return@use "empty"
                buf.copyOf(read).joinToString(separator = "") { b -> "%02X".format(b) }
            }
        } catch (_: Exception) {
            "unreadable"
        }
    }

    private fun isValidTaskBundle(file: File): Boolean {
        if (!file.name.lowercase().endsWith(".task")) {
            return true
        }

        val zipOffset = findZipMagicOffset(file)

        // Some MediaPipe/LiteRT providers ship .task as non-ZIP binary blobs.
        // If no ZIP magic is present, defer strict validation to MediaPipe engine load.
        if (zipOffset == null) {
            Log.i(
                TAG,
                "Task file ${file.name} is non-ZIP (magic=${readMagicHex(file)}); deferring validation to MediaPipe runtime"
            )
            return true
        }

        // Some bundles include a small prefix before ZIP content.
        // Defer strict ZIP parsing in this case to avoid false negatives.
        if (zipOffset > 0) {
            Log.i(
                TAG,
                "Task file ${file.name} has ZIP magic at offset=$zipOffset (magic=${readMagicHex(file)}); deferring strict ZIP parse to MediaPipe runtime"
            )
            return true
        }

        return try {
            ZipFile(file).use { zip ->
                val hasEntries = zip.entries().hasMoreElements()
                if (!hasEntries) {
                    Log.w(TAG, "Task ZIP bundle has no entries: ${file.name}")
                }
                hasEntries
            }
        } catch (e: Exception) {
            Log.w(TAG, "Task ZIP bundle validation failed for ${file.name}: ${e.message}")
            false
        }
    }
    
    /**
     * Determine which engine should be used for a given model file.
     * 
     * Engine routing:
     * - .gguf → LlamaEngine (llama.cpp)
     * - .task, .litertlm → MediaPipeEngine (MediaPipe tasks-genai)
     * - .tflite, .literlm → MediaPipeEngine (will attempt to load)
     */
    private fun getEngineType(filePath: String): EngineType {
        val lowerPath = filePath.lowercase()
        return when {
            lowerPath.endsWith(".gguf") -> EngineType.LLAMA
            // LiteRT models use MediaPipe for inference
            lowerPath.endsWith(".task") -> EngineType.MEDIAPIPE
            lowerPath.endsWith(".litertlm") -> EngineType.MEDIAPIPE
            lowerPath.endsWith(".tflite") -> EngineType.MEDIAPIPE
            lowerPath.endsWith(".literlm") -> EngineType.MEDIAPIPE
            else -> EngineType.LLAMA // Default to llama for unknown formats
        }
    }
    
    /**
     * Get the model format from file extension
     */
    private fun getModelFormat(filePath: String): ModelFormat {
        return ModelFormat.fromFileName(filePath)
    }

    /**
     * MediaPipe LLM native runtime currently ships arm64-v8a only.
     */
    private fun isMediaPipeAbiSupported(): Boolean {
        return Build.SUPPORTED_ABIS.any { it.equals("arm64-v8a", ignoreCase = true) }
    }

    /**
     * Detect common cases where a failed download saved an HTML/JSON error page
     * instead of a model binary.
     */
    private fun isLikelyHttpErrorPayload(file: File): Boolean {
        return try {
            val preview = file.inputStream().use { input ->
                val buf = ByteArray(512)
                val read = input.read(buf)
                if (read <= 0) return@use ""
                String(buf, 0, read, Charsets.UTF_8).trimStart().lowercase()
            }

            preview.startsWith("<!doctype html") ||
                preview.startsWith("<html") ||
                preview.startsWith("{\"error\"") ||
                preview.contains("huggingface") && preview.contains("access")
        } catch (_: Exception) {
            false
        }
    }
    
    override suspend fun loadModel(config: ModelConfig): Result<Unit> = modelLoadMutex.withLock {
        val persistedHardware = generationPreferences
            .getHardwareSettings()
            .firstOrNull()
        val effectiveConfig = if (persistedHardware != null) {
            config.copy(nGpuLayers = resolveGpuLayers(config.nGpuLayers, persistedHardware))
        } else {
            config
        }

        val currentModel = _loadedModel.value
        
        // Set loading state - show switching if there's a current model
        if (currentModel != null) {
            _loadingState.value = ModelLoadingState.Switching(currentModel.name, effectiveConfig.name)
        } else {
            _loadingState.value = ModelLoadingState.Loading(effectiveConfig.name)
        }
        
        // Unload any currently loaded model first
        if (currentModel != null) {
            unloadCurrentEngine()
        }
        
        // Determine which engine to use based on file format
        val engineType = getEngineType(effectiveConfig.filePath)
        val modelFormat = getModelFormat(effectiveConfig.filePath)
        
        Log.d(TAG, "Loading model: ${effectiveConfig.name}, format: $modelFormat, engine: $engineType")
        AppEventLogger.info(
            component = TAG,
            action = "load_requested",
            details = "name=${effectiveConfig.name}, format=$modelFormat, engine=$engineType, vision=${effectiveConfig.isVisionModel}, context=${effectiveConfig.contextLength}, gpuLayers=${effectiveConfig.nGpuLayers}"
        )
        
        val result = when (engineType) {
            EngineType.LLAMA -> loadWithLlamaEngine(effectiveConfig)
            EngineType.MEDIAPIPE -> loadWithMediaPipeEngine(effectiveConfig)
            EngineType.NONE -> Result.failure(Exception("Unsupported model format"))
        }
        
        if (result.isSuccess) {
            currentEngineType = engineType
            
            val modelInfo = ModelInfo(
                name = effectiveConfig.name,
                filePath = effectiveConfig.filePath,
                size = effectiveConfig.size,
                isLoaded = true,
                metadata = getModelMetadata(engineType),
                isVisionModel = effectiveConfig.isVisionModel || isVisionSupported(engineType),
                mmprojPath = effectiveConfig.mmprojPath
            )
            _loadedModel.value = modelInfo
            
            // Persist the loaded model info
            try {
                modelPreferences.saveLoadedModel(
                    name = effectiveConfig.name,
                    filePath = effectiveConfig.filePath,
                    size = effectiveConfig.size,
                    isVisionModel = modelInfo.isVisionModel,
                    mmprojPath = effectiveConfig.mmprojPath
                )
                Log.d(TAG, "Saved model to preferences: ${effectiveConfig.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving model to preferences", e)
            }

            try {
                applyModelProfileOverrides(effectiveConfig.filePath)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply model profile overrides", e)
                AppEventLogger.warn(
                    component = TAG,
                    action = "model_profile_override_failed",
                    details = "path=${effectiveConfig.filePath}, reason=${e.message ?: "unknown"}"
                )
            }
            
            // Set loaded state briefly, then return to idle
            _loadingState.value = ModelLoadingState.Loaded(effectiveConfig.name)
            _loadingState.value = ModelLoadingState.Idle
            AppEventLogger.info(
                component = TAG,
                action = "load_success",
                details = "name=${effectiveConfig.name}, engine=$engineType, gpuLayers=${effectiveConfig.nGpuLayers}"
            )
        } else {
            currentEngineType = EngineType.NONE
            AppEventLogger.error(
                component = TAG,
                action = "load_failed",
                details = "name=${effectiveConfig.name}, engine=$engineType, reason=${result.exceptionOrNull()?.message ?: "unknown"}",
                throwable = result.exceptionOrNull()
            )
            _loadingState.value = ModelLoadingState.Error(result.exceptionOrNull()?.message ?: "Unknown error")
            // Return to idle after error
            _loadingState.value = ModelLoadingState.Idle
        }
        
        result
    }

    private suspend fun applyModelProfileOverrides(filePath: String) {
        val loadedFileName = File(filePath).name
        val modelId = modelManager.resolveModelIdForLoadedFileName(loadedFileName)
        val modelKey = modelId ?: File(filePath).nameWithoutExtension
        val profile = ModelProfileRegistry.getProfileForModel(modelKey)
        generationPreferences.applyModelProfile(profile)

        AppEventLogger.info(
            component = TAG,
            action = "model_profile_applied",
            details = "loadedFileName=$loadedFileName, modelKey=$modelKey, family=${profile.family}"
        )
    }
    
    /**
     * Load model using LlamaEngine (for GGUF models)
     */
    private suspend fun loadWithLlamaEngine(config: ModelConfig): Result<Unit> {
        AppEventLogger.info(
            component = TAG,
            action = "load_with_llama",
            details = "name=${config.name}, vision=${config.isVisionModel}"
        )
        return if (config.isVisionModel && config.mmprojPath != null) {
            Log.d(TAG, "Loading GGUF vision model with mmproj: ${config.name}")
            llamaEngine.loadVisionModel(
                modelPath = config.filePath,
                mmprojPath = config.mmprojPath,
                nThreads = config.nThreads,
                nGpuLayers = config.nGpuLayers,
                contextSize = config.contextLength
            )
        } else {
            Log.d(TAG, "Loading GGUF model: ${config.name}")
            llamaEngine.loadModel(
                modelPath = config.filePath,
                nThreads = config.nThreads,
                nGpuLayers = config.nGpuLayers,
                contextSize = config.contextLength
            )
        }
    }
    
    /**
     * Load model using MediaPipeEngine (for .task, .litertlm, .tflite models)
     */
    private suspend fun loadWithMediaPipeEngine(config: ModelConfig): Result<Unit> {
        val modelFile = File(config.filePath)
        if (!modelFile.exists()) {
            AppEventLogger.error(
                component = TAG,
                action = "mediapipe_preflight_failed",
                details = "missing_file path=${config.filePath}"
            )
            return Result.failure(IllegalArgumentException("Model file not found: ${config.filePath}"))
        }

        if (modelFile.length() < MIN_MODEL_BYTES) {
            AppEventLogger.error(
                component = TAG,
                action = "mediapipe_preflight_failed",
                details = "file_too_small name=${modelFile.name}, bytes=${modelFile.length()}"
            )
            return Result.failure(
                IllegalStateException(
                    "Model file is too small (${modelFile.length()} bytes). Please delete and re-download the model."
                )
            )
        }

        if (!isValidTaskBundle(modelFile)) {
            AppEventLogger.error(
                component = TAG,
                action = "mediapipe_preflight_failed",
                details = "invalid_task_bundle name=${modelFile.name}, magic=${readMagicHex(modelFile)}"
            )
            return Result.failure(
                IllegalStateException(
                    "Model file is not a valid .task bundle. Please delete and re-download the model."
                )
            )
        }

        if (modelFile.name.lowercase().endsWith(".task")) {
            val zipOffset = findZipMagicOffset(modelFile)
            when {
                zipOffset == null -> {
                    AppEventLogger.warn(
                        component = TAG,
                        action = "mediapipe_preflight_task_non_zip",
                        details = "name=${modelFile.name}, magic=${readMagicHex(modelFile)}"
                    )
                }
                zipOffset > 0 -> {
                    AppEventLogger.warn(
                        component = TAG,
                        action = "mediapipe_preflight_task_zip_prefixed",
                        details = "name=${modelFile.name}, zipOffset=$zipOffset, magic=${readMagicHex(modelFile)}"
                    )
                }
            }
        }

        if (!isMediaPipeAbiSupported()) {
            AppEventLogger.error(
                component = TAG,
                action = "mediapipe_preflight_failed",
                details = "unsupported_abi abis=${Build.SUPPORTED_ABIS.joinToString()}"
            )
            return Result.failure(
                IllegalStateException(
                    "MediaPipe task models require arm64-v8a. Device ABIs: ${Build.SUPPORTED_ABIS.joinToString()}"
                )
            )
        }

        if (isLikelyHttpErrorPayload(modelFile)) {
            AppEventLogger.error(
                component = TAG,
                action = "mediapipe_preflight_failed",
                details = "error_payload_detected name=${modelFile.name}"
            )
            return Result.failure(
                IllegalStateException(
                    "Model file appears invalid (downloaded error page). Please delete and re-download the model."
                )
            )
        }

        val inferenceConfig = InferenceConfig(
            nThreads = config.nThreads,
            nGpuLayers = config.nGpuLayers,
            contextSize = config.contextLength
        )

        Log.i(
            TAG,
            "MediaPipe preflight passed: file=${modelFile.name}, size=${modelFile.length()} bytes, abis=${Build.SUPPORTED_ABIS.joinToString()}"
        )
        AppEventLogger.info(
            component = TAG,
            action = "mediapipe_preflight_passed",
            details = "name=${modelFile.name}, bytes=${modelFile.length()}"
        )
        
        return if (config.isVisionModel && config.mmprojPath != null) {
            Log.d(TAG, "Loading MediaPipe vision model: ${config.name}")
            mediaPipeEngine.loadVisionModel(
                modelPath = config.filePath,
                mmprojPath = config.mmprojPath,
                config = inferenceConfig
            )
        } else {
            Log.d(TAG, "Loading MediaPipe model: ${config.name}")
            mediaPipeEngine.loadModel(
                modelPath = config.filePath,
                config = inferenceConfig
            )
        }
    }
    
    /**
     * Unload the currently loaded model from whichever engine was used
     */
    private fun unloadCurrentEngine() {
        when (currentEngineType) {
            EngineType.LLAMA -> {
                AppEventLogger.info(component = TAG, action = "unload_engine", details = "engine=LLAMA")
                val currentModel = _loadedModel.value
                if (currentModel?.isVisionModel == true) {
                    llamaEngine.unloadVisionModel()
                }
                llamaEngine.unloadModel()
            }
            EngineType.MEDIAPIPE -> {
                AppEventLogger.info(component = TAG, action = "unload_engine", details = "engine=MEDIAPIPE")
                mediaPipeEngine.unloadModel()
            }
            EngineType.NONE -> { /* Nothing to unload */ }
        }
        currentEngineType = EngineType.NONE
    }
    
    /**
     * Get model metadata from the active engine
     */
    private fun getModelMetadata(engineType: EngineType): String {
        return when (engineType) {
            EngineType.LLAMA -> llamaEngine.getModelInfo()
            EngineType.MEDIAPIPE -> mediaPipeEngine.getModelInfo().toString()
            EngineType.NONE -> ""
        }
    }
    
    /**
     * Check if vision is supported by the active engine
     */
    private fun isVisionSupported(engineType: EngineType): Boolean {
        return when (engineType) {
            EngineType.LLAMA -> llamaEngine.isVisionSupported()
            EngineType.MEDIAPIPE -> mediaPipeEngine.supportsVision
            EngineType.NONE -> false
        }
    }
    
    /**
     * Get the currently active engine type
     */
    fun getCurrentEngineType(): String {
        return when (currentEngineType) {
            EngineType.LLAMA -> "LlamaEngine (llama.cpp)"
            EngineType.MEDIAPIPE -> "MediaPipeEngine"
            EngineType.NONE -> "None"
        }
    }
    
    /**
     * Check if the current model uses MediaPipe engine
     */
    fun isMediaPipeModel(): Boolean = currentEngineType == EngineType.MEDIAPIPE
    
    /**
     * Check if the current model uses Llama engine
     */
    fun isLlamaModel(): Boolean = currentEngineType == EngineType.LLAMA
    
    override suspend fun unloadModel() = modelLoadMutex.withLock {
        val currentModel = _loadedModel.value
        _loadingState.value = ModelLoadingState.Unloading(currentModel?.name)
        
        unloadCurrentEngine()
        _loadedModel.value = null
        
        // Clear the persisted model info
        try {
            modelPreferences.clearLoadedModel()
            Log.d(TAG, "Cleared model from preferences")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing model from preferences", e)
        }
        
        _loadingState.value = ModelLoadingState.Idle
    }
    
    override fun getLoadedModel(): Flow<ModelInfo?> {
        return _loadedModel.asStateFlow()
    }
    
    override suspend fun getAvailableModels(): List<ModelInfo> {
        return modelManager.getAvailableModels()
    }
    
    override suspend fun deleteModel(filePath: String): Result<Unit> {
        // Unload if this is the loaded model
        if (_loadedModel.value?.filePath == filePath) {
            unloadModel()
        }
        return modelManager.deleteModel(filePath)
    }
    
    override suspend fun getModelInfo(filePath: String): ModelInfo? {
        return modelManager.getModelInfo(filePath)
    }
}
