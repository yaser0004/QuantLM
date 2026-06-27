package com.quantlm.yaser.domain.inference

import com.quantlm.yaser.data.inference.LlamaEngine.StreamCallback
import com.quantlm.yaser.data.local.GenerationPreferences.HardwareAccelerationMode
import com.quantlm.yaser.domain.model.ModelCapability
import com.quantlm.yaser.domain.model.ModelFormat
import kotlinx.coroutines.flow.Flow

/**
 * Abstract interface for inference engines.
 * Allows QuantLM to support multiple model formats/frameworks
 * with a unified API for loading, generating, and managing models.
 * 
 * Current implementations:
 * - LlamaEngine (GGUF format via llama.cpp)
 * - MediaPipeEngine (.task / .litertlm via MediaPipe GenAI)
 * - LiteRTEngine (.litertlm via native LiteRT-LM)
 */
interface InferenceEngine {
    
    /**
     * The model format this engine supports
     */
    val supportedFormat: ModelFormat
    
    /**
     * Whether a model is currently loaded and ready for inference
     */
    val isModelLoaded: Boolean
    
    /**
     * Whether inference is currently in progress
     */
    val isGenerating: Boolean
    
    /**
     * Whether this engine supports vision/multimodal capabilities
     */
    val supportsVision: Boolean

    /**
     * The sampling parameters this engine actually honors. The Settings UI
     * uses this to disable controls the active engine would silently ignore.
     * Defaults to the full surface; SDK-backed engines override with a subset.
     */
    val samplingCapabilities: Set<SamplingParam>
        get() = SamplingCapabilities.FULL
    
    /**
     * Load a model from the given path
     *
     * @param modelPath Path to the model file
     * @param config Configuration options for loading
     * @return Result indicating success or failure
     */
    suspend fun loadModel(
        modelPath: String,
        config: InferenceConfig = InferenceConfig()
    ): Result<Unit>

    /**
     * Phase 1: load with capability-aware options.
     *
     * The default implementation delegates to the simple [loadModel] overload and
     * ignores [options]; concrete engines override to honor accelerator selection,
     * thinking, and speculative-decoding init flags. Keeping a default keeps source
     * compat for any engine that hasn't been updated yet.
     */
    suspend fun loadModel(
        modelPath: String,
        config: InferenceConfig,
        options: LoadOptions,
    ): Result<Unit> = loadModel(modelPath, config)

    /**
     * Phase 1: capabilities the engine can actually run for the currently loaded
     * model. Returned set is intersected with the manifest entry's declared
     * capabilities by the repository layer before being surfaced to the UI.
     *
     * Engines that have no model loaded should return [emptySet].
     */
    fun getRuntimeCapabilities(): Set<ModelCapability> = emptySet()

    /**
     * Phase 1: reset any conversation-scoped state (KV cache, MediaPipe session,
     * etc.) without unloading the model. Called when the user starts a new
     * conversation or edits/regenerates a turn. Default no-op for engines that
     * don't carry conversation state.
     */
    suspend fun resetConversation() = Unit
    
    /**
     * Load a vision-capable model with multimodal projector
     * 
     * @param modelPath Path to the main model file
     * @param mmprojPath Path to the multimodal projector file
     * @param config Configuration options
     * @return Result indicating success or failure
     */
    suspend fun loadVisionModel(
        modelPath: String,
        mmprojPath: String,
        config: InferenceConfig = InferenceConfig()
    ): Result<Unit>
    
    /**
     * Unload the currently loaded model and free resources
     */
    fun unloadModel()
    
    /**
     * Generate text completion for the given prompt (blocking)
     * 
     * @param prompt The input prompt
     * @param params Generation parameters
     * @return Generated text
     */
    suspend fun generate(
        prompt: String,
        params: GenerationParams = GenerationParams()
    ): String
    
    /**
     * Generate text completion with streaming output
     * 
     * @param prompt The input prompt
     * @param callback Callback to receive tokens as they're generated
     * @param params Generation parameters
     * @return Whether generation completed successfully
     */
    suspend fun generateStream(
        prompt: String,
        callback: StreamCallback,
        params: GenerationParams = GenerationParams()
    ): Boolean
    
    /**
     * Generate text with image input (for vision models)
     * 
     * @param prompt The input prompt
     * @param imagePath Path to the image file
     * @param callback Callback for streaming output
     * @param params Generation parameters
     * @return Whether generation completed successfully
     */
    suspend fun generateWithImage(
        prompt: String,
        imagePath: String,
        callback: StreamCallback,
        params: GenerationParams = GenerationParams()
    ): Boolean
    
    /**
     * Stop any ongoing generation
     */
    fun stopGeneration()
    
    /**
     * Get information about the currently loaded model
     * @return Map of model metadata
     */
    fun getModelInfo(): Map<String, String>
    
