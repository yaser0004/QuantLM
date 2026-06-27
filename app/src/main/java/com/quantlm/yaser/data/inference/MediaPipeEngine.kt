package com.quantlm.yaser.data.inference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.quantlm.yaser.data.diagnostics.AppEventLogger
import com.quantlm.yaser.data.inference.LlamaEngine.StreamCallback
import com.quantlm.yaser.data.local.GenerationPreferences.HardwareAccelerationMode
import com.quantlm.yaser.domain.inference.GenerationParams
import com.quantlm.yaser.domain.inference.InferenceConfig
import com.quantlm.yaser.domain.inference.InferenceEngine
import com.quantlm.yaser.domain.inference.LoadOptions
import com.quantlm.yaser.domain.inference.SamplingCapabilities
import com.quantlm.yaser.domain.inference.SamplingParam
import com.quantlm.yaser.domain.model.ModelCapability
import com.quantlm.yaser.domain.model.ModelFormat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.LlmInferenceSessionOptions
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaPipe LLM Inference engine for running .task format models.
 * 
 * This engine uses Google's MediaPipe GenAI library to run LLM inference
 * on-device. It supports:
 * - Text generation with streaming
 * - Multi-turn conversation sessions
 * 
 * Supported models from HuggingFace LiteRT Community:
 * - litert-community/Gemma3-1B-IT
 * - litert-community/Qwen2.5-1.5B-Instruct
 * - litert-community/Phi-4-mini-instruct
 * 
 * @see <a href="https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android">MediaPipe LLM Inference</a>
 */
@Singleton
class MediaPipeEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : InferenceEngine {
    
    companion object {
        private const val TAG = "MediaPipeEngine"
        private const val DEFAULT_MAX_TOKENS = 512
        private const val MIN_FALLBACK_TOKENS = 128
        private const val DEFAULT_TOP_K = 40

        /** Longest edge an attached image is downsampled to before encoding. */
        private const val MAX_IMAGE_DIMENSION = 1024

        /** Max images a vision session may hold across a conversation. */
        private const val MAX_IMAGES = 8

        /**
         * Safety interlock for [supportsAudioInput]. Audio must not be
         * advertised until generateWithAudio() actually calls a real SDK audio
         * API — otherwise the recording is silently dropped. Flip to true in
         * the same change that wires the audio call path.
         */
        private const val AUDIO_CALL_PATH_WIRED = false
    }
    
    override val supportedFormat: ModelFormat = ModelFormat.TFLITE

    // The MediaPipe SDK honors only temperature / topK / topP.
    override val samplingCapabilities: Set<SamplingParam> = SamplingCapabilities.BASIC
    
    private var llmInference: LlmInference? = null
    private var currentSession: LlmInferenceSession? = null
    private var modelPath: String? = null
    @Volatile
    private var loadedContextSize: Int = 0

    fun getLoadedContextSize(): Int = loadedContextSize

    
    @Volatile
    private var _isModelLoaded = false
    override val isModelLoaded: Boolean
        get() = _isModelLoaded
    
    @Volatile
    private var _isGenerating = false
    override val isGenerating: Boolean
        get() = _isGenerating
    
    private val shouldStop = AtomicBoolean(false)

    /**
     * Serializes the session-touching generation calls ([generate],
     * [generateStream], [generateWithImage]). A MediaPipe [LlmInferenceSession]
     * permits only one in-flight invocation at a time; without this lock a new
     * turn — even one started in a freshly created conversation — could call
     * addQueryChunk()/generateResponseAsync() before the previous invocation's
     * future had resolved, which the SDK rejects with "Previous invocation
     * still processing". The lock is held until generateResponse()/future.get()
     * returns, i.e. until the persistent session is genuinely idle again.
     */
    private val generationMutex = Mutex()
    
    // True when a vision-capable model is loaded and image input is wired
    // (see [generateWithImage] / [visionEnabled]).
    @Volatile
    private var _supportsVision = false
    override val supportsVision: Boolean
        get() = _supportsVision
    
    private var modelName: String? = null

