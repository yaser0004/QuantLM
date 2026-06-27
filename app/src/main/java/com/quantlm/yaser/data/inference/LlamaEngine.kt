package com.quantlm.yaser.data.inference

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import com.quantlm.yaser.data.diagnostics.AppEventLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import com.quantlm.yaser.domain.inference.InferenceError
import com.quantlm.yaser.domain.inference.SamplingCapabilities
import com.quantlm.yaser.domain.inference.SamplingParam
import com.quantlm.yaser.domain.model.ModelCapability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LlamaEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "LlamaEngine"

        /** Lower bound for the load-time context bisection retry. */
        private const val MIN_CONTEXT_SIZE = 512

        /**
         * Whether libquantlm.so loaded successfully for this device's ABI.
         * Checked before any load so an unsupported-ABI device fails fast with
         * a clear message instead of an opaque UnsatisfiedLinkError mid-call.
         */
        @Volatile
        private var nativeLibraryLoaded = false

        init {
            try {
                System.loadLibrary("quantlm")
                nativeLibraryLoaded = true
                Log.i(TAG, "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library", e)
            }
        }
    }
    
    @Volatile
    private var isModelLoaded = false

    /**
     * Generation in-flight guard. AtomicBoolean (not @Volatile) so arming is a
     * single compare-and-set — this eliminates the read/reset/re-arm race the
     * old forced-reset code papered over.
     */
    private val isGenerating = AtomicBoolean(false)

    /**
     * Monotonic id assigned at arm time. A stream callback only disarms
     * [isGenerating] when the current id still matches the one it captured, so
     * a late callback from a stopped run cannot disarm a newer generation.
     */
    private val generationId = AtomicLong(0L)

    @Volatile
    private var isVisionModelLoaded = false
    
    @Volatile
    private var loadedModelName: String? = null

    @Volatile
    private var lastLoadedGpuLayers: Int = 0

    /**
     * Effective context size of the currently loaded model (post-bisection).
     * Zero when no model is loaded. Used by the use-case layer to budget the
     * conversation history that gets re-tokenized on every turn.
     */
    @Volatile
    private var loadedContextSize: Int = 0

    fun getLoadedContextSize(): Int = loadedContextSize
    
    // Native method declarations
    private external fun nativeInit(): Boolean
    
    private external fun nativeLoadModel(
        modelPath: String,
        nThreads: Int,
        nGpuLayers: Int,
        contextSize: Int,
        flashAttn: Boolean,
        useMlock: Boolean
    ): Boolean
    
    private external fun nativeUnloadModel()

    // Fix [2.6]: JNI error code from llama_jni.cpp (see InferenceError)
    private external fun nativeGetLastInferenceErrorCode(): Int
    
    private external fun nativeGenerate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        repeatLastN: Int,
        minP: Float,
        tfsZ: Float,
        typicalP: Float,
        mirostat: Int,
        mirostatTau: Float,
        mirostatEta: Float
    ): String
    
    private external fun nativeGenerateStream(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        repeatLastN: Int,
        minP: Float,
        tfsZ: Float,
        typicalP: Float,
        mirostat: Int,
        mirostatTau: Float,
        mirostatEta: Float,
        callback: StreamCallback
    )
    
    private external fun nativeStopGeneration()
    
    private external fun nativeGetModelInfo(): String

    private external fun nativeIsVulkanCompiled(): Boolean

    // Runtime Vulkan state (a GPU device actually registered), as opposed to
    // the compile-time flag reported by nativeIsVulkanCompiled().
    private external fun nativeIsVulkanInitialized(): Boolean
    
    private external fun nativeCleanup()
    
    // Vision model methods
    private external fun nativeLoadVisionModel(
        modelPath: String,
        mmprojPath: String,
        nThreads: Int,
        nGpuLayers: Int,
        contextSize: Int
    ): Boolean
    
    private external fun nativeUnloadVisionModel()
    
    private external fun nativeGenerateWithImage(
        prompt: String,
        imagePath: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        repeatLastN: Int,
        minP: Float,
        tfsZ: Float,
        typicalP: Float,
        mirostat: Int,
        mirostatTau: Float,
        mirostatEta: Float
    ): String
    
    private external fun nativeGenerateStreamWithImage(
        prompt: String,
        imagePath: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        repeatLastN: Int,
        minP: Float,
        tfsZ: Float,
        typicalP: Float,
        mirostat: Int,
        mirostatTau: Float,
        mirostatEta: Float,
        callback: StreamCallback
    )
    
    private external fun nativeIsVisionSupported(): Boolean

    private external fun nativeGetChatTemplate(): String

    // Phase 1 (Subphase D): conversation reset.
    private external fun nativeResetConversation()

    // GGML_BACKEND_DL builds (arm64): tells the native side where the packaged
    // libggml-cpu-android_* variant libraries live, so the best one for this
    // SoC can be dlopened at backend init. No-op on monolithic builds.
    private external fun nativeSetBackendSearchPath(path: String)

    init {
        try {
            nativeSetBackendSearchPath(context.applicationInfo.nativeLibraryDir)
            nativeInit()
        } catch (e: Throwable) {
            // Throwable: a missing native library throws UnsatisfiedLinkError
            // (an Error, not Exception). Swallow it so engine construction
            // never crashes; load attempts then fail cleanly per-call. Persist
            // it to the diagnostics log — a native-init failure disables the
            // whole GGUF path, so it must be visible in the session log.
            Log.e(TAG, "Failed to initialize native engine - native library may not be loaded", e)
            AppEventLogger.error(TAG, "native_init_failed", "nativeLibDir=${context.applicationInfo.nativeLibraryDir}", e)
        }
    }
    
    /**
     * Get the chat template type embedded in the loaded model.
     * Returns null if no model is loaded or no template is embedded.
     * Common values: "chatml", "llama3", "phi3", "gemma", "qwen", etc.
     */
    fun getChatTemplate(): String? {
        if (!isModelLoaded && !isVisionModelLoaded) {
            return null
        }
        val template = nativeGetChatTemplate()
        return template.ifEmpty { null }
    }
    
    suspend fun loadModel(
        modelPath: String,
        nThreads: Int = 4,
        nGpuLayers: Int = 0,
        contextSize: Int = 2048,
        useFlashAttention: Boolean = true,
        useMlock: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
      InferenceThreadDiscipline.runWithInferencePriority {
        try {
            if (!nativeLibraryLoaded) {
                return@runWithInferencePriority Result.failure(
                    Exception("QuantLM native library is unavailable for this device's CPU architecture (ABI).")
                )
            }

            val file = File(modelPath)
            if (!file.exists()) {
                return@runWithInferencePriority Result.failure(Exception("Model file not found: $modelPath"))
            }

            if (isModelLoaded) {
                unloadModel()
            }

            // Cap thread count so System UI always has headroom on small-core
            // devices; see [InferenceThreadDiscipline.computeInferenceThreadCount].
            val effectiveThreads = InferenceThreadDiscipline.computeInferenceThreadCount(nThreads)

            // Context bisection: on a context/KV init failure (code 2), retry
            // with a halved context down to MIN_CONTEXT_SIZE. Other failure
            // codes (corrupt file, mmproj) are not retried — a smaller context
            // would not help.
            var effectiveContext = contextSize.coerceAtLeast(MIN_CONTEXT_SIZE)
            var success = false
            var lastErrorCode = InferenceError.NATIVE_CODE_NONE
            // Native side may have partially allocated context/KV/Vulkan state even
            // when nativeLoadModel returns false or throws. Track so the failure path
            // always tears down rather than leaking the allocation across retries.
            var nativeLoadAttempted = false
            while (true) {
                nativeLoadAttempted = true
                success = nativeLoadModel(
                    modelPath, effectiveThreads, nGpuLayers, effectiveContext, useFlashAttention, useMlock
                )
                if (success) break
                lastErrorCode = nativeGetLastInferenceErrorCode()
                if (lastErrorCode != InferenceError.NATIVE_CODE_CONTEXT_INIT_FAILED ||
                    effectiveContext <= MIN_CONTEXT_SIZE
                ) {
                    break
                }
                // Drop the partial allocation from the failed attempt before bisecting.
                forceNativeUnload("bisection retry")
                val reduced = (effectiveContext / 2).coerceAtLeast(MIN_CONTEXT_SIZE)
                Log.w(TAG, "Context init failed at $effectiveContext; retrying at $reduced")
                AppEventLogger.warn(
                    component = TAG,
                    action = "model_load_context_bisection",
                    details = "path=$modelPath, from=$effectiveContext, to=$reduced"
                )
                effectiveContext = reduced
            }

            if (success) {
                isModelLoaded = true
                lastLoadedGpuLayers = nGpuLayers
                loadedContextSize = effectiveContext
                // Extract model name from file path (without extension)
                loadedModelName = file.nameWithoutExtension
                Log.i(TAG, "Model loaded successfully: $modelPath (context=$effectiveContext)")
                AppEventLogger.info(
                    component = TAG,
                    action = "model_loaded",
                    details = "name=${loadedModelName ?: file.name}, bytes=${file.length()}, context=$effectiveContext, threads=$effectiveThreads (requested=$nThreads), gpuLayers=$nGpuLayers, flashAttn=$useFlashAttention, mlock=$useMlock"
                )
                Result.success(Unit)
            } else {
                if (nativeLoadAttempted) forceNativeUnload("load_failed_code=$lastErrorCode")
                val failure = InferenceError.fromNativeCode(
                    lastErrorCode,
                    "nativeLoadModel returned false for $modelPath"
                )
                AppEventLogger.error(
                    component = TAG,
                    action = "model_load_failed",
                    details = "path=$modelPath, reason=${failure.message ?: "unknown"}",
                    throwable = failure
                )
                Result.failure(failure)
            }
        } catch (e: Throwable) {
            // Throwable: nativeLoadModel can throw UnsatisfiedLinkError or
            // OutOfMemoryError (Errors, not Exceptions). Catching only
            // Exception would let those escape and terminate the app.
            Log.e(TAG, "Error loading model", e)
            AppEventLogger.error(
                component = TAG,
                action = "model_load_exception",
                details = "path=$modelPath, type=${e.javaClass.simpleName}",
                throwable = e
            )
            // Best-effort native cleanup even though isModelLoaded never flipped true.
            forceNativeUnload("exception:${e.javaClass.simpleName}")
            isModelLoaded = false
            Result.failure(e)
        }
      }
    }

    /**
     * Unload the native model regardless of [isModelLoaded] — used by the load failure
     * and exception paths where partial state may exist but the loaded flag never flipped.
     * `nativeUnloadModel` is a no-op when the native side has nothing allocated, so calling
     * it defensively is safe.
     */
    private fun forceNativeUnload(reason: String) {
        try {
            nativeUnloadModel()
        } catch (t: Throwable) {
            Log.w(TAG, "forceNativeUnload($reason) ignored failure", t)
        }
        lastLoadedGpuLayers = 0
        loadedContextSize = 0
        loadedModelName = null
    }
    
    fun unloadModel() {
        if (isModelLoaded) {
            try {
                nativeUnloadModel()
            } catch (e: Throwable) {
                // Never let teardown crash the app — log and force state reset
                // so a later load still starts from a clean slate.
                Log.e(TAG, "Error during native model unload", e)
            }
            isModelLoaded = false
            // nativeUnloadModel drops the vision projector (mtmd) too, so the
            // Kotlin flag must follow — a stale true here would route image
            // requests at native state that no longer exists.
            isVisionModelLoaded = false
            lastLoadedGpuLayers = 0
            loadedContextSize = 0
            loadedModelName = null
            Log.i(TAG, "Model unloaded")
            AppEventLogger.info(component = TAG, action = "model_unloaded")
        }
    }
    
    suspend fun generate(
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        topK: Int = 40,
        repeatPenalty: Float = 1.1f,
        repeatLastN: Int = 64,
        minP: Float = 0.05f,
        tfsZ: Float = 1.0f,
        typicalP: Float = 1.0f,
        mirostat: Int = 0,
        mirostatTau: Float = 5.0f,
        mirostatEta: Float = 0.1f
    ): Result<String> = withContext(Dispatchers.IO) {
      InferenceThreadDiscipline.runWithInferencePriority {
        try {
            if (!isModelLoaded) {
                return@runWithInferencePriority Result.failure(Exception("No model loaded"))
            }

            isGenerating.set(true)
            val response = nativeGenerate(
                prompt,
                maxTokens,
                temperature,
                topP,
                topK,
                repeatPenalty,
                repeatLastN,
                minP,
                tfsZ,
                typicalP,
                mirostat,
                mirostatTau,
                mirostatEta
            )
            isGenerating.set(false)

            Result.success(response)
        } catch (e: Throwable) {
            // Throwable: JNI can throw Errors (OOM, UnsatisfiedLinkError) —
            // catching only Exception would leak the isGenerating guard and
            // block every subsequent generation until app restart.
            isGenerating.set(false)
            Log.e(TAG, "Generation error", e)
            Result.failure(e)
        }
      }
    }

    suspend fun generateStream(
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        topK: Int = 40,
        repeatPenalty: Float = 1.1f,
        repeatLastN: Int = 64,
        minP: Float = 0.05f,
        tfsZ: Float = 1.0f,
        typicalP: Float = 1.0f,
        mirostat: Int = 0,
        mirostatTau: Float = 5.0f,
        mirostatEta: Float = 0.1f,
        onToken: (String) -> Unit,
        onComplete: () -> Unit
    ) = withContext(Dispatchers.IO) {
      InferenceThreadDiscipline.runWithInferencePriority {
        var armed = false
        var myGenerationId = 0L
        try {
            Log.d(TAG, "=== GENERATE STREAM START ===")
            Log.d(TAG, "isModelLoaded: $isModelLoaded")
            Log.d(TAG, "isGenerating (before): ${isGenerating.get()}")
            Log.d(TAG, "maxTokens: $maxTokens, temperature: $temperature")
            Log.d(TAG, "Prompt length: ${prompt.length} chars")
            AppEventLogger.info(
                component = TAG,
                action = "generate_stream_start",
                details = "model=${loadedModelName ?: "unknown"}, promptChars=${prompt.length}, maxTokens=$maxTokens, temperature=$temperature"
            )

            if (!isModelLoaded) {
                Log.e(TAG, "BLOCKED: No model loaded")
                throw Exception("No model loaded")
            }

            if (!isGenerating.compareAndSet(false, true)) {
                Log.e(TAG, "BLOCKED: generation already in progress")
                throw Exception("Generation already in progress")
            }
            armed = true
            myGenerationId = generationId.incrementAndGet()

            Log.d(TAG, "Armed isGenerating (id=$myGenerationId), starting native generation")

            val callback = object : StreamCallback {
                override fun onToken(token: String) {
                    // No per-token logging here: this is the hottest callback in
                    // the app and even Log.v pays string-building + a logd write.
                    onToken(token)
                }

                override fun onComplete() {
                    Log.d(TAG, "Complete callback received, resetting isGenerating flag")
                    if (generationId.get() == myGenerationId) isGenerating.set(false)
                    onComplete()
                }
            }

            try {
                Log.d(TAG, "Calling nativeGenerateStream...")
                nativeGenerateStream(
                    prompt,
                    maxTokens,
                    temperature,
                    topP,
                    topK,
                    repeatPenalty,
                    repeatLastN,
                    minP,
                    tfsZ,
                    typicalP,
                    mirostat,
                    mirostatTau,
                    mirostatEta,
                    callback
                )
                Log.d(TAG, "nativeGenerateStream returned")
            } catch (e: Throwable) {
                Log.e(TAG, "Native generation threw exception", e)
                AppEventLogger.error(
                    component = TAG,
                    action = "generate_stream_native_exception",
                    details = "model=${loadedModelName ?: "unknown"}",
                    throwable = e
                )
                throw e
            }

            Log.d(TAG, "=== GENERATE STREAM END ===")
        } catch (e: Throwable) {
            // Throwable: a JNI Error escaping here would leave isGenerating
            // armed forever ("Generation already in progress" until restart).
            Log.e(TAG, "Streaming generation error", e)
            AppEventLogger.error(
                component = TAG,
                action = "generate_stream_failed",
                details = "model=${loadedModelName ?: "unknown"}",
                throwable = e
            )
            if (armed && generationId.get() == myGenerationId) isGenerating.set(false)
            throw e
        }
      }
    }
    
    fun stopGeneration() {
        Log.d(TAG, "stopGeneration called, resetting flag")
        if (isGenerating.get() && isModelLoaded) {
            try {
                nativeStopGeneration()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping native generation", e)
            }
        }
        // Always reset the flag regardless of previous state
        isGenerating.set(false)
    }
    
    fun getModelInfo(): String {
        return if (isModelLoaded) {
            nativeGetModelInfo()
        } else {
            "No model loaded"
        }
    }

    fun getActiveBackendLabel(): String {
        if (!isModelLoaded && !isVisionModelLoaded) {
            return "Unknown"
        }

        return if (lastLoadedGpuLayers > 0) {
            if (nativeIsVulkanInitialized()) {
                "GPU (Vulkan)"
            } else {
                "CPU (GPU requested, Vulkan unavailable)"
            }
        } else {
            "CPU"
        }
    }
    
    fun isLoaded(): Boolean = isModelLoaded
    
    fun isVisionLoaded(): Boolean = isVisionModelLoaded
    
    fun getLoadedModelName(): String? = loadedModelName
    
    fun isVisionSupported(): Boolean {
        val nativeSupported = if (isModelLoaded) {
            nativeIsVisionSupported()
        } else {
            false
        }
        Log.d(TAG, "isVisionSupported check: modelLoaded=$isModelLoaded, visionModelLoaded=$isVisionModelLoaded, nativeSupport=$nativeSupported")
        return nativeSupported || isVisionModelLoaded
    }

    /** llama.cpp honors the full sampling surface. */
    val samplingCapabilities: Set<SamplingParam> = SamplingCapabilities.FULL

    /**
     * Phase 1: runtime capabilities derived from native introspection. The
     * repository layer intersects this with the manifest's declared set.
     */
    fun getRuntimeCapabilities(): Set<ModelCapability> {
        if (!isModelLoaded && !isVisionModelLoaded) return emptySet()
        val caps = mutableSetOf<ModelCapability>()
        if (isVisionSupported()) caps += ModelCapability.VISION
        if (isModelLoaded) {
            val template = getChatTemplate()
            if (template != null && chatTemplateSupportsThinking(template)) caps += ModelCapability.REASONING
            if (template != null && chatTemplateSupportsTools(template)) caps += ModelCapability.AGENT_SKILLS
        }
        return caps
    }

    private fun chatTemplateSupportsThinking(template: String): Boolean =
        template.contains("<think>", ignoreCase = true) ||
            template.contains("thinking", ignoreCase = true)

    private fun chatTemplateSupportsTools(template: String): Boolean =
        template.contains("tool_call", ignoreCase = true) ||
            template.contains("function_call", ignoreCase = true) ||
            template.contains("<tool_response>", ignoreCase = true)

    /**
     * Phase 1 (Subphase D): clear conversation-scoped native state — cached
     * prompt-token sequence + KV cache — without unloading the model.
     */
    fun resetConversation() {
        if (!isModelLoaded && !isVisionModelLoaded) return
        try {
            nativeResetConversation()
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "nativeResetConversation not available", e)
        }
    }
    
    fun cleanup() {
        unloadModel()
        unloadVisionModel()
        try {
            nativeCleanup()
        } catch (e: Throwable) {
            Log.e(TAG, "Error during native cleanup", e)
        }
    }
    
    // Vision model functions
    
    suspend fun loadVisionModel(
        modelPath: String,
        mmprojPath: String,
        nThreads: Int = 4,
        nGpuLayers: Int = 0,
        contextSize: Int = 2048
    ): Result<Unit> = withContext(Dispatchers.IO) {
      InferenceThreadDiscipline.runWithInferencePriority {
        try {
            if (!nativeLibraryLoaded) {
                return@runWithInferencePriority Result.failure(
                    Exception("QuantLM native library is unavailable for this device's CPU architecture (ABI).")
                )
            }

            val modelFile = File(modelPath)
            val mmprojFile = File(mmprojPath)

            if (!modelFile.exists()) {
                return@runWithInferencePriority Result.failure(Exception("Model file not found: $modelPath"))
            }
            if (!mmprojFile.exists()) {
                return@runWithInferencePriority Result.failure(Exception("Vision projector file not found: $mmprojPath"))
            }

            if (isVisionModelLoaded) {
                unloadVisionModel()
            }
            if (isModelLoaded) {
                unloadModel()
            }

            val effectiveThreads = InferenceThreadDiscipline.computeInferenceThreadCount(nThreads)
            val success = nativeLoadVisionModel(modelPath, mmprojPath, effectiveThreads, nGpuLayers, contextSize)

            if (success) {
                isVisionModelLoaded = true
                isModelLoaded = true
                lastLoadedGpuLayers = nGpuLayers
                // Record the context so history budgeting sees the real window
                // instead of the 0 that previously broke vision-model chats.
                loadedContextSize = contextSize
                // Extract model name from file path (without extension)
                loadedModelName = modelFile.nameWithoutExtension
                Log.i(TAG, "Vision model loaded successfully: $modelPath")
                AppEventLogger.info(
                    component = TAG,
                    action = "vision_model_loaded",
                    details = "name=${loadedModelName ?: modelFile.name}, mmproj=${mmprojFile.name}, context=$contextSize, threads=$effectiveThreads (requested=$nThreads)"
                )
                Result.success(Unit)
            } else {
                val visionErrorCode = nativeGetLastInferenceErrorCode()
                // mmproj mismatch (code 3): the base model may itself be fine.
                // Fall back to a text-only load so a bad or mismatched
                // projector doesn't block the user from using the model at all.
                if (visionErrorCode == InferenceError.NATIVE_CODE_VISION_MTMD_FAILED &&
                    nativeLoadModel(modelPath, effectiveThreads, nGpuLayers, contextSize, false, false)
                ) {
                    isModelLoaded = true
                    isVisionModelLoaded = false
                    lastLoadedGpuLayers = nGpuLayers
                    loadedContextSize = contextSize
                    loadedModelName = modelFile.nameWithoutExtension
                    Log.w(TAG, "Vision projector failed to load; loaded model in text-only mode")
                    AppEventLogger.warn(
                        component = TAG,
                        action = "vision_model_text_only_fallback",
                        details = "path=$modelPath, mmproj=${mmprojFile.name}"
                    )
                    Result.success(Unit)
                } else {
                    val failure = InferenceError.fromNativeCode(
                        visionErrorCode,
                        "nativeLoadVisionModel returned false"
                    )
                    AppEventLogger.error(
                        component = TAG,
                        action = "vision_model_load_failed",
                        details = "path=$modelPath, mmproj=$mmprojPath, reason=${failure.message ?: "unknown"}",
                        throwable = failure
                    )
                    Result.failure(failure)
                }
            }
        } catch (e: Throwable) {
            // Throwable: native vision load can throw UnsatisfiedLinkError or
            // OutOfMemoryError (Errors). Catch them so the load fails cleanly.
            Log.e(TAG, "Error loading vision model", e)
            AppEventLogger.error(
                component = TAG,
                action = "vision_model_load_exception",
                details = "path=$modelPath, mmproj=$mmprojPath, type=${e.javaClass.simpleName}",
                throwable = e
            )
            isVisionModelLoaded = false
            isModelLoaded = false
            Result.failure(e)
        }
      }
    }
    
    fun unloadVisionModel() {
        if (isVisionModelLoaded) {
            try {
                nativeUnloadVisionModel()
            } catch (e: Throwable) {
                Log.e(TAG, "Error during native vision model unload", e)
            }
            isVisionModelLoaded = false
            lastLoadedGpuLayers = 0
            loadedModelName = null
            Log.i(TAG, "Vision model unloaded")
            AppEventLogger.info(component = TAG, action = "vision_model_unloaded")
        }
    }
    
    suspend fun generateWithImage(
        prompt: String,
        imagePath: String,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        topK: Int = 40,
        repeatPenalty: Float = 1.1f,
        repeatLastN: Int = 64,
        minP: Float = 0.05f,
        tfsZ: Float = 1.0f,
        typicalP: Float = 1.0f,
        mirostat: Int = 0,
        mirostatTau: Float = 5.0f,
        mirostatEta: Float = 0.1f
    ): Result<String> = withContext(Dispatchers.IO) {
      InferenceThreadDiscipline.runWithInferencePriority {
        try {
            if (!isVisionModelLoaded) {
                return@runWithInferencePriority Result.failure(Exception("No vision model loaded"))
            }

            val imageFile = File(imagePath)
            if (!imageFile.exists()) {
                return@runWithInferencePriority Result.failure(Exception("Image file not found: $imagePath"))
            }

            isGenerating.set(true)
            val response = nativeGenerateWithImage(
                prompt,
                imagePath,
                maxTokens,
                temperature,
                topP,
                topK,
                repeatPenalty,
                repeatLastN,
                minP,
                tfsZ,
                typicalP,
                mirostat,
                mirostatTau,
                mirostatEta
            )
            isGenerating.set(false)

            Result.success(response)
        } catch (e: Throwable) {
            isGenerating.set(false)
            Log.e(TAG, "Vision generation error", e)
            Result.failure(e)
        }
      }
    }
    
    suspend fun generateStreamWithImage(
        prompt: String,
        imagePath: String,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        topK: Int = 40,
        repeatPenalty: Float = 1.1f,
        repeatLastN: Int = 64,
        minP: Float = 0.05f,
        tfsZ: Float = 1.0f,
        typicalP: Float = 1.0f,
        mirostat: Int = 0,
        mirostatTau: Float = 5.0f,
        mirostatEta: Float = 0.1f,
        onToken: (String) -> Unit,
        onComplete: () -> Unit
    ) = withContext(Dispatchers.IO) {
      InferenceThreadDiscipline.runWithInferencePriority {
        var armed = false
        var myGenerationId = 0L
        try {
            Log.d(TAG, "=== GENERATE STREAM WITH IMAGE START ===")
            Log.d(TAG, "isVisionModelLoaded: $isVisionModelLoaded")
            Log.d(TAG, "imagePath: $imagePath")

            if (!isVisionModelLoaded) {
                Log.e(TAG, "BLOCKED: No vision model loaded")
                throw Exception("No vision model loaded")
            }

            val imageFile = File(imagePath)
            if (!imageFile.exists()) {
                throw Exception("Image file not found: $imagePath")
            }

            if (!isGenerating.compareAndSet(false, true)) {
                Log.e(TAG, "BLOCKED: generation already in progress")
                throw Exception("Generation already in progress")
            }
            armed = true
            myGenerationId = generationId.incrementAndGet()

            val callback = object : StreamCallback {
                override fun onToken(token: String) {
                    // No per-token logging here: this is the hottest callback in
                    // the app and even Log.v pays string-building + a logd write.
                    onToken(token)
                }

                override fun onComplete() {
                    Log.d(TAG, "Complete callback received, resetting isGenerating flag")
                    if (generationId.get() == myGenerationId) isGenerating.set(false)
                    onComplete()
                }
            }

            try {
                nativeGenerateStreamWithImage(
                    prompt,
                    imagePath,
                    maxTokens,
                    temperature,
                    topP,
                    topK,
                    repeatPenalty,
                    repeatLastN,
                    minP,
                    tfsZ,
                    typicalP,
                    mirostat,
                    mirostatTau,
                    mirostatEta,
                    callback
                )
            } catch (e: Throwable) {
                Log.e(TAG, "Native vision generation threw exception", e)
                throw e
            }

            Log.d(TAG, "=== GENERATE STREAM WITH IMAGE END ===")
        } catch (e: Throwable) {
            Log.e(TAG, "Streaming vision generation error", e)
            if (armed && generationId.get() == myGenerationId) isGenerating.set(false)
            throw e
        }
      }
    }
    
    @Keep
    interface StreamCallback {
        @Keep
        fun onToken(token: String)

        @Keep
        fun onComplete()

        @Keep
        fun onError(message: String) {} // Default empty implementation for backward compatibility
    }
}
