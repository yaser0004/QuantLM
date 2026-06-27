package com.quantlm.yaser.data.repository

import android.os.Build
import android.util.Log
import com.quantlm.yaser.data.diagnostics.AppEventLogger
import com.quantlm.yaser.data.diagnostics.PerformanceSnapshotLogger
import com.quantlm.yaser.data.inference.DeviceCapabilityProbe
import com.quantlm.yaser.data.inference.EngineRegistry
import com.quantlm.yaser.data.inference.LiteRTEngine
import com.quantlm.yaser.data.inference.LlamaEngine
import com.quantlm.yaser.data.inference.MediaPipeEngine
import com.quantlm.yaser.data.inference.ModelManager
import com.quantlm.yaser.data.local.GenerationPreferences
import com.quantlm.yaser.data.local.ModelPreferences
import com.quantlm.yaser.domain.inference.InferenceConfig
import com.quantlm.yaser.domain.inference.LoadOptions
import com.quantlm.yaser.domain.model.AvailableModels
import com.quantlm.yaser.domain.model.ModelCapability
import com.quantlm.yaser.domain.model.ModelConfig
import com.quantlm.yaser.domain.model.ModelFileValidator
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
 * - LiteRT-LM models (.litertlm) -> LiteRTEngine (native LiteRT-LM)
 * - Task models (.task) -> MediaPipeEngine
 * - TFLite models (.tflite, .literlm) -> MediaPipeEngine
 */