    /**
     * Phase 1 (Subphase F): persistent-session sampling options.
     *
     * MediaPipe's [LlmInferenceSession] owns the KV cache for a conversation.
     * We keep the session alive across [generate]/[generateStream] calls and
     * only rebuild it when sampling options change or [resetConversation] is
     * called — that's what lets multi-turn coherence work without re-prefilling
     * every turn.
     */
    private data class SessionParams(
        val temperature: Float,
        val topK: Int,
        val topP: Float,
    )
    @Volatile
    private var currentSessionParams: SessionParams? = null

    /**
     * The accelerator the caller asked for at load time. Wired into
     * [LlmInference.LlmInferenceOptions.Builder.setPreferredBackend] in
     * [tryLoadWithMaxTokens] so the MediaPipe runtime actually honors the
     * GPU/CPU selection, and surfaced by [getActiveBackendLabel].
     */
    @Volatile
    private var loadedAccelerator: HardwareAccelerationMode? = null

    /**
     * Set when a GPU load exhausted the token ladder and the model was
     * recovered on the CPU backend (see [loadModel]). Surfaced by
     * [getActiveBackendLabel] so the UI distinguishes an automatic fallback
     * from a user-chosen CPU load.
     */
    @Volatile
    private var gpuFellBackToCpu = false

    /**
     * Whether the loaded model is vision-capable and the engine/session were
     * configured for image input. Decided from the filename at load time —
     * before the token ladder runs — so it can gate the [LlmInference] and
     * [LlmInferenceSession] options.
     */
    @Volatile
    private var visionEnabled = false
    