    /**
     * Check if this engine can handle the given model file
     * 
     * @param filePath Path to the model file
     * @return Whether this engine can load and run this model
     */
    fun canHandle(filePath: String): Boolean {
        val format = ModelFormat.fromFileName(filePath)
        return format == supportedFormat
    }
}

/**
 * Configuration options for loading a model
 */
data class InferenceConfig(
    val nThreads: Int = 4,
    val nGpuLayers: Int = 0,
    val contextSize: Int = 2048,
    val useMlock: Boolean = false,
    val useFlashAttention: Boolean = true
)

/**
 * Phase 1: capability-aware load options.
 *
 * - [accelerationMode] is honored by both engines. MediaPipe historically ignored
 *   it; engines that can't actually steer their backend should reflect this in
 *   the active backend label so the UI doesn't lie.
 * - [enableThinking] gates the chat-template `enable_thinking` flag (llama.cpp
 *   `common_chat_templates_inputs.enable_thinking`).
 * - [enableSpeculativeDecoding] is metadata-only in Phase 1 (no GGUF runtime
 *   path yet; see prompts/QuantLM_Phase1_Opus.md §1.4). Engines that don't
 *   implement it should silently ignore.
 */
data class LoadOptions(
    val accelerationMode: HardwareAccelerationMode = HardwareAccelerationMode.GPU,
    val enableThinking: Boolean = false,
    val enableSpeculativeDecoding: Boolean = false,
)

/**
 * Parameters for text generation
 */
data class GenerationParams(
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f,
    val repeatLastN: Int = 64,
    val minP: Float = 0.05f,
    val tfsZ: Float = 1.0f,
    val typicalP: Float = 1.0f,
    val mirostat: Int = 0,
    val mirostatTau: Float = 5.0f,
    val mirostatEta: Float = 0.1f,
    val stopSequences: List<String> = emptyList(),
    /** Gates the LiteRT-LM `enable_thinking` extra-context flag. */
    val enableThinking: Boolean = false
)

/**
 * Structured error codes for inference operations.
 *
 * Use these instead of raw strings when propagating errors from engines,
 * so the presentation layer can show specific user-facing messages.
 */
sealed class InferenceError(errorMsg: String) : Exception(errorMsg) {
    /** No model has been loaded yet. */
    object ModelNotLoaded : InferenceError("No model is currently loaded")

    /**
     * The system prompt exceeds the model's context window.
     * [maxTokens] is the engine's effective context limit.
     */
    class SystemPromptTooLong(val maxTokens: Int) :
        InferenceError("System prompt exceeds the maximum context length of $maxTokens tokens")

    /**
     * The model or engine rejected the system prompt for a reason other than length
     * (e.g. unsupported role, format incompatibility).
     */
    class SystemPromptRejected(val reason: String) :
        InferenceError("System prompt was rejected: $reason")

    /**
     * Generation failed or was interrupted.
     * [errorCause] is the underlying exception if available.
     */
    class GenerationFailed(val errorCause: Throwable? = null) :
        InferenceError(errorCause?.message ?: "Text generation failed unexpectedly")

    /** Vision input was provided but the loaded model does not support images. */
    object VisionNotSupported : InferenceError("The current model does not support image input")

    /**
     * Fix [2.6]: JNI / llama.cpp reported a load failure; [nativeCode] matches llama_jni.cpp (1=model, 2=context, 3=mtmd).
     */
    class NativeBridgeFailure(val nativeCode: Int, detail: String) : InferenceError(
        userMessageFromNativeCode(nativeCode, detail)
    )

    companion object {
        const val NATIVE_CODE_NONE = 0
        const val NATIVE_CODE_MODEL_LOAD_FAILED = 1
        const val NATIVE_CODE_CONTEXT_INIT_FAILED = 2
        const val NATIVE_CODE_VISION_MTMD_FAILED = 3

        fun fromNativeCode(code: Int, detail: String): NativeBridgeFailure =
            NativeBridgeFailure(code, detail)
    }
}

private fun userMessageFromNativeCode(code: Int, detail: String): String = when (code) {
    InferenceError.NATIVE_CODE_NONE,
    InferenceError.NATIVE_CODE_MODEL_LOAD_FAILED ->
        "Could not load the model file. It may be corrupted, incomplete, or not a supported GGUF. ($detail)"
    InferenceError.NATIVE_CODE_CONTEXT_INIT_FAILED ->
        "Failed to create a model context. Try lowering context size or GPU layers, or use a smaller model. ($detail)"
    InferenceError.NATIVE_CODE_VISION_MTMD_FAILED ->
        "Failed to load the vision (mmproj) component. Ensure it matches the base model. ($detail)"
    else -> "Model error (code $code): $detail"
}
