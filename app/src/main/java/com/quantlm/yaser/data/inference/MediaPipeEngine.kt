package com.quantlm.yaser.data.inference

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.quantlm.yaser.data.diagnostics.AppEventLogger
import com.quantlm.yaser.data.inference.LlamaEngine.StreamCallback
import com.quantlm.yaser.domain.inference.GenerationParams
import com.quantlm.yaser.domain.inference.InferenceConfig
import com.quantlm.yaser.domain.inference.InferenceEngine
import com.quantlm.yaser.domain.model.ModelFormat
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.LlmInferenceSessionOptions
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
 * - google/gemma-3n-E2B-it-litert-lm
 * - google/gemma-3n-E4B-it-litert-lm
 * - litert-community/Qwen2.5-1.5B-Instruct
 * - litert-community/Phi-4-mini-instruct
 * - litert-community/DeepSeek-R1-Distill-Qwen-1.5B
 * 
 * @see <a href="https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android">MediaPipe LLM Inference</a>
 */
@Singleton
class MediaPipeEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : InferenceEngine {
    
    companion object {
        private const val TAG = "MediaPipeEngine"
        private const val DEFAULT_MAX_TOKENS = 4096
        private const val MIN_FALLBACK_TOKENS = 128
        private const val DEFAULT_TOP_K = 40
    }
    
    override val supportedFormat: ModelFormat = ModelFormat.TFLITE
    
    private var llmInference: LlmInference? = null
    private var currentSession: LlmInferenceSession? = null
    private var modelPath: String? = null
    
    @Volatile
    private var _isModelLoaded = false
    override val isModelLoaded: Boolean
        get() = _isModelLoaded
    
    @Volatile
    private var _isGenerating = false
    override val isGenerating: Boolean
        get() = _isGenerating
    
    private val shouldStop = AtomicBoolean(false)
    
    // Vision is not yet fully supported via this engine
    // Gemma 3n models have native vision but require special handling
    @Volatile
    private var _supportsVision = false
    override val supportsVision: Boolean
        get() = _supportsVision
    