@Singleton
class ModelRepositoryImpl @Inject constructor(
    private val llamaEngine: LlamaEngine,
    private val mediaPipeEngine: MediaPipeEngine,
    private val liteRTEngine: LiteRTEngine,
    private val modelManager: ModelManager,
    private val modelPreferences: ModelPreferences,
    private val generationPreferences: GenerationPreferences,
    private val deviceCapabilityProbe: DeviceCapabilityProbe,
    private val engineRegistry: EngineRegistry,
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

    // Item 1: identity of the model currently loaded into a native engine,
    // including the effective hardware settings it was loaded with. Used to skip
    // a full (slow) native unload+reload when the user re-selects the model that
    // is already loaded with identical settings. Null when nothing is loaded.
    private var loadedEffectiveKey: String? = null

    private fun effectiveKey(c: ModelConfig): String =
        "${c.filePath}|gpu=${c.nGpuLayers}|mode=${c.accelerationMode}|mmproj=${c.mmprojPath}|g4=${c.gemma4GpuOverride}"

    private enum class EngineType {
        NONE, LLAMA, MEDIAPIPE, LITERT
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
                        val restoreResult = loadModelInternal(config, isRestore = true)
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
            } catch (e: Throwable) {
                // Throwable, not Exception: restoring a corrupt/incompatible
                // model can fail with an Error (UnsatisfiedLinkError, OOM, or a
                // native Throwable). Catching only Exception here would let the
                // app crash on startup. The bad model is dropped from prefs so
                // the next launch starts clean.
                AppEventLogger.error(
                    component = TAG,
                    action = "restore_exception",
                    details = "reason=${e.message ?: "unknown"}, type=${e.javaClass.simpleName}",
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
                if (!deviceCapabilityProbe.supportsGpuInference()) {
                    Log.w(TAG, "GPU acceleration requested but device reports no Vulkan / GL ES 3 GPU compute; loading on CPU")
                    AppEventLogger.warn(
                        component = TAG,
                        action = "gpu_unavailable_forced_cpu",
                        details = "vulkan=${deviceCapabilityProbe.isVulkanAvailable()}, glEsMajor=${deviceCapabilityProbe.openGlEsMajorVersion()}"
                    )
                    0
                } else {
                    when {
                        callerLayers > 0 -> callerLayers
                        persistedLayers > 0 -> persistedLayers
                        else -> 1
                    }
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
     * - .litertlm → LiteRTEngine (native LiteRT-LM)
     * - .task → MediaPipeEngine (MediaPipe tasks-genai)
     * - .tflite, .literlm → MediaPipeEngine (will attempt to load)
     */
    private fun getEngineType(filePath: String): EngineType {
        val lowerPath = filePath.lowercase()
        return when {
            lowerPath.endsWith(".gguf") -> EngineType.LLAMA
            // LiteRT-LM models use the native LiteRT-LM engine
            lowerPath.endsWith(".litertlm") -> EngineType.LITERT
            lowerPath.endsWith(".task") -> EngineType.MEDIAPIPE
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
    
    override suspend fun loadModel(config: ModelConfig): Result<Unit> =
        loadModelInternal(config, isRestore = false)

    /**
     * Shared load implementation. [isRestore] is true only for the automatic
     * load-on-startup of the previously-used model. In that path the Gemma-4
     * GPU override is deliberately ignored: a device that hard-crashes on
     * Gemma-4 GPU init must still be able to boot (on CPU) instead of getting
     * stuck in a restore crash-loop. Manual, user-initiated loads honor it.
     */
    private suspend fun loadModelInternal(
        config: ModelConfig,
        isRestore: Boolean,
    ): Result<Unit> = modelLoadMutex.withLock {
      val perfReason = "load:${config.name}"
      PerformanceSnapshotLogger.begin(perfReason)
      try {
        val persistedHardware = generationPreferences
            .getHardwareSettings()
            .firstOrNull()
        val effectiveConfig = if (persistedHardware != null) {
            config.copy(
                nGpuLayers = resolveGpuLayers(config.nGpuLayers, persistedHardware),
                accelerationMode = persistedHardware.accelerationMode,
                gemma4GpuOverride = !isRestore && persistedHardware.gemma4GpuOverride
            )
        } else {
            config
        }

        // Item 1: already-loaded short-circuit. Re-selecting the model that is
        // already loaded with identical effective settings would otherwise do a
        // full native unload + reload (seconds of GPU/context init). Skip it. A
        // hardware-settings change alters the key, so a genuine reload still runs.
        if (_loadedModel.value != null && loadedEffectiveKey == effectiveKey(effectiveConfig)) {
            AppEventLogger.info(TAG, "load_skipped_already_loaded", "model=${effectiveConfig.name}")
            _loadingState.value = ModelLoadingState.Loaded(effectiveConfig.name)
            _loadingState.value = ModelLoadingState.Idle
            return@withLock Result.success(Unit)
        }

        Log.w(
            TAG,
            "GPUDIAG load: persistedHardware=" +
                (persistedHardware?.let { "mode=${it.accelerationMode},gpuLayers=${it.gpuLayers}" } ?: "NULL") +
                ", effectiveConfig.accelerationMode=${effectiveConfig.accelerationMode}" +
                ", effectiveConfig.nGpuLayers=${effectiveConfig.nGpuLayers}" +
                ", supportsGpuInference=${deviceCapabilityProbe.supportsGpuInference()}"
        )

        val currentModel = _loadedModel.value
        
        // Set loading state - show switching if there's a current model
        val loadingHint = run {
            val file = File(effectiveConfig.filePath)
            val isLargeGguf = file.name.endsWith(".gguf", ignoreCase = true) &&
                file.length() > 2_000_000_000L &&
                effectiveConfig.nGpuLayers > 0
            if (isLargeGguf) "(large model — may take 2–3 min)" else null
        }
        if (currentModel != null) {
            _loadingState.value = ModelLoadingState.Switching(currentModel.name, effectiveConfig.name)
        } else {
            _loadingState.value = ModelLoadingState.Loading(effectiveConfig.name, loadingHint)
        }
        
        // Unload any currently loaded model first
        if (currentModel != null) {
            unloadCurrentEngine()
        }
        
        // Manifest-level loadability guard. Some models in [AvailableModels]
        // are downloadable for completeness but cannot be loaded by the
        // bundled runtime (e.g. Gemma 4 multimodal `.litertlm` triggers an
        // absl::CHECK abort inside tasks-genai 0.10.32). Refuse early so the
        // failure surfaces as a clean UI error instead of a SIGABRT.
        val preflightEntry = AvailableModels.getAllModels()
            .firstOrNull { File(effectiveConfig.filePath).name == it.fileName }
        if (preflightEntry != null && !preflightEntry.loadable) {
            val reason = preflightEntry.unsupportedReason
                ?: "Model is not supported by the bundled inference runtime."
            AppEventLogger.error(
                component = TAG,
                action = "load_blocked_unloadable_manifest",
                details = "name=${effectiveConfig.name}, file=${preflightEntry.fileName}, reason=$reason"
            )
            _loadingState.value = ModelLoadingState.Error(reason)
            _loadingState.value = ModelLoadingState.Idle
            return@withLock Result.failure(IllegalStateException(reason))
        }

        // Determine which engine to use — from the file extension, then
        // cross-checked against the file's actual magic bytes so a renamed or
        // mislabeled file is not routed to an engine that would crash on it.
        val extensionEngine = getEngineType(effectiveConfig.filePath)
        val engineType = when (ModelFormatDetector.detect(File(effectiveConfig.filePath))) {
            ModelFormatDetector.DetectedFormat.GGUF -> {
                if (extensionEngine != EngineType.LLAMA) {
                    Log.w(TAG, "${effectiveConfig.name} is GGUF by content but '$extensionEngine' by extension; routing to LlamaEngine")
                    AppEventLogger.warn(
                        component = TAG,
                        action = "engine_route_corrected",
                        details = "name=${effectiveConfig.name}, from=$extensionEngine, to=LLAMA, reason=gguf_magic"
                    )
                }
                EngineType.LLAMA
            }
            ModelFormatDetector.DetectedFormat.ZIP_BUNDLE -> {
                if (extensionEngine == EngineType.LLAMA) {
                    val reason = "This file is a bundle (.task / .litertlm), not a GGUF model. Re-download it or correct the file extension."
                    AppEventLogger.error(
                        component = TAG,
                        action = "engine_route_mismatch",
                        details = "name=${effectiveConfig.name}, detected=ZIP_BUNDLE, extensionEngine=$extensionEngine"
                    )
                    _loadingState.value = ModelLoadingState.Error(reason)
                    _loadingState.value = ModelLoadingState.Idle
                    return@withLock Result.failure(IllegalStateException(reason))
                }
                extensionEngine
            }
            null -> extensionEngine
        }
        val modelFormat = getModelFormat(effectiveConfig.filePath)

        Log.d(TAG, "Loading model: ${effectiveConfig.name}, format: $modelFormat, engine: $engineType")
        AppEventLogger.info(
            component = TAG,
            action = "load_requested",
            details = "name=${effectiveConfig.name}, format=$modelFormat, engine=$engineType, vision=${effectiveConfig.isVisionModel}, context=${effectiveConfig.contextLength}, gpuLayers=${effectiveConfig.nGpuLayers}"
        )
        
        // Native engine init can fail in ways that are NOT java.lang.Exception:
        // a missing/incompatible native library throws UnsatisfiedLinkError, a
        // too-large context throws OutOfMemoryError, and JNI can surface other
        // Throwables. Engine code catches Exception but not Error, so without
        // this guard such a failure would escape loadModel() and crash the app.
        // Catching Throwable here converts every load failure into a Result.
        val result: Result<Unit> = try {
            when (engineType) {
                EngineType.LLAMA -> loadWithLlamaEngine(effectiveConfig)
                EngineType.MEDIAPIPE -> loadWithMediaPipeEngine(effectiveConfig)
                EngineType.LITERT -> loadWithLiteRTEngine(effectiveConfig)
                EngineType.NONE -> Result.failure(Exception("Unsupported model format"))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Engine load threw for ${effectiveConfig.name}", t)
            AppEventLogger.error(
                component = TAG,
                action = "load_threw_uncaught",
                details = "name=${effectiveConfig.name}, engine=$engineType, type=${t.javaClass.simpleName}",
                throwable = t
            )
            // Engine may have left native state half-initialized — clear it.
            try {
                unloadCurrentEngine()
            } catch (cleanupError: Throwable) {
                Log.w(TAG, "Cleanup after failed load also threw", cleanupError)
            }
            Result.failure(
                IllegalStateException(
                    "Could not load this model: ${t.message ?: t.javaClass.simpleName}. " +
                        "The model may be unsupported by this device's runtime.",
                    t
                )
            )
        }
        
        if (result.isSuccess) {
            currentEngineType = engineType
            // Force-unload any other engine that may still be carrying native state from
            // a prior load — defense in depth against the GPU buffer / KV cache leaks
            // that previously left two engines simultaneously mapped.
            engineRegistry.setActive(
                when (engineType) {
                    EngineType.LLAMA -> EngineRegistry.Active.LLAMA
                    EngineType.LITERT -> EngineRegistry.Active.LITERT
                    EngineType.MEDIAPIPE -> EngineRegistry.Active.MEDIAPIPE
                    EngineType.NONE -> EngineRegistry.Active.NONE
                }
            )
            engineRegistry.releaseInactive()

            val effectiveIsVision = effectiveConfig.isVisionModel || isVisionSupported(engineType)
            val manifestEntry = AvailableModels.getAllModels()
                .firstOrNull { java.io.File(effectiveConfig.filePath).name == it.fileName }
            val manifestCaps = manifestEntry?.capabilities ?: emptySet()
            val runtimeCaps = if (effectiveIsVision) {
                manifestCaps + ModelCapability.VISION
            } else {
                manifestCaps - ModelCapability.VISION
            }
            val incompleteCaps = if (!effectiveIsVision && ModelCapability.VISION in manifestCaps) {
                setOf(ModelCapability.VISION)
            } else {
                emptySet()
            }

            val modelInfo = ModelInfo(
                name = effectiveConfig.name,
                filePath = effectiveConfig.filePath,
                size = effectiveConfig.size,
                isLoaded = true,
                metadata = getModelMetadata(engineType),
                isVisionModel = effectiveIsVision,
                mmprojPath = effectiveConfig.mmprojPath,
                capabilities = runtimeCaps,
                incompleteCapabilities = incompleteCaps,
            )
            _loadedModel.value = modelInfo
            loadedEffectiveKey = effectiveKey(effectiveConfig)

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
            engineRegistry.setActive(EngineRegistry.Active.NONE)
            // The previously loaded model (if any) was already unloaded at the
            // top of this function — leaving it in _loadedModel would advertise
            // a model as loaded that no engine is actually serving.
            _loadedModel.value = null
            loadedEffectiveKey = null
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
      } finally {
        PerformanceSnapshotLogger.end(perfReason)
      }
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

        // GGUF preflight: the llama.cpp loader can abort the process on a
        // corrupt/truncated/wrong-format file instead of returning an error.
        // Validate on the JVM side first so a bad file becomes a clean failure
        // instead of a native crash. This mirrors the MediaPipe preflight and
        // closes the gap where the GGUF path trusted the downloaded file.
        val modelValidation = ModelFileValidator.validateGguf(File(config.filePath))
        if (modelValidation is ModelFileValidator.Result.Invalid) {
            AppEventLogger.error(
                component = TAG,
                action = "llama_preflight_failed",
                details = "name=${config.name}, file=${File(config.filePath).name}, reason=${modelValidation.reason}"
            )
            return Result.failure(IllegalStateException(modelValidation.reason))
        }
        if (config.isVisionModel && config.mmprojPath != null) {
            val mmprojValidation = ModelFileValidator.validateGguf(File(config.mmprojPath))
            if (mmprojValidation is ModelFileValidator.Result.Invalid) {
                AppEventLogger.error(
                    component = TAG,
                    action = "llama_preflight_failed",
                    details = "name=${config.name}, mmproj=${File(config.mmprojPath).name}, reason=${mmprojValidation.reason}"
                )
                return Result.failure(
                    IllegalStateException("Vision projector: ${mmprojValidation.reason}")
                )
            }
        }
        AppEventLogger.info(
            component = TAG,
            action = "llama_preflight_passed",
            details = "name=${config.name}, bytes=${File(config.filePath).length()}"
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
        
        // Thread the user-selected accelerator (GPU/CPU) into the engine so it
        // honors the Settings choice instead of MediaPipe's device default.
        // Null (no preference read) keeps the prior 2-param behavior.
        val loadOptions = config.accelerationMode?.let { LoadOptions(accelerationMode = it) }

        Log.w(
            TAG,
            "GPUDIAG MediaPipe: config.accelerationMode=${config.accelerationMode}" +
                ", loadOptions=${loadOptions?.accelerationMode ?: "NULL (2-param path)"}"
        )

        return if (config.isVisionModel && config.mmprojPath != null) {
            Log.d(TAG, "Loading MediaPipe vision model: ${config.name}")
            if (loadOptions != null) {
                mediaPipeEngine.loadVisionModel(
                    modelPath = config.filePath,
                    mmprojPath = config.mmprojPath,
                    config = inferenceConfig,
                    options = loadOptions
                )
            } else {
                mediaPipeEngine.loadVisionModel(
                    modelPath = config.filePath,
                    mmprojPath = config.mmprojPath,
                    config = inferenceConfig
                )
            }
        } else {
            Log.d(TAG, "Loading MediaPipe model: ${config.name}")
            if (loadOptions != null) {
                mediaPipeEngine.loadModel(
                    modelPath = config.filePath,
                    config = inferenceConfig,
                    options = loadOptions
                )
            } else {
                mediaPipeEngine.loadModel(
                    modelPath = config.filePath,
                    config = inferenceConfig
                )
            }
        }
    }
    
    /**
     * Load model using the native LiteRTEngine (for .litertlm models)
     */
    private suspend fun loadWithLiteRTEngine(config: ModelConfig): Result<Unit> {
        val modelFile = File(config.filePath)
        if (!modelFile.exists()) {
            AppEventLogger.error(
                component = TAG,
                action = "litert_preflight_failed",
                details = "missing_file path=${config.filePath}"
            )
            return Result.failure(IllegalArgumentException("Model file not found: ${config.filePath}"))
        }

        if (modelFile.length() < MIN_MODEL_BYTES) {
            AppEventLogger.error(
                component = TAG,
                action = "litert_preflight_failed",
                details = "file_too_small name=${modelFile.name}, bytes=${modelFile.length()}"
            )
            return Result.failure(
                IllegalStateException(
                    "Model file is too small (${modelFile.length()} bytes). Please delete and re-download the model."
                )
            )
        }

        if (isLikelyHttpErrorPayload(modelFile)) {
            AppEventLogger.error(
                component = TAG,
                action = "litert_preflight_failed",
                details = "error_payload_detected name=${modelFile.name}"
            )
            return Result.failure(
                IllegalStateException(
                    "Model file appears invalid (downloaded error page). Please delete and re-download the model."
                )
            )
        }

        if (!isMediaPipeAbiSupported()) {
            AppEventLogger.error(
                component = TAG,
                action = "litert_preflight_failed",
                details = "unsupported_abi abis=${Build.SUPPORTED_ABIS.joinToString()}"
            )
            return Result.failure(
                IllegalStateException(
                    "LiteRT-LM models require arm64-v8a. Device ABIs: ${Build.SUPPORTED_ABIS.joinToString()}"
                )
            )
        }

        // Gemma 4: check for chipsets known to crash with LiteRT-LM 0.11.0 before
        // attempting to load. The crashes are native SIGABRT / SIGSEGV that bypass
        // all JVM exception handling — the only defence is refusing to load at all.
        if (modelFile.name.contains("gemma-4", ignoreCase = true)) {
            val barrier = deviceCapabilityProbe.gemma4LoadBarrier()
            if (barrier != null) {
                AppEventLogger.error(
                    component = TAG,
                    action = "gemma4_chipset_blocked",
                    details = "name=${modelFile.name}, hw=${Build.HARDWARE}, " +
                        "soc=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else "n/a"}"
                )
                return Result.failure(IllegalStateException(barrier))
            }
        }

        // Gemma 4 multimodal models need at minimum 8192 tokens; with the default
        // ModelConfig.contextLength of 2048, the LiteRT-LM KV cache overflows after
        // only a few turns and triggers an absl::CHECK abort (SIGABRT) in the native
        // runtime that cannot be caught on the JVM side. Enforce the minimum here.
        // Gemma 3n models are similarly limited; 4096 is the safe floor for them.
        // For all other LiteRT-LM models, use a floor of 4096 (the smallest practical
        // context; the old default of 2048 is too tight for multi-turn conversations).
        val safeContextSize = when {
            modelFile.name.contains("gemma-4", ignoreCase = true) ->
                maxOf(config.contextLength, 8192)
            modelFile.name.contains("gemma-3n", ignoreCase = true) ->
                maxOf(config.contextLength, 4096)
            else -> maxOf(config.contextLength, 4096)
        }

        // Gemma 4 multimodal GPU initialization triggers a native absl::CHECK abort
        // on many devices — caught by the Kotlin VM as a crash, not an exception.
        // Force CPU-only to keep loading stable. The user can opt out via the
        // "Gemma-4 GPU Override" setting, accepting the native-crash risk.
        val isGemma4 = modelFile.name.contains("gemma-4", ignoreCase = true)
        val safeGpuLayers = if (isGemma4 && !config.gemma4GpuOverride) {
            Log.w(TAG, "Gemma 4 detected: forcing CPU backend (GPU causes native SIGABRT on many devices)")
            AppEventLogger.warn(
                component = TAG,
                action = "gemma4_cpu_forced",
                details = "name=${modelFile.name}, requestedGpuLayers=${config.nGpuLayers}"
            )
            0
        } else {
            if (isGemma4) {
                Log.w(TAG, "Gemma 4 GPU override ENABLED — bypassing CPU lock (may crash natively)")
                AppEventLogger.warn(
                    component = TAG,
                    action = "gemma4_gpu_override",
                    details = "name=${modelFile.name}, requestedGpuLayers=${config.nGpuLayers}"
                )
            }
            config.nGpuLayers
        }

        val inferenceConfig = InferenceConfig(
            nThreads = config.nThreads,
            nGpuLayers = safeGpuLayers,
            contextSize = safeContextSize
        )

        AppEventLogger.info(
            component = TAG,
            action = "litert_preflight_passed",
            details = "name=${modelFile.name}, bytes=${modelFile.length()}, contextSize=$safeContextSize, gpuLayers=$safeGpuLayers"
        )

        // Resolve multimodal backends from the manifest: LiteRT-LM needs the vision
        // and audio backends explicitly enabled at load time. ModelConfig only carries
        // an isVisionModel flag, so audio is derived from the manifest capability set.
        val manifestEntry = AvailableModels.getAllModels()
            .firstOrNull { File(config.filePath).name == it.fileName }
        val caps = manifestEntry?.capabilities ?: emptySet()
        val enableVision = config.isVisionModel || ModelCapability.VISION in caps
        val enableAudio = ModelCapability.AUDIO in caps

        return if (enableVision || enableAudio) {
            Log.d(TAG, "Loading LiteRT-LM multimodal model: ${config.name} (vision=$enableVision, audio=$enableAudio)")
            liteRTEngine.loadMultimodalModel(
                modelPath = config.filePath,
                config = inferenceConfig,
                enableVision = enableVision,
                enableAudio = enableAudio,
            )
        } else {
            Log.d(TAG, "Loading LiteRT-LM model: ${config.name}")
            // Honor the user's accelerator choice. safeGpuLayers already encodes
            // the Gemma-4 CPU-forcing and the CPU/GPU/no-GPU resolution, so a 0
            // here means CPU regardless of the raw preference. When no
            // preference has been read, this matches the engine's prior
            // nGpuLayers-based inference exactly.
            val resolvedAccel = if (safeGpuLayers > 0) {
                config.accelerationMode ?: GenerationPreferences.HardwareAccelerationMode.GPU
            } else {
                GenerationPreferences.HardwareAccelerationMode.CPU
            }
            Log.w(
                TAG,
                "GPUDIAG LiteRT: safeGpuLayers=$safeGpuLayers" +
                    ", config.accelerationMode=${config.accelerationMode}, resolvedAccel=$resolvedAccel"
            )
            liteRTEngine.loadModel(
                modelPath = config.filePath,
                config = inferenceConfig,
                options = LoadOptions(accelerationMode = resolvedAccel)
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
            EngineType.LITERT -> {
                AppEventLogger.info(component = TAG, action = "unload_engine", details = "engine=LITERT")
                liteRTEngine.unloadModel()
            }
            EngineType.NONE -> { /* Nothing to unload */ }
        }
        currentEngineType = EngineType.NONE
        engineRegistry.setActive(EngineRegistry.Active.NONE)
    }
    
    /**
     * Get model metadata from the active engine
     */
    private fun getModelMetadata(engineType: EngineType): String {
        return when (engineType) {
            EngineType.LLAMA -> llamaEngine.getModelInfo()
            EngineType.MEDIAPIPE -> mediaPipeEngine.getModelInfo().toString()
            EngineType.LITERT -> liteRTEngine.getModelInfo().toString()
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
            EngineType.LITERT -> liteRTEngine.isVisionSupported()
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
            EngineType.LITERT -> "LiteRTEngine (LiteRT-LM)"
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
        loadedEffectiveKey = null

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