    /**
     * Detect expected context size from model filename.
     * Many LiteRT models encode context size as "ekv4096", "ekv2048", etc.
     */
    private fun detectContextSizeFromFilename(filename: String): Int? {
        val match = Regex("ekv(\\d+)").find(filename.lowercase())
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * MediaPipe exceptions often include large internal traces. Keep user-visible
     * messages concise and actionable.
     */
    private fun sanitizeLoadError(throwable: Throwable): String {
        val raw = throwable.message ?: throwable.javaClass.simpleName
        val trimmed = raw.substringBefore("=== Source Location Trace").trim()

        return when {
            throwable is OutOfMemoryError -> {
                "Not enough memory to load this model. Try a smaller model or close background apps."
            }
            throwable is UnsatisfiedLinkError -> {
                "MediaPipe native runtime could not be loaded on this device."
            }
            trimmed.contains("Unrecognized model path", ignoreCase = true) -> {
                "Model file is invalid or incomplete. Please re-download the model."
            }
            trimmed.contains("permission denied", ignoreCase = true) -> {
                "Model file cannot be read due to storage permission restrictions."
            }
            trimmed.contains("No such file", ignoreCase = true) ||
                trimmed.contains("not found", ignoreCase = true) -> {
                "Model file was not found on disk. Please re-download the model."
            }
            trimmed.contains("token", ignoreCase = true) &&
                trimmed.contains("max", ignoreCase = true) -> {
                "Model could not be initialized with current context length; lower context settings and retry."
            }
            trimmed.isBlank() -> throwable.javaClass.simpleName
            else -> trimmed
        }
    }

    private fun summarizeThrowable(throwable: Throwable?): String {
        if (throwable == null) return "Unknown error"
        return generateSequence(throwable) { it.cause }
            .take(4)
            .joinToString(" -> ") { cause ->
                val msg = (cause.message ?: "")
                    .substringBefore("=== Source Location Trace")
                    .trim()
                if (msg.isBlank()) {
                    cause.javaClass.simpleName
                } else {
                    "${cause.javaClass.simpleName}: $msg"
                }
            }
    }

    private fun buildMaxTokenCandidates(initialMaxTokens: Int): List<Int> {
        val normalizedInitial = initialMaxTokens.coerceAtLeast(MIN_FALLBACK_TOKENS)
        // Keep at most 3 candidates: requested → half → minimum.
        // The original 10-entry ladder could add 8–16 s of failed-allocation overhead
        // on constrained devices; fast-failing to MIN_FALLBACK_TOKENS is always safe.
        return listOf(
            normalizedInitial,
            (normalizedInitial / 2).coerceAtLeast(MIN_FALLBACK_TOKENS),
            MIN_FALLBACK_TOKENS
        ).distinct()
    }
    
    override suspend fun loadModel(
        modelPath: String,
        config: InferenceConfig
    ): Result<Unit> = withContext(Dispatchers.IO + InferenceThreadDiscipline.inferencePriority()) {
        try {
            // Capture the requested accelerator before unloadModel() clears it,
            // then restore it so tryLoadWithMaxTokens can steer the backend.
            // Null here (a bare 2-param call) preserves the device-default path.
            val requestedAccelerator = loadedAccelerator
            // Unload any existing model
            unloadModel()
            loadedAccelerator = requestedAccelerator
            
            val modelFile = File(modelPath)
            if (!modelFile.exists()) {
                Log.e(TAG, "Model file not found: $modelPath")
                return@withContext Result.failure(
                    IllegalArgumentException("Model file not found: $modelPath")
                )
            }
            
            // Decided here — before the token ladder — so tryLoadWithMaxTokens
            // and ensureSession can configure image input.
            visionEnabled = isVisionCapableModel(modelFile.name) && supportsVisionInput()

            Log.i(TAG, "Loading MediaPipe LLM model: $modelPath")
            Log.i(TAG, "File size: ${modelFile.length()} bytes (${modelFile.length() / (1024*1024)} MB)")
            Log.i(TAG, "File extension: ${modelFile.extension}")
            AppEventLogger.info(
                component = TAG,
                action = "model_load_start",
                details = "path=$modelPath, bytes=${modelFile.length()}, extension=${modelFile.extension}"
            )
            
            // Determine max tokens: use filename hint > config > default
            val filenameContextSize = detectContextSizeFromFilename(modelFile.name)
            val maxTokens = filenameContextSize
                ?: if (config.contextSize > 0) config.contextSize else DEFAULT_MAX_TOKENS
            
            Log.i(TAG, "Using maxTokens=$maxTokens (filename hint: $filenameContextSize, config: ${config.contextSize})")
            val tokenCandidates = buildMaxTokenCandidates(maxTokens)
            Log.i(TAG, "Token fallback sequence for ${modelFile.name}: ${tokenCandidates.joinToString()}")

            var loadResult: Result<Unit> = Result.failure(IllegalStateException("Model load was not attempted"))
            var lastFailure: Throwable? = null
            for ((index, candidate) in tokenCandidates.withIndex()) {
                Log.i(
                    TAG,
                    "Load attempt ${index + 1}/${tokenCandidates.size}: modelPath=$modelPath, maxTokens=$candidate"
                )
                AppEventLogger.debug(
                    component = TAG,
                    action = "model_load_attempt",
                    details = "attempt=${index + 1}/${tokenCandidates.size}, maxTokens=$candidate"
                )
                loadResult = tryLoadWithMaxTokens(modelPath, candidate)
                if (loadResult.isSuccess) {
                    break
                }
                lastFailure = loadResult.exceptionOrNull()
            }
            
            // GPU→CPU fallback: if every GPU token-ladder attempt failed,
            // retry once on CPU at the smallest token count. A single attempt
            // (not the whole ladder again) keeps a failed load from doubling
            // in duration.
            if (loadResult.isFailure && loadedAccelerator == HardwareAccelerationMode.GPU) {
                Log.w(TAG, "All GPU load attempts failed; retrying once on CPU backend")
                AppEventLogger.warn(
                    component = TAG,
                    action = "model_load_gpu_fallback",
                    details = "path=$modelPath, fallbackTokens=$MIN_FALLBACK_TOKENS"
                )
                loadedAccelerator = HardwareAccelerationMode.CPU
                gpuFellBackToCpu = true
                loadResult = tryLoadWithMaxTokens(modelPath, MIN_FALLBACK_TOKENS)
                if (loadResult.isFailure) {
                    lastFailure = loadResult.exceptionOrNull()
                }
            }

            if (loadResult.isFailure) {
                val detailed = summarizeThrowable(lastFailure)
                Log.e(
                    TAG,
                    "All load attempts failed for modelPath=$modelPath. Root cause chain: $detailed"
                )
                AppEventLogger.error(
                    component = TAG,
                    action = "model_load_failed_all_attempts",
                    details = "path=$modelPath, attempts=${tokenCandidates.size}, rootCause=$detailed",
                    throwable = lastFailure
                )
                return@withContext Result.failure(
                    IllegalStateException(
                        "Failed to initialize model after ${tokenCandidates.size} attempts. $detailed",
                        lastFailure
                    )
                )
            }
            
            this@MediaPipeEngine.modelPath = modelPath
            this@MediaPipeEngine.modelName = modelFile.nameWithoutExtension
            _isModelLoaded = true
            
            // visionEnabled was decided before the token ladder ran; the
            // engine and sessions are already configured to match.
            _supportsVision = visionEnabled
            
            Log.i(TAG, "MediaPipe LLM model loaded successfully: ${modelFile.name}")
            Log.i(TAG, "Vision support: $_supportsVision")
            AppEventLogger.info(
                component = TAG,
                action = "model_load_success",
                details = "name=${modelFile.name}, vision=$_supportsVision"
            )
            
            Result.success(Unit)
        } catch (t: Throwable) {
            val userMessage = sanitizeLoadError(t)
            Log.e(
                TAG,
                "Failed to load MediaPipe LLM model: path=$modelPath, error=${t.javaClass.simpleName}: $userMessage, causeChain=${summarizeThrowable(t)}",
                t
            )
            AppEventLogger.error(
                component = TAG,
                action = "model_load_exception",
                details = "path=$modelPath, reason=$userMessage",
                throwable = t
            )
            _isModelLoaded = false
            llmInference = null
            Result.failure(IllegalStateException(userMessage, t))
        }
    }

    /**
     * Attempt to load a model with a specific maxTokens value.
     */
    private fun tryLoadWithMaxTokens(modelPath: String, maxTokens: Int): Result<Unit> {
        return try {
            val backend = when (loadedAccelerator) {
                HardwareAccelerationMode.GPU -> LlmInference.Backend.GPU
                HardwareAccelerationMode.CPU -> LlmInference.Backend.CPU
                null -> LlmInference.Backend.DEFAULT
            }
            Log.w(TAG, "GPUDIAG MediaPipe.tryLoad: loadedAccelerator=$loadedAccelerator, backend=$backend")
            Log.i(TAG, "Attempting load with maxTokens=$maxTokens, backend=$backend, modelPath=$modelPath")
            val optionsBuilder = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(maxTokens)
                .setPreferredBackend(backend)
            if (visionEnabled) {
                optionsBuilder.setMaxNumImages(MAX_IMAGES)
            }
            val options = optionsBuilder.build()
            
            llmInference = LlmInference.createFromOptions(context, options)
            loadedContextSize = maxTokens
            Log.i(TAG, "Successfully loaded with maxTokens=$maxTokens")
            AppEventLogger.debug(
                component = TAG,
                action = "model_load_attempt_success",
                details = "maxTokens=$maxTokens"
            )
            Result.success(Unit)
        } catch (t: Throwable) {
            val userMessage = sanitizeLoadError(t)
            Log.e(
                TAG,
                "Failed to load with maxTokens=$maxTokens for modelPath=$modelPath: ${t.javaClass.simpleName}: $userMessage; causeChain=${summarizeThrowable(t)}"
            )
            AppEventLogger.warn(
                component = TAG,
                action = "model_load_attempt_failed",
                details = "maxTokens=$maxTokens, reason=$userMessage"
            )
            llmInference = null
            Result.failure(IllegalStateException(userMessage, t))
        }
    }
    
    override suspend fun loadVisionModel(
        modelPath: String,
        mmprojPath: String,
        config: InferenceConfig
    ): Result<Unit> {
        // MediaPipe `.task` vision models are single-file (no separate
        // projector). loadModel() detects vision capability from the filename
        // and configures the engine and sessions for image input.
        return loadModel(modelPath, config)
    }

    /**
     * Accelerator-aware vision load. Records the requested accelerator so the
     * GPU/CPU choice from Settings is honored for `.task` vision models, then
     * delegates to the 3-param [loadVisionModel]. Mirrors the 3-param
     * [loadModel] override; not part of [InferenceEngine] — the repository
     * holds a concrete [MediaPipeEngine] reference.
     */
    suspend fun loadVisionModel(
        modelPath: String,
        mmprojPath: String,
        config: InferenceConfig,
        options: LoadOptions,
    ): Result<Unit> {
        loadedAccelerator = options.accelerationMode
        return loadVisionModel(modelPath, mmprojPath, config)
    }
    
    /**
     * Check if the model is capable of vision input based on its name.
     * Gemma 3n models support vision natively.
     */
    private fun isVisionCapableModel(modelName: String): Boolean {
        val lowerName = modelName.lowercase()
        return lowerName.contains("gemma-3n") || 
               lowerName.contains("gemma3n") ||
               lowerName.contains("e2b") ||
               lowerName.contains("e4b")
    }
    
    override fun unloadModel() {
        try {
            try {
                currentSession?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing session during unload", e)
            }
            currentSession = null
            currentSessionParams = null
            try {
                llmInference?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing LLM inference during unload", e)
            }
            llmInference = null
            modelPath = null
            modelName = null
            loadedAccelerator = null
            gpuFellBackToCpu = false
            visionEnabled = false
            loadedContextSize = 0
            _isModelLoaded = false
            _supportsVision = false
            Log.d(TAG, "MediaPipe LLM model unloaded")
            AppEventLogger.info(component = TAG, action = "model_unloaded")
        } catch (e: Exception) {
            Log.e(TAG, "Error unloading MediaPipe LLM model", e)
            AppEventLogger.error(component = TAG, action = "model_unload_failed", throwable = e)
        }
    }

    /**
     * Accelerator-aware load. Records the requested accelerator so
     * [tryLoadWithMaxTokens] can pass it to MediaPipe via
     * [LlmInference.LlmInferenceOptions.Builder.setPreferredBackend].
     */
    override suspend fun loadModel(
        modelPath: String,
        config: InferenceConfig,
        options: LoadOptions,
    ): Result<Unit> {
        loadedAccelerator = options.accelerationMode
        return loadModel(modelPath, config)
    }

    /**
     * Honest active-backend label for the loaded model. Used by the repository
     * layer to surface the actual runtime selection in diagnostics.
     */
    fun getActiveBackendLabel(): String {
        if (!_isModelLoaded) return "Unknown"
        return when (loadedAccelerator) {
            HardwareAccelerationMode.CPU ->
                if (gpuFellBackToCpu) "MediaPipe (CPU - GPU unavailable)" else "MediaPipe (CPU)"
            HardwareAccelerationMode.GPU -> "MediaPipe (GPU)"
            null -> "MediaPipe (device default)"
        }
    }

    /**
     * Phase 1 (Subphase F): ensure a session exists with sampling options
     * matching [params]; rebuild if they don't. Sessions are reused across
     * turns so the KV cache survives — this is what makes multi-turn chat
     * coherent without re-prefilling history each call.
     */
    private fun ensureSession(params: GenerationParams): LlmInferenceSession? {
        val inference = llmInference ?: return null
        if (params.topK > 64) {
            Log.i(TAG, "topK=${params.topK} exceeds the MediaPipe SDK maximum; clamped to 64")
        }
        val effective = SessionParams(
            temperature = params.temperature,
            topK = params.topK.coerceAtMost(64),
            topP = params.topP,
        )
        val existing = currentSession
        if (existing != null && currentSessionParams == effective) {
            return existing
        }
        if (existing != null) {
            try {
                existing.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing stale session before rebuild", e)
            }
        }
        return try {
            val sessionBuilder = LlmInferenceSessionOptions.builder()
                .setTemperature(effective.temperature)
                .setTopK(effective.topK)
                .setTopP(effective.topP)
            if (visionEnabled) {
                sessionBuilder.setGraphOptions(
                    GraphOptions.builder().setEnableVisionModality(true).build()
                )
            }
            val sessionOptions = sessionBuilder.build()
            val fresh = LlmInferenceSession.createFromOptions(inference, sessionOptions)
            currentSession = fresh
            currentSessionParams = effective
            AppEventLogger.debug(
                component = TAG,
                action = "session_rebuilt",
                details = "temp=${effective.temperature}, topK=${effective.topK}, topP=${effective.topP}"
            )
            fresh
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create inference session", e)
            currentSession = null
            currentSessionParams = null
            null
        }
    }
    
    override suspend fun generate(
        prompt: String,
        params: GenerationParams
    ): String = withContext(Dispatchers.IO + InferenceThreadDiscipline.inferencePriority()) {
        if (llmInference == null) throw IllegalStateException("No model loaded")

        // Wait for any prior invocation to fully finish before touching the
        // shared session; released in the finally below.
        generationMutex.lock()
        _isGenerating = true
        shouldStop.set(false)

        try {
            Log.d(TAG, "Generating response for prompt (${prompt.length} chars)")
            AppEventLogger.info(
                component = TAG,
                action = "generate_start",
                details = "promptChars=${prompt.length}, maxTokens=${params.maxTokens}, temperature=${params.temperature}"
            )

            // Phase 1 (Subphase F): reuse the persistent session so KV carries
            // across turns. The session is rebuilt only on sampling-options
            // change or resetConversation()/unloadModel().
            val session = ensureSession(params)
                ?: throw IllegalStateException("Failed to acquire MediaPipe session")
            session.addQueryChunk(prompt)
            val response = session.generateResponse()
            Log.d(TAG, "Generated response: ${response.take(100)}...")
            AppEventLogger.info(
                component = TAG,
                action = "generate_complete",
                details = "responseChars=${response.length}"
            )
            response
        } finally {
            _isGenerating = false
            generationMutex.unlock()
        }
    }

    override suspend fun generateStream(
        prompt: String,
        callback: StreamCallback,
        params: GenerationParams
    ): Boolean = withContext(Dispatchers.IO + InferenceThreadDiscipline.inferencePriority()) {
        if (llmInference == null) {
            callback.onError("No model loaded")
            return@withContext false
        }

        // Wait for any prior invocation to fully finish before touching the
        // shared session; released in the finally below.
        generationMutex.lock()
        _isGenerating = true
        shouldStop.set(false)

        try {
            Log.d(TAG, "Starting streaming generation for prompt (${prompt.length} chars)")
            AppEventLogger.info(
                component = TAG,
                action = "generate_stream_start",
                details = "promptChars=${prompt.length}, maxTokens=${params.maxTokens}, temperature=${params.temperature}"
            )

            // Phase 1 (Subphase F): reuse the persistent session so MediaPipe's
            // internal KV state carries across turns. Session is rebuilt only
            // on sampling-options change or resetConversation().
            val session = ensureSession(params)
            if (session == null) {
                callback.onError("Failed to acquire MediaPipe session")
                return@withContext false
            }

            try {
                session.addQueryChunk(prompt)

                // Track whether the listener already called onComplete
                val completedViaListener = AtomicBoolean(false)

                val progressListener = ProgressListener<String> { partialResult, done ->
                    if (!shouldStop.get()) {
                        callback.onToken(partialResult)
                    }
                    // Deliver completion even when stopped: suppressing it left
                    // the consumer's callbackFlow open forever (leaked collector,
                    // wake lock held until its timeout, stuck foreground service).
                    if (done && completedViaListener.compareAndSet(false, true)) {
                        callback.onComplete()
                    }
                }

                val future = session.generateResponseAsync(progressListener)

                // future.get() can throw even after the listener completes;
                // tolerate the race so we don't double-report failure. A
                // cancelled turn (stopGeneration → cancelGenerateResponseAsync)
                // may surface here as an exception OR resolve without ever
                // delivering done.
                try {
                    future.get()
                } catch (e: Exception) {
                    when {
                        completedViaListener.get() ->
                            Log.w(TAG, "future.get() threw after listener completed: ${e.message}")
                        shouldStop.get() ->
                            Log.i(TAG, "Generation cancelled: ${e.message}")
                        else -> throw e
                    }
                }

                // Whatever the cancel path did (done delivered, swallowed
                // above, or resolved without done), the consumer's stream
                // must terminate exactly once.
                if (completedViaListener.compareAndSet(false, true)) {
                    callback.onComplete()
                }

                !shouldStop.get()
            } finally {
                AppEventLogger.info(
                    component = TAG,
                    action = "generate_stream_end",
                    details = "stopped=${shouldStop.get()}"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Streaming generation error", e)
            // Session may be in a broken state — drop it so the next call rebuilds.
            try {
                currentSession?.close()
            } catch (closeErr: Exception) {
                Log.w(TAG, "Error closing session after stream failure", closeErr)
            }
            currentSession = null
            currentSessionParams = null
            callback.onError(e.message ?: "Unknown error")
            AppEventLogger.error(
                component = TAG,
                action = "generate_stream_failed",
                details = "reason=${e.message ?: "unknown"}",
                throwable = e
            )
            false
        } finally {
            _isGenerating = false
            generationMutex.unlock()
        }
    }

    override suspend fun generateWithImage(
        prompt: String,
        imagePath: String,
        callback: StreamCallback,
        params: GenerationParams
    ): Boolean = withContext(Dispatchers.IO + InferenceThreadDiscipline.inferencePriority()) {
        if (!_supportsVision) {
            callback.onError("Vision is not supported for this model")
            return@withContext false
        }
        if (llmInference == null) {
            callback.onError("No model loaded")
            return@withContext false
        }
        if (!File(imagePath).exists()) {
            callback.onError("Image file not found")
            return@withContext false
        }
        val bitmap = decodeDownsampledBitmap(imagePath)
        if (bitmap == null) {
            callback.onError("Could not read the image file")
            return@withContext false
        }

        // Wait for any prior invocation to fully finish before touching the
        // shared session; released in the finally below.
        generationMutex.lock()
        _isGenerating = true
        shouldStop.set(false)
        try {
            Log.d(TAG, "Vision generation: ${prompt.length} chars, image ${bitmap.width}x${bitmap.height}")
            AppEventLogger.info(
                component = TAG,
                action = "generate_vision_start",
                details = "promptChars=${prompt.length}, imageW=${bitmap.width}, imageH=${bitmap.height}"
            )

            val session = ensureSession(params)
            if (session == null) {
                callback.onError("Failed to acquire MediaPipe session")
                return@withContext false
            }

            try {
                session.addImage(BitmapImageBuilder(bitmap).build())
                session.addQueryChunk(prompt)

                val completedViaListener = AtomicBoolean(false)
                val progressListener = ProgressListener<String> { partialResult, done ->
                    if (!shouldStop.get()) {
                        callback.onToken(partialResult)
                    }
                    // Always complete (see generateStream) so a stopped vision
                    // turn cannot leave the consumer flow open forever.
                    if (done && completedViaListener.compareAndSet(false, true)) {
                        callback.onComplete()
                    }
                }

                val future = session.generateResponseAsync(progressListener)
                try {
                    future.get()
                } catch (e: Exception) {
                    when {
                        completedViaListener.get() ->
                            Log.w(TAG, "future.get() threw after listener completed: ${e.message}")
                        shouldStop.get() ->
                            Log.i(TAG, "Vision generation cancelled: ${e.message}")
                        else -> throw e
                    }
                }

                // See generateStream: guarantee exactly-once completion even
                // on the cancel paths.
                if (completedViaListener.compareAndSet(false, true)) {
                    callback.onComplete()
                }

                !shouldStop.get()
            } catch (e: Exception) {
                Log.e(TAG, "Vision generation error", e)
                // Session may be in a broken state — drop it so the next call rebuilds.
                try {
                    currentSession?.close()
                } catch (closeErr: Exception) {
                    Log.w(TAG, "Error closing session after vision failure", closeErr)
                }
                currentSession = null
                currentSessionParams = null
                callback.onError(e.message ?: "Unknown error")
                AppEventLogger.error(
                    component = TAG,
                    action = "generate_vision_failed",
                    details = "reason=${e.message ?: "unknown"}",
                    throwable = e
                )
                false
            }
        } finally {
            _isGenerating = false
            bitmap.recycle()
            generationMutex.unlock()
        }
    }

    /**
     * Decode an image file, downsampling so the longest edge is at most
     * [MAX_IMAGE_DIMENSION] — full-resolution photos would otherwise risk an
     * OutOfMemoryError before the model ever sees them.
     */
    private fun decodeDownsampledBitmap(imagePath: String): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(imagePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.w(TAG, "Image has no decodable bounds: $imagePath")
                return null
            }
            var sampleSize = 1
            val longestEdge = maxOf(bounds.outWidth, bounds.outHeight)
            while (longestEdge / sampleSize > MAX_IMAGE_DIMENSION) {
                sampleSize *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeFile(imagePath, opts)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode image: $imagePath", e)
            null
        }
    }
    
    override fun stopGeneration() {
        shouldStop.set(true)
        _isGenerating = false
        // tasks-genai 0.10.32 ships a real cancel. Without it the native
        // generation drained to completion in the background — holding
        // generationMutex (blocking the next turn) and burning CPU for a
        // response that was already discarded.
        try {
            currentSession?.cancelGenerateResponseAsync()
        } catch (e: Throwable) {
            Log.w(TAG, "cancelGenerateResponseAsync failed: ${e.message}")
        }
        Log.d(TAG, "Stop generation requested")
    }
    
    override fun getModelInfo(): Map<String, String> {
        val info = mutableMapOf(
            "format" to "MediaPipe LLM (.task)",
            "engine" to "MediaPipe GenAI",
            "status" to if (_isModelLoaded) "loaded" else "not loaded",
            "supportsVision" to _supportsVision.toString()
        )
        
        modelPath?.let {
            info["modelPath"] = it
            info["modelFile"] = File(it).name
        }
        
        modelName?.let {
            info["modelName"] = it
        }
        
        return info
    }
    
    /**
     * Check if this engine can handle the given model file.
     * 
     * Supported formats:
     * - .task: MediaPipe Task Bundle
     * - .litertlm: LiteRT-LM format (may require specific runtime)
     */
    override fun canHandle(filePath: String): Boolean {
        val lowerPath = filePath.lowercase()
        return lowerPath.endsWith(".task") ||
               lowerPath.endsWith(".litertlm") ||
               lowerPath.endsWith(".tflite") ||
               lowerPath.endsWith(".literlm")
    }
    
    /**
     * Get the loaded model name
     */
    fun getLoadedModelName(): String? = modelName
    
    /**
     * Check if vision is supported by the currently loaded model
     */
    fun isVisionSupported(): Boolean = _supportsVision

    /**
     * Phase 1: runtime capabilities derived from the loaded model file name.
     *
     * MediaPipe doesn't expose programmatic capability introspection, so we
     * pattern-match against the family slugs we ship in [AvailableModels].
     * The repository layer intersects this with the manifest's declared set,
     * so adding a richer family here only adds capability coverage — manifest
     * remains the source of truth.
     */
    override fun getRuntimeCapabilities(): Set<ModelCapability> {
        if (!_isModelLoaded) return emptySet()
        val name = (modelPath?.let { File(it).name } ?: modelName.orEmpty()).lowercase()
        val familySet = when {
            name.startsWith("gemma-4-") -> setOf(
                ModelCapability.VISION,
                ModelCapability.AUDIO,
                ModelCapability.REASONING,
                ModelCapability.SPECULATIVE_DECODING,
            )
            name.startsWith("gemma-3n-") -> setOf(
                ModelCapability.VISION,
                ModelCapability.AUDIO,
            )
            else -> emptySet()
        }
        // The bundled `tasks-genai` SDK does not deliver pixel or audio data to
        // the model today (generateWithImage is a text-only stub, and there is
        // no audio-input API). Strip VISION/AUDIO when the runtime cannot honor
        // them so the UI surfaces them as incomplete capabilities instead of
        // silently swallowing the user's image or recording.
        var caps = familySet
        if (!supportsVisionInput()) caps = caps - ModelCapability.VISION
        if (!supportsAudioInput()) caps = caps - ModelCapability.AUDIO
        return caps
    }

    /**
     * Whether this engine can deliver image input. The bundled
     * `tasks-genai:0.10.32` exposes the LLM vision API
     * ([LlmInferenceSession.addImage]) and [generateWithImage] is wired to it,
     * so vision is genuinely supported for vision-capable models.
     */
    fun supportsVisionInput(): Boolean = true

    /**
     * Runtime gate for audio input on the bundled MediaPipe SDK. Probes (via
     * reflection) for an audio-input method on [LlmInferenceSession]; the
     * `tasks-genai` build wired into this app today exposes none, so this
     * returns false. The [AUDIO_CALL_PATH_WIRED] interlock additionally blocks
     * a true result until generateWithAudio() is wired to that API — otherwise
     * audio would be advertised and then silently dropped (cf. the vision stub).
     */
    fun supportsAudioInput(): Boolean {
        if (!AUDIO_CALL_PATH_WIRED) return false
        return try {
            LlmInferenceSession::class.java.getMethod("addAudio", ByteArray::class.java)
            true
        } catch (e: NoSuchMethodException) {
            false
        }
    }

    /**
     * Phase 1 (Subphase F): reset conversation-scoped state. Closes the
     * persistent session so the next [generate]/[generateStream] call rebuilds
     * a fresh one with no carried-over context. The model itself stays loaded.
     */
    override suspend fun resetConversation() {
        try {
            currentSession?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing session during resetConversation", e)
        }
        currentSession = null
        currentSessionParams = null
    }
}
