package com.quantlm.yaser.domain.repository

import com.quantlm.yaser.domain.inference.SamplingCapabilities
import com.quantlm.yaser.domain.inference.SamplingParam
import com.quantlm.yaser.domain.model.GenerationState
import com.quantlm.yaser.domain.model.ModelCapability
import kotlinx.coroutines.flow.Flow

interface InferenceRepository {

    /**
     * Phase 1: capabilities the currently active engine reports it can run for
     * the loaded model, intersected with the manifest entry's declared set.
     *
     * Returns [emptySet] when no engine is active. UI surfaces should observe
     * this (via the loaded-model flow on ModelRepository) rather than calling
     * engines or inspecting model names directly.
     */
    fun getActiveCapabilities(): Set<ModelCapability> = emptySet()

    /**
     * Phase 1: reset conversation-scoped state on the active engine without
     * unloading the model. Call from new-conversation / edit / regenerate
     * flows. Default is a no-op so engines without conversation state
     * (and tests/fakes) don't need to override.
     */
    suspend fun resetConversation() = Unit
    
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
        mirostatEta: Float = 0.1f,
        stopSequences: List<String> = emptyList()
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
        mirostatEta: Float = 0.1f,
        stopSequences: List<String> = emptyList(),
        reasoningEnabled: Boolean = false
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

    /**
     * Get the active inference backend label (for example CPU or GPU).
     */
    fun getActiveBackendLabel(): String

    /**
     * Get the active model format label (for example GGUF or LiteRT).
     */
    fun getActiveModelFormatLabel(): String

    /**
     * Effective context window of the active model in tokens. Used by
     * [SendMessageUseCase] to budget conversation history so prefill cost
     * stays bounded. Returns 0 when no model is loaded.
     */
    fun getActiveContextTokens(): Int = 0

    /**
     * The sampling parameters the active engine honors. Returns the full set
     * when no model is loaded so the Settings UI stays fully editable.
     */
    fun getActiveSamplingCapabilities(): Set<SamplingParam> = SamplingCapabilities.FULL
    
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
        mirostatEta: Float = 0.1f,
        stopSequences: List<String> = emptyList()
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
        mirostatEta: Float = 0.1f,
        stopSequences: List<String> = emptyList(),
        reasoningEnabled: Boolean = false
    ): Flow<GenerationState>

    // Audio model methods
    fun isAudioSupported(): Boolean = false

    fun generateStreamWithAudio(
        prompt: String,
        audioPath: String,
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
        mirostatEta: Float = 0.1f,
        stopSequences: List<String> = emptyList(),
        reasoningEnabled: Boolean = false
    ): Flow<GenerationState>
}
