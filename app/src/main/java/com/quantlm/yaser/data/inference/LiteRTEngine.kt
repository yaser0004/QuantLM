package com.quantlm.yaser.data.inference

import android.content.Context
import android.util.Log
import com.quantlm.yaser.data.inference.LlamaEngine.StreamCallback
import com.quantlm.yaser.domain.inference.GenerationParams
import com.quantlm.yaser.domain.inference.InferenceConfig
import com.quantlm.yaser.domain.inference.InferenceEngine
import com.quantlm.yaser.domain.model.ModelFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LiteRT-LM inference engine for Google's official LiteRT LLM models.
 * 
 * This engine wraps MediaPipeEngine to provide LLM inference for .litertlm models.
 * The MediaPipe GenAI library provides:
 * - GPU and CPU acceleration
 * - Multi-modality (vision and audio) for Gemma 3n models
 * - Streaming responses via callbacks
 * 
 * Models available on HuggingFace:
 * - litert-community/Gemma3-1B-IT
 * - google/gemma-3n-E2B-it-litert-lm
 * - google/gemma-3n-E4B-it-litert-lm
 * - litert-community/Qwen2.5-1.5B-Instruct
 * - litert-community/Phi-4-mini-instruct
 * - litert-community/DeepSeek-R1-Distill-Qwen-1.5B
 * 
 * @see <a href="https://github.com/google-ai-edge/LiteRT-LM">LiteRT-LM GitHub</a>
 */
@Singleton
class LiteRTEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : InferenceEngine {
    
    companion object {
        private const val TAG = "LiteRTEngine"
        
        /**
         * Check if MediaPipe LLM Inference is available
         */
        fun isMediaPipeAvailable(): Boolean {
            return try {
                Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")
                true
            } catch (e: ClassNotFoundException) {
                false
            }
        }
    }
    
    override val supportedFormat: ModelFormat = ModelFormat.TFLITE
    
    private var modelPath: String? = null
    
    @Volatile
    private var _isModelLoaded = false
    override val isModelLoaded: Boolean
        get() = _isModelLoaded
    
    @Volatile
    private var _isGenerating = false
    override val isGenerating: Boolean
        get() = _isGenerating
    
    @Volatile
    private var shouldStop = false
    
    @Volatile
    private var _supportsVision = false
    override val supportsVision: Boolean
        get() = _supportsVision
    
    @Volatile
    private var _supportsAudio = false
    val supportsAudio: Boolean
        get() = _supportsAudio
    
    private var currentBackend: String = "CPU"
    private var systemMessage: String? = null
    
    // Delegate to MediaPipeEngine for actual LLM inference
    private val delegateEngine: MediaPipeEngine by lazy { MediaPipeEngine(context) }
    
