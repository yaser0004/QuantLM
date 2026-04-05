package com.quantlm.yaser.domain.inference

import com.quantlm.yaser.domain.model.ModelFormat

/**
 * Manager for handling multiple inference engines.
 * Routes model loading and inference to the appropriate engine
 * based on model format.
 * 
 * This enables seamless switching between different model frameworks
 * (GGUF, TFLite, etc.) from the user's perspective.
 */
class InferenceEngineManager(
    private val engines: Map<ModelFormat, @JvmSuppressWildcards InferenceEngine>
) {
    
    /**
     * Currently active engine
     */
    private var activeEngine: InferenceEngine? = null
    
    /**
     * Get the currently loaded model's format
     */
    val activeFormat: ModelFormat?
        get() = activeEngine?.supportedFormat
    
    /**
     * Whether any model is currently loaded
     */
    val isModelLoaded: Boolean
        get() = activeEngine?.isModelLoaded == true
    
    /**
     * Whether inference is in progress
     */
    val isGenerating: Boolean
        get() = activeEngine?.isGenerating == true
    
    /**
     * Whether the current model supports vision
     */
    val supportsVision: Boolean
        get() = activeEngine?.supportsVision == true
    
    /**
     * Get list of supported formats
     */
    fun getSupportedFormats(): List<ModelFormat> = engines.keys.toList()
    
    /**
     * Check if a model format is supported
     */
    fun isFormatSupported(format: ModelFormat): Boolean = engines.containsKey(format)
    
    /**
     * Get the appropriate engine for a model file
     * 
     * @param filePath Path to the model file
     * @return The engine that can handle this file, or null if unsupported
     */
    fun getEngineForModel(filePath: String): InferenceEngine? {
        val format = ModelFormat.fromFileName(filePath)
        return engines[format]
    }
    
    /**
     * Load a model, automatically selecting the correct engine
     * 
     * @param modelPath Path to the model file
     * @param config Loading configuration
     * @return Result indicating success or failure
     */
    suspend fun loadModel(
        modelPath: String,
        config: InferenceConfig = InferenceConfig()
    ): Result<Unit> {
        // Determine format from file extension
        val format = ModelFormat.fromFileName(modelPath)
        val engine = engines[format] 
            ?: return Result.failure(UnsupportedModelFormatException(format))
        
        // Unload any previously loaded model
        activeEngine?.unloadModel()
        
        // Load with the appropriate engine
        val result = engine.loadModel(modelPath, config)
        if (result.isSuccess) {
            activeEngine = engine
        }
        
        return result
    }
    
    /**
     * Load a vision model with automatic engine selection
     */
    suspend fun loadVisionModel(
        modelPath: String,
        mmprojPath: String,
        config: InferenceConfig = InferenceConfig()
    ): Result<Unit> {
        val format = ModelFormat.fromFileName(modelPath)
        val engine = engines[format]
            ?: return Result.failure(UnsupportedModelFormatException(format))
        
        if (!engine.supportsVision) {
            return Result.failure(VisionNotSupportedException(format))
        }
        
        activeEngine?.unloadModel()
        
        val result = engine.loadVisionModel(modelPath, mmprojPath, config)
        if (result.isSuccess) {
            activeEngine = engine
        }
        
        return result
    }
    
    /**
     * Unload the currently loaded model
     */
    fun unloadModel() {
        activeEngine?.unloadModel()
        activeEngine = null
    }
    
    /**
     * Get the active engine for direct access to inference methods
     * @throws NoModelLoadedException if no model is loaded
     */
    fun getActiveEngine(): InferenceEngine {
        return activeEngine ?: throw NoModelLoadedException()
    }
    
    /**
     * Get the active engine if available
     */
    fun getActiveEngineOrNull(): InferenceEngine? = activeEngine
}

/**
 * Exception thrown when trying to load an unsupported model format
 */
class UnsupportedModelFormatException(val format: ModelFormat) : Exception(
    "Model format $format is not supported. Supported formats: GGUF, TFLite"
)

/**
 * Exception thrown when vision operations are attempted on a non-vision engine
 */
class VisionNotSupportedException(val format: ModelFormat) : Exception(
    "Vision/multimodal capabilities are not supported for $format models"
)

/**
 * Exception thrown when trying to use an engine without a loaded model
 */
class NoModelLoadedException : Exception(
    "No model is currently loaded. Please load a model first."
)