    private var modelName: String? = null
    
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
        val fallback = listOf(4096, 3072, 2048, 1536, 1024, 768, 512, 384, 256, MIN_FALLBACK_TOKENS)
        return buildList {
            add(normalizedInitial)
            fallback.forEach { candidate ->
                if (candidate < normalizedInitial) {
                    add(candidate)
                }
            }
        }.distinct()
    }
    
    override suspend fun loadModel(
        modelPath: String,
        config: InferenceConfig
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Unload any existing model
            unloadModel()
            
            val modelFile = File(modelPath)
            if (!modelFile.exists()) {
                Log.e(TAG, "Model file not found: $modelPath")
                return@withContext Result.failure(
                    IllegalArgumentException("Model file not found: $modelPath")
                )
            }
            
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
            
            // Check if model supports vision based on model name
            _supportsVision = isVisionCapableModel(modelFile.name)
            
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
            Log.i(TAG, "Attempting load with maxTokens=$maxTokens, modelPath=$modelPath")
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(maxTokens)
                .build()
            
            llmInference = LlmInference.createFromOptions(context, options)
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
    ): Result<Unit> = withContext(Dispatchers.IO) {
        // MediaPipe vision models are single files, no separate projector needed
        // Just load the model and enable vision mode
        val result = loadModel(modelPath, config)
        if (result.isSuccess) {
            _supportsVision = true
            Log.i(TAG, "Vision model loaded with vision support enabled")
        }
        result
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
            try {
                llmInference?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing LLM inference during unload", e)
            }
            llmInference = null
            modelPath = null
            modelName = null
            _isModelLoaded = false
            _supportsVision = false
            Log.d(TAG, "MediaPipe LLM model unloaded")
            AppEventLogger.info(component = TAG, action = "model_unloaded")
        } catch (e: Exception) {
            Log.e(TAG, "Error unloading MediaPipe LLM model", e)
            AppEventLogger.error(component = TAG, action = "model_unload_failed", throwable = e)
        }
    }
    
    override suspend fun generate(
        prompt: String,
        params: GenerationParams
    ): String = withContext(Dispatchers.IO) {
        val inference = llmInference
            ?: throw IllegalStateException("No model loaded")
        
        _isGenerating = true
        shouldStop.set(false)
        
        try {
            Log.d(TAG, "Generating response for prompt (${prompt.length} chars)")
            AppEventLogger.info(
                component = TAG,
                action = "generate_start",
                details = "promptChars=${prompt.length}, maxTokens=${params.maxTokens}, temperature=${params.temperature}"
            )
            
            // Create a session for this generation
            val sessionOptions = LlmInferenceSessionOptions.builder()
                .setTemperature(params.temperature)
                .setTopK(params.topK.coerceAtMost(64))
                .setTopP(params.topP)
                .build()
            
            val session = LlmInferenceSession.createFromOptions(inference, sessionOptions)
            
            try {
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
                session.close()
            }
        } finally {
            _isGenerating = false
        }
    }
    
    override suspend fun generateStream(
        prompt: String,
        callback: StreamCallback,
        params: GenerationParams
    ): Boolean = withContext(Dispatchers.IO) {
        val inference = llmInference
        if (inference == null) {
            callback.onError("No model loaded")
            return@withContext false
        }
        
        _isGenerating = true
        shouldStop.set(false)
        
        try {
            Log.d(TAG, "Starting streaming generation for prompt (${prompt.length} chars)")
            AppEventLogger.info(
                component = TAG,
                action = "generate_stream_start",
                details = "promptChars=${prompt.length}, maxTokens=${params.maxTokens}, temperature=${params.temperature}"
            )
            
            // Create session options
            val sessionOptions = LlmInferenceSessionOptions.builder()
                .setTemperature(params.temperature)
                .setTopK(params.topK.coerceAtMost(64))
                .setTopP(params.topP)
                .build()
            
            // Create a new session
            val session = try {
                LlmInferenceSession.createFromOptions(inference, sessionOptions)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create inference session", e)
                callback.onError("Failed to create session: ${e.message}")
                return@withContext false
            }
            currentSession = session
            
            try {
                // Add the prompt
                session.addQueryChunk(prompt)
                
                // Track whether the listener already called onComplete
                val completedViaListener = AtomicBoolean(false)
                
                // Create progress listener for streaming
                val progressListener = ProgressListener<String> { partialResult, done ->
                    if (!shouldStop.get()) {
                        callback.onToken(partialResult)
                        if (done) {
                            completedViaListener.set(true)
                            callback.onComplete()
                        }
                    }
                }
                
                // Generate with streaming via async API
                val future = session.generateResponseAsync(progressListener)
                
                // Wait for completion - future.get() can throw even if
                // ProgressListener already called onComplete(). Guard against this.
                try {
                    future.get()
                } catch (e: Exception) {
                    if (completedViaListener.get()) {
                        // Listener already delivered the complete response
                        Log.w(TAG, "future.get() threw after listener completed: ${e.message}")
                    } else {
                        // Genuine error - listener never completed
                        throw e
                    }
                }
                
                !shouldStop.get()
            } finally {
                try {
                    session.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing session", e)
                }
                currentSession = null
                AppEventLogger.info(
                    component = TAG,
                    action = "generate_stream_end",
                    details = "stopped=${shouldStop.get()}"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Streaming generation error", e)
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
        }
    }
    
    override suspend fun generateWithImage(
        prompt: String,
        imagePath: String,
        callback: StreamCallback,
        params: GenerationParams
    ): Boolean = withContext(Dispatchers.IO) {
        // Vision support via MediaPipe requires additional setup
        // For now, fall back to text-only generation with a note
        if (!_supportsVision) {
            callback.onError("Vision is not supported for this model")
            return@withContext false
        }
        
        Log.w(TAG, "Vision support via MediaPipe is experimental")
        
        // For Gemma 3n models, we would need to use the vision-specific APIs
        // which require additional session configuration
        // For now, generate text-only and note that image was ignored
        val enhancedPrompt = "[Image provided but vision processing is limited in this version]\n\n$prompt"
        generateStream(enhancedPrompt, callback, params)
    }
    
    override fun stopGeneration() {
        shouldStop.set(true)
        _isGenerating = false
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
}
