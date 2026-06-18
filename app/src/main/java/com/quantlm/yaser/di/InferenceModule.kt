package com.quantlm.yaser.di

import android.content.Context
import com.quantlm.yaser.data.inference.LlamaEngine
import com.quantlm.yaser.data.inference.LiteRTEngine
import com.quantlm.yaser.data.inference.MediaPipeEngine
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

@Module
@InstallIn(SingletonComponent::class)
object InferenceModule {

    /**
     * Provides the singleton LlamaEngine instance for GGUF model inference (llama.cpp)
     * This single instance is used both with and without the qualifier annotation.
     */
    @Provides
    @Singleton
    fun provideLlamaEngineInstance(
        @ApplicationContext context: Context
    ): LlamaEngine {
        return LlamaEngine(context)
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
     * Provides the LiteRTEngine for LiteRT-LM model inference (.litertlm).
     *
     * Backed by the native `com.google.ai.edge.litertlm` Engine/Conversation
     * API.
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
 * - .litertlm files → LiteRTEngine (native LiteRT-LM)
 * - .task files → MediaPipeEngine (Google MediaPipe GenAI)
 */
@Singleton
class InferenceEngineSelector @javax.inject.Inject constructor(
    private val llamaEngine: LlamaEngine,
    private val mediaPipeEngine: MediaPipeEngine,
    private val liteRTEngine: LiteRTEngine
) {

    /**
     * Sealed class representing the engine selection result
     */
    sealed class EngineSelection {
        data class Llama(val engine: LlamaEngine) : EngineSelection()
        data class MediaPipe(val engine: MediaPipeEngine) : EngineSelection()
        data class LiteRT(val engine: LiteRTEngine) : EngineSelection()
    }

    /**
     * Get the appropriate engine selection for a model file
     */
    fun selectEngineForModel(modelPath: String): EngineSelection {
        val lowerPath = modelPath.lowercase()

        return when {
            // GGUF models use LlamaEngine
            lowerPath.endsWith(".gguf") -> EngineSelection.Llama(llamaEngine)

            // LiteRT-LM files use the native LiteRTEngine
            lowerPath.endsWith(".litertlm") -> EngineSelection.LiteRT(liteRTEngine)

            // Task files use MediaPipeEngine
            lowerPath.endsWith(".task") -> EngineSelection.MediaPipe(mediaPipeEngine)

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
     * Check if a model file is supported by any engine
     */
    fun isModelSupported(modelPath: String): Boolean {
        val format = ModelFormat.fromFileName(modelPath)
        return format == ModelFormat.GGUF || format == ModelFormat.TFLITE
    }
}
