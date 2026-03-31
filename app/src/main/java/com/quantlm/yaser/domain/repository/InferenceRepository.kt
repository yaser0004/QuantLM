package com.quantlm.yaser.domain.repository

import com.quantlm.yaser.domain.model.GenerationState
import kotlinx.coroutines.flow.Flow

interface InferenceRepository {
    
    suspend fun generate(
        prompt: String,
        temperature: Float,
        maxTokens: Int,
        topP: Float,
        topK: Int,
        repeatPenalty: Float = 1.1f,
        repeatLastN: Int = 64,
        minP: Float = 0.05f,
        tfsZ: Float = 1.0f,
        typicalP: Float = 1.0f,
        mirostat: Int = 0,
        mirostatTau: Float = 5.0f,
        mirostatEta: Float = 0.1f
    ): Result<String>
    
    fun generateStream(
        prompt: String,
        temperature: Float,
        maxTokens: Int,
        topP: Float,
        topK: Int,
        repeatPenalty: Float = 1.1f,
        repeatLastN: Int = 64,
        minP: Float = 0.05f,
        tfsZ: Float = 1.0f,
        typicalP: Float = 1.0f,
        mirostat: Int = 0,
        mirostatTau: Float = 5.0f,
        mirostatEta: Float = 0.1f
    ): Flow<GenerationState>
    
    suspend fun stopGeneration()
    
    /**
     * Get the chat template type embedded in the loaded model.
     * Returns null if no model is loaded or no template is embedded.
     * Common values: "chatml", "llama3", "phi3", "gemma", "qwen", etc.
     */
    fun getChatTemplate(): String?
    
    /**
     * Get the name of the currently loaded model.
     * Returns null if no model is loaded.
     */
    fun getLoadedModelName(): String?
    
    // Vision model methods
    fun isVisionSupported(): Boolean
    
    suspend fun generateWithImage(
        prompt: String,
        imagePath: String,
        temperature: Float,
        maxTokens: Int,
        topP: Float,
        topK: Int,
        repeatPenalty: Float = 1.1f,
        repeatLastN: Int = 64,
        minP: Float = 0.05f,
        tfsZ: Float = 1.0f,
        typicalP: Float = 1.0f,
        mirostat: Int = 0,
        mirostatTau: Float = 5.0f,
        mirostatEta: Float = 0.1f
    ): Result<String>
    
    fun generateStreamWithImage(
        prompt: String,
        imagePath: String,
        temperature: Float,
        maxTokens: Int,
        topP: Float,
        topK: Int,
        repeatPenalty: Float = 1.1f,
        repeatLastN: Int = 64,
        minP: Float = 0.05f,
        tfsZ: Float = 1.0f,
        typicalP: Float = 1.0f,
        mirostat: Int = 0,
        mirostatTau: Float = 5.0f,
        mirostatEta: Float = 0.1f
    ): Flow<GenerationState>
}