    override suspend fun loadModel(
        modelPath: String,
        config: InferenceConfig
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val modelFile = File(modelPath)
            if (!modelFile.exists()) {
                return@withContext Result.failure(
                    IllegalArgumentException("Model file not found: $modelPath")
                )
            }
            
            // Log that we're using MediaPipe engine
            Log.i(TAG, "Using MediaPipeEngine for LiteRT model: $modelPath")
            
            // Delegate to TFLiteEngine
            val result = delegateEngine.loadModel(modelPath, config)
            
            if (result.isSuccess) {
                this@LiteRTEngine.modelPath = modelPath
                _isModelLoaded = true
                currentBackend = if (config.nGpuLayers > 0) "GPU" else "CPU"
            }
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            Result.failure(e)
        }
    }
    
    override suspend fun loadVisionModel(
        modelPath: String,
        mmprojPath: String,
        config: InferenceConfig
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Loading vision model via TFLiteEngine fallback")
            
            val result = delegateEngine.loadVisionModel(modelPath, mmprojPath, config)
            
            if (result.isSuccess) {
                this@LiteRTEngine.modelPath = modelPath
                _isModelLoaded = true
                _supportsVision = true
                currentBackend = if (config.nGpuLayers > 0) "GPU" else "CPU"
            }
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load vision model", e)
            Result.failure(e)
        }
    }
    
    /**
     * Load a model with audio support (for Audio Scribe feature)
     * Note: Full audio support requires LiteRT-LM library (Kotlin 2.2+)
     */
    suspend fun loadAudioModel(
        modelPath: String,
        config: InferenceConfig
    ): Result<Unit> = withContext(Dispatchers.IO) {
        Log.w(TAG, "Audio support requires LiteRT-LM library (Kotlin 2.2+)")
        Log.w(TAG, "Loading as standard model without audio capabilities")
        
        val result = loadModel(modelPath, config)
        if (result.isSuccess) {
            _supportsAudio = false // Audio not supported without LiteRT-LM
        }
        result
    }
    
    /**
     * Load a multimodal model with vision and/or audio support
     * Note: Full multimodal support requires LiteRT-LM library (Kotlin 2.2+)
     */
    suspend fun loadMultimodalModel(
        modelPath: String,
        config: InferenceConfig,
        enableVision: Boolean = true,
        enableAudio: Boolean = true
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (enableAudio) {
            Log.w(TAG, "Audio support requires LiteRT-LM library (Kotlin 2.2+)")
        }
        
        return@withContext if (enableVision) {
            loadVisionModel(modelPath, "", config)
        } else {
            loadModel(modelPath, config)
        }
    }
    
    override fun unloadModel() {
        delegateEngine.unloadModel()
        modelPath = null
        _isModelLoaded = false
        _supportsVision = false
        _supportsAudio = false
        Log.d(TAG, "Model unloaded")
    }
    
    fun setSystemMessage(message: String?) {
        systemMessage = message
    }
    
    override suspend fun generate(
        prompt: String,
        params: GenerationParams
    ): String = withContext(Dispatchers.IO) {
        if (!_isModelLoaded) {
            throw IllegalStateException("No model loaded")
        }
        
        _isGenerating = true
        shouldStop = false
        
        try {
            delegateEngine.generate(prompt, params)
        } finally {
            _isGenerating = false
        }
    }
    
    override suspend fun generateStream(
        prompt: String,
        callback: StreamCallback,
        params: GenerationParams
    ): Boolean = withContext(Dispatchers.IO) {
        if (!_isModelLoaded) {
            callback.onError("No model loaded")
            return@withContext false
        }
        
        _isGenerating = true
        shouldStop = false
        
        try {
            delegateEngine.generateStream(prompt, callback, params)
        } finally {
            _isGenerating = false
        }
    }
    
    /**
     * Generate with audio input (for audio transcription/translation)
     * Note: Requires LiteRT-LM library (Kotlin 2.2+)
     */
    suspend fun generateWithAudio(
        prompt: String,
        audioBytes: ByteArray,
        callback: StreamCallback,
        params: GenerationParams
    ): Boolean = withContext(Dispatchers.IO) {
        callback.onError("Audio generation requires LiteRT-LM library (Kotlin 2.2+). Please upgrade project to Kotlin 2.2+ to enable this feature.")
        false
    }
    
    override suspend fun generateWithImage(
        prompt: String,
        imagePath: String,
        callback: StreamCallback,
        params: GenerationParams
    ): Boolean = withContext(Dispatchers.IO) {
        if (!_isModelLoaded) {
            callback.onError("No model loaded")
            return@withContext false
        }
        
        _isGenerating = true
        shouldStop = false
        
        try {
            delegateEngine.generateWithImage(prompt, imagePath, callback, params)
        } finally {
            _isGenerating = false
        }
    }
    
    override fun stopGeneration() {
        shouldStop = true
        _isGenerating = false
        delegateEngine.stopGeneration()
        Log.d(TAG, "Stop generation requested")
    }
    
    override fun getModelInfo(): Map<String, String> {
        val info = mutableMapOf(
            "format" to "LiteRT (MediaPipe)",
            "engine" to "MediaPipeEngine",
            "status" to if (_isModelLoaded) "loaded" else "not loaded",
            "backend" to currentBackend,
            "mediaPipeAvailable" to isMediaPipeAvailable().toString()
        )
        
        modelPath?.let {
            info["modelPath"] = it
            info["modelFile"] = File(it).name
        }
        
        info["supportsVision"] = _supportsVision.toString()
        info["supportsAudio"] = _supportsAudio.toString()
        
        // Add delegate engine info
        info.putAll(delegateEngine.getModelInfo().mapKeys { "delegate_${it.key}" })
        
        return info
    }
    
    override fun canHandle(filePath: String): Boolean {
        val lowerPath = filePath.lowercase()
        return lowerPath.endsWith(".litertlm")
    }
    
    /**
     * Check if this engine should be preferred for a given file
     * 
     * LiteRTEngine is preferred for .litertlm files but currently
     * delegates to TFLiteEngine due to Kotlin version requirements.
     */
    fun isPreferredFor(filePath: String): Boolean {
        return filePath.lowercase().endsWith(".litertlm")
    }
    
    /**
     * Get the loaded model name
     */
    fun getLoadedModelName(): String? = delegateEngine.getLoadedModelName()
    
    /**
     * Check if vision is supported
     */
    fun isVisionSupported(): Boolean = delegateEngine.isVisionSupported()
    
    /**
     * Get information about engine capabilities
     */
    fun getCapabilitiesInfo(): String {
        return "LiteRT models are run using MediaPipe GenAI library. " +
               "require the com.google.ai.edge.litertlm library which needs Kotlin 2.2+. " +
               "Current project uses Kotlin 1.9.x. Using TFLite fallback for basic inference."
    }
}
