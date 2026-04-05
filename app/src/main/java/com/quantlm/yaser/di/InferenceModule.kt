package com.quantlm.yaser.di

import android.content.Context
import com.quantlm.yaser.data.inference.LlamaEngine
import com.quantlm.yaser.data.inference.LiteRTEngine
import com.quantlm.yaser.data.inference.MediaPipeEngine
import com.quantlm.yaser.data.inference.TFLiteEngine
import com.quantlm.yaser.domain.model.ModelFormat
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifier annotations for different inference engines
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class LlamaEngineQualifier

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class LiteRTEngineQualifier

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class TFLiteEngineQualifier

@Module
@InstallIn(SingletonComponent::class)
object InferenceModule {
    
    /**
     * Provides the singleton LlamaEngine instance for GGUF model inference (llama.cpp)
     * This single instance is used both with and without the qualifier annotation.
     */
    @Provides
    @Singleton
    fun provideLlamaEngineInstance(): LlamaEngine {
        return LlamaEngine()
    }
    
    /**
     * Provides the LlamaEngine with qualifier for explicit injection
     */
    @Provides
    @Singleton
    @LlamaEngineQualifier
    fun provideLlamaEngine(llamaEngine: LlamaEngine): LlamaEngine {
        return llamaEngine
    }
    
    /**
     * Provides the TFLiteEngine for TensorFlow Lite model inference (.tflite, .task)
     */
    @Provides
    @Singleton
    fun provideTFLiteEngineInstance(): TFLiteEngine {
        return TFLiteEngine()
    }
    
    /**
     * Provides the TFLiteEngine with qualifier for explicit injection
     */
    @Provides
    @Singleton
    @TFLiteEngineQualifier
    fun provideTFLiteEngine(tfLiteEngine: TFLiteEngine): TFLiteEngine {
        return tfLiteEngine
    }
    
    /**
     * Provides the LiteRTEngine for LiteRT-LM model inference (.litertlm)
     * 
     * NOTE: Currently a wrapper around TFLiteEngine because the full
     * com.google.ai.edge.litertlm library requires Kotlin 2.2+.
     * When the project upgrades to Kotlin 2.2+, this will use the native
     * Engine/Conversation API from Google's LiteRT-LM library.
     */
    @Provides
    @Singleton
    fun provideLiteRTEngineInstance(
        @ApplicationContext context: Context
    ): LiteRTEngine {
        return LiteRTEngine(context)
    }
    
    /**
     * Provides the LiteRTEngine with qualifier for explicit injection
     */
    @Provides
    @Singleton
    @LiteRTEngineQualifier
    fun provideLiteRTEngine(liteRTEngine: LiteRTEngine): LiteRTEngine {
        return liteRTEngine
    }
    
    /**
     * Provides the MediaPipeEngine for LLM inference on .task and .litertlm models.
     * Uses Google's MediaPipe GenAI library for on-device LLM inference.
     */
    @Provides
    @Singleton
    fun provideMediaPipeEngineInstance(
        @ApplicationContext context: Context
    ): MediaPipeEngine {
        return MediaPipeEngine(context)
    }
}

/**
 * Helper class for selecting the appropriate inference engine based on model format.
 * 
 * Engine selection:
 * - .gguf files → LlamaEngine (llama.cpp native)
 * - .task, .litertlm files → MediaPipeEngine (Google MediaPipe GenAI)
 * - .tflite, .literlm files → TFLiteEngine (TensorFlow Lite - legacy)
 */
@Singleton
class InferenceEngineSelector @javax.inject.Inject constructor(
    private val llamaEngine: LlamaEngine,
    private val mediaPipeEngine: MediaPipeEngine,
    private val liteRTEngine: LiteRTEngine,
    private val tfLiteEngine: TFLiteEngine
) {
    
    /**
     * Sealed class representing the engine selection result
     */
    sealed class EngineSelection {
        data class Llama(val engine: LlamaEngine) : EngineSelection()
        data class MediaPipe(val engine: MediaPipeEngine) : EngineSelection()
        data class LiteRT(val engine: LiteRTEngine) : EngineSelection()
        data class TFLite(val engine: TFLiteEngine) : EngineSelection()
    }
    
    /**
     * Get the appropriate engine selection for a model file
     */
    fun selectEngineForModel(modelPath: String): EngineSelection {
        val lowerPath = modelPath.lowercase()
        
        return when {
            // GGUF models use LlamaEngine
            lowerPath.endsWith(".gguf") -> EngineSelection.Llama(llamaEngine)
            
            // Task and LiteRT-LM files use MediaPipeEngine (primary LLM engine)
            lowerPath.endsWith(".task") ||
            lowerPath.endsWith(".litertlm") -> EngineSelection.MediaPipe(mediaPipeEngine)
            
            // TFLite files use TFLiteEngine (legacy, non-LLM)
            lowerPath.endsWith(".tflite") || 
            lowerPath.endsWith(".literlm") -> EngineSelection.TFLite(tfLiteEngine)
            
            // Default to LlamaEngine for unknown formats
            else -> EngineSelection.Llama(llamaEngine)
        }
    }
    
    /**
     * Get engine selection for a specific format
     */
    fun selectEngineForFormat(format: ModelFormat): EngineSelection {
        return when (format) {
            ModelFormat.GGUF -> EngineSelection.Llama(llamaEngine)
            ModelFormat.TFLITE -> EngineSelection.MediaPipe(mediaPipeEngine)
            ModelFormat.SAFETENSORS, ModelFormat.UNKNOWN -> EngineSelection.Llama(llamaEngine)
        }
    }
    
    /**
     * Get the LlamaEngine directly (for llama.cpp specific operations)
     */
    fun getLlamaEngine(): LlamaEngine = llamaEngine
    
    /**
     * Get the MediaPipeEngine directly (for MediaPipe LLM operations)
     */
    fun getMediaPipeEngine(): MediaPipeEngine = mediaPipeEngine
    
    /**
     * Get the LiteRTEngine directly (for LiteRT-LM specific operations)
     */
    fun getLiteRTEngine(): LiteRTEngine = liteRTEngine
    
    /**
     * Get the TFLiteEngine directly (for TFLite specific operations)
     */
    fun getTFLiteEngine(): TFLiteEngine = tfLiteEngine
    
    /**
     * Check if a model file is supported by any engine
     */
    fun isModelSupported(modelPath: String): Boolean {
        val format = ModelFormat.fromFileName(modelPath)
        return format == ModelFormat.GGUF || format == ModelFormat.TFLITE
    }
}
