package com.quantlm.yaser.data.inference

import android.util.Log
import com.quantlm.yaser.data.diagnostics.AppEventLogger
import com.quantlm.yaser.domain.inference.InferenceError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LlamaEngine @Inject constructor() {
    
    companion object {
        private const val TAG = "LlamaEngine"
        
        init {
            try {
                System.loadLibrary("quantlm")
                Log.i(TAG, "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library", e)
            }
        }
    }
    
    @Volatile
    private var isModelLoaded = false
    
    @Volatile
    private var isGenerating = false
    
    @Volatile
    private var isVisionModelLoaded = false
    
    @Volatile
    private var loadedModelName: String? = null
    
    // Native method declarations
    private external fun nativeInit(): Boolean
    
    private external fun nativeLoadModel(
        modelPath: String,
        nThreads: Int,
        nGpuLayers: Int,
        contextSize: Int
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
    
    init {
        try {
            nativeInit()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize native engine - native library may not be loaded", e)
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
        contextSize: Int = 2048
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val file = File(modelPath)
            if (!file.exists()) {
                return@withContext Result.failure(Exception("Model file not found: $modelPath"))
            }
            
            if (isModelLoaded) {
                unloadModel()
            }
            
            val success = nativeLoadModel(modelPath, nThreads, nGpuLayers, contextSize)
            
            if (success) {
                isModelLoaded = true
                // Extract model name from file path (without extension)
                loadedModelName = file.nameWithoutExtension
                Log.i(TAG, "Model loaded successfully: $modelPath")
                AppEventLogger.info(
                    component = TAG,
                    action = "model_loaded",
                    details = "name=${loadedModelName ?: file.name}, bytes=${file.length()}, context=$contextSize, threads=$nThreads, gpuLayers=$nGpuLayers"
                )
                Result.success(Unit)
            } else {
                val failure = InferenceError.fromNativeCode(
                    nativeGetLastInferenceErrorCode(),
                    "nativeLoadModel returned false for $modelPath"
                )
                AppEventLogger.error(
                    component = TAG,
                    action = "model_load_failed",
                    details = "path=$modelPath, reason=${failure.message ?: "unknown"}",
                    throwable = failure
                )
                Result.failure(
                    failure
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading model", e)
            AppEventLogger.error(
                component = TAG,
                action = "model_load_exception",
                details = "path=$modelPath",
                throwable = e
            )
            Result.failure(e)
        }
    }
    
    fun unloadModel() {
        if (isModelLoaded) {
            nativeUnloadModel()
            isModelLoaded = false
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
        try {
            if (!isModelLoaded) {
                return@withContext Result.failure(Exception("No model loaded"))
            }
            
            isGenerating = true
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
            isGenerating = false
            
            Result.success(response)
        } catch (e: Exception) {
            isGenerating = false
            Log.e(TAG, "Generation error", e)
            Result.failure(e)
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
        try {
            Log.d(TAG, "=== GENERATE STREAM START ===")
            Log.d(TAG, "isModelLoaded: $isModelLoaded")
            Log.d(TAG, "isGenerating (before): $isGenerating")
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
            
            // Always reset flag at start to prevent blocking
            if (isGenerating) {
                Log.w(TAG, "WARNING: Flag was stuck at true, resetting to allow generation")
            }
            isGenerating = false
            
            isGenerating = true
            Log.d(TAG, "Set isGenerating = true, starting native generation")
            
            val callback = object : StreamCallback {
                override fun onToken(token: String) {
                    Log.v(TAG, "Token callback: '$token'")
                    onToken(token)
                }
                
                override fun onComplete() {
                    Log.d(TAG, "Complete callback received, resetting isGenerating flag")
                    isGenerating = false
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
            } catch (e: Exception) {
                Log.e(TAG, "Native generation threw exception", e)
                AppEventLogger.error(
                    component = TAG,
                    action = "generate_stream_native_exception",
                    details = "model=${loadedModelName ?: "unknown"}",
                    throwable = e
                )
                isGenerating = false
                throw e
            }
            
            Log.d(TAG, "=== GENERATE STREAM END ===")
        } catch (e: Exception) {
            Log.e(TAG, "Streaming generation error", e)
            AppEventLogger.error(
                component = TAG,
                action = "generate_stream_failed",
                details = "model=${loadedModelName ?: "unknown"}",
                throwable = e
            )
            isGenerating = false
            throw e
        }
    }
    
    fun stopGeneration() {
        Log.d(TAG, "stopGeneration called, resetting flag")
        if (isGenerating && isModelLoaded) {
            try {
                nativeStopGeneration()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping native generation", e)
            }
        }
        // Always reset the flag regardless of previous state
        isGenerating = false
    }
    
    fun getModelInfo(): String {
        return if (isModelLoaded) {
            nativeGetModelInfo()
        } else {
            "No model loaded"
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
    
    fun cleanup() {
        unloadModel()
        unloadVisionModel()
        nativeCleanup()
    }
    
    // Vision model functions
    
    suspend fun loadVisionModel(
        modelPath: String,
        mmprojPath: String,
        nThreads: Int = 4,
        nGpuLayers: Int = 0,
        contextSize: Int = 2048
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val modelFile = File(modelPath)
            val mmprojFile = File(mmprojPath)
            
            if (!modelFile.exists()) {
                return@withContext Result.failure(Exception("Model file not found: $modelPath"))
            }
            if (!mmprojFile.exists()) {
                return@withContext Result.failure(Exception("Vision projector file not found: $mmprojPath"))
            }
            
            if (isVisionModelLoaded) {
                unloadVisionModel()
            }
            if (isModelLoaded) {
                unloadModel()
            }
            
            val success = nativeLoadVisionModel(modelPath, mmprojPath, nThreads, nGpuLayers, contextSize)
            
            if (success) {
                isVisionModelLoaded = true
                isModelLoaded = true
                // Extract model name from file path (without extension)
                loadedModelName = modelFile.nameWithoutExtension
                Log.i(TAG, "Vision model loaded successfully: $modelPath")
                AppEventLogger.info(
                    component = TAG,
                    action = "vision_model_loaded",
                    details = "name=${loadedModelName ?: modelFile.name}, mmproj=${mmprojFile.name}, context=$contextSize"
                )
                Result.success(Unit)
            } else {
                val failure = InferenceError.fromNativeCode(
                    nativeGetLastInferenceErrorCode(),
                    "nativeLoadVisionModel returned false"
                )
                AppEventLogger.error(
                    component = TAG,
                    action = "vision_model_load_failed",
                    details = "path=$modelPath, mmproj=$mmprojPath, reason=${failure.message ?: "unknown"}",
                    throwable = failure
                )
                Result.failure(
                    failure
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading vision model", e)
            AppEventLogger.error(
                component = TAG,
                action = "vision_model_load_exception",
                details = "path=$modelPath, mmproj=$mmprojPath",
                throwable = e
            )
            Result.failure(e)
        }
    }
    
    fun unloadVisionModel() {
        if (isVisionModelLoaded) {
            nativeUnloadVisionModel()
            isVisionModelLoaded = false
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
        try {
            if (!isVisionModelLoaded) {
                return@withContext Result.failure(Exception("No vision model loaded"))
            }
            
            val imageFile = File(imagePath)
            if (!imageFile.exists()) {
                return@withContext Result.failure(Exception("Image file not found: $imagePath"))
            }
            
            isGenerating = true
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
            isGenerating = false
            
            Result.success(response)
        } catch (e: Exception) {
            isGenerating = false
            Log.e(TAG, "Vision generation error", e)
            Result.failure(e)
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
            
            if (isGenerating) {
                Log.w(TAG, "WARNING: Flag was stuck at true, resetting to allow generation")
            }
            isGenerating = false
            isGenerating = true
            
            val callback = object : StreamCallback {
                override fun onToken(token: String) {
                    Log.v(TAG, "Token callback: '$token'")
                    onToken(token)
                }
                
                override fun onComplete() {
                    Log.d(TAG, "Complete callback received, resetting isGenerating flag")
                    isGenerating = false
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
            } catch (e: Exception) {
                Log.e(TAG, "Native vision generation threw exception", e)
                isGenerating = false
                throw e
            }
            
            Log.d(TAG, "=== GENERATE STREAM WITH IMAGE END ===")
        } catch (e: Exception) {
            Log.e(TAG, "Streaming vision generation error", e)
            isGenerating = false
            throw e
        }
    }
    
    interface StreamCallback {
        fun onToken(token: String)
        fun onComplete()
        fun onError(message: String) {} // Default empty implementation for backward compatibility
    }
}
