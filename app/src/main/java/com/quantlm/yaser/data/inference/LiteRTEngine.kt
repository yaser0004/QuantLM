package com.quantlm.yaser.data.inference

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Native LiteRT-LM inference engine for Google's `.litertlm` LLM models.
 *
 * Backed by the `com.google.ai.edge.litertlm` library (Engine / Conversation
 * API), which wraps the C++ LiteRT-LM runtime via JNI. It supports:
 * - GPU and CPU acceleration (selected per-load via [Backend])
 * - Streaming responses
 * - Multimodal input (vision and audio) for capable models
 *
 * Models available on HuggingFace:
 * - litert-community/Gemma3-1B-IT
 * - litert-community/Qwen2.5-1.5B-Instruct
 * - litert-community/Phi-4-mini-instruct
 *
 * @see <a href="https://github.com/google-ai-edge/LiteRT-LM">LiteRT-LM GitHub</a>
 */
@Singleton
class LiteRTEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : InferenceEngine {

    companion object {
        private const val TAG = "LiteRTEngine"

        /** Watchdog cap so a wedged native callback cannot hang generation. */
        private const val STREAM_TIMEOUT_MS = 10 * 60 * 1000L

        /**
         * Check whether the native LiteRT-LM library is available on the
         * classpath.
         */
        fun isLiteRtLmAvailable(): Boolean {
            return try {
                Class.forName("com.google.ai.edge.litertlm.Engine")
                true
            } catch (e: ClassNotFoundException) {
                false
            }
        }
    }

    override val supportedFormat: ModelFormat = ModelFormat.TFLITE

    // The LiteRT-LM SDK honors only temperature / topK / topP.
    override val samplingCapabilities: Set<SamplingParam> = SamplingCapabilities.BASIC

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var modelPath: String? = null
    private var modelName: String? = null
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

    @Volatile
    private var _supportsVision = false
    override val supportsVision: Boolean
        get() = _supportsVision

    @Volatile
    private var _supportsAudio = false
    val supportsAudio: Boolean
        get() = _supportsAudio

    private var loadedAccelerator: HardwareAccelerationMode? = null
    private var currentBackendLabel: String = "CPU"
    private var systemMessage: String? = null

    /**
     * Sampling options the live [conversation] was created with. The native
     * [Conversation] owns the KV cache, so it is reused across turns and only
     * rebuilt when these change (or on [resetConversation] / system-message
     * change).
     */
    private data class SamplerParams(
        val temperature: Float,
        val topK: Int,
        val topP: Float,
    )
    @Volatile
    private var currentSamplerParams: SamplerParams? = null

    // --- Loading ------------------------------------------------------------

    override suspend fun loadModel(
        modelPath: String,
        config: InferenceConfig
    ): Result<Unit> {
        // No LoadOptions: infer the accelerator from the GPU-layers hint.
        val accel = if (config.nGpuLayers > 0) {
            HardwareAccelerationMode.GPU
        } else {
            HardwareAccelerationMode.CPU
        }
        return loadInternal(modelPath, config, accel, enableVision = false, enableAudio = false)
    }

    /**
     * Capability-aware load: honors [LoadOptions.accelerationMode] for the
     * native accelerator selection.
     */
    override suspend fun loadModel(
        modelPath: String,
        config: InferenceConfig,
        options: LoadOptions,
    ): Result<Unit> {
        loadedAccelerator = options.accelerationMode
        return loadInternal(
            modelPath,
            config,
            options.accelerationMode,
            enableVision = false,
            enableAudio = false,
        )
    }

    override suspend fun loadVisionModel(
        modelPath: String,
        mmprojPath: String,
        config: InferenceConfig
    ): Result<Unit> {
        // LiteRT-LM `.litertlm` models are single-file; no separate projector.
        val accel = if (config.nGpuLayers > 0) {
            HardwareAccelerationMode.GPU
        } else {
            HardwareAccelerationMode.CPU
        }
        return loadInternal(modelPath, config, accel, enableVision = true, enableAudio = false)
    }

    /**
     * Load a model with audio support (for the Audio Scribe feature).
     */
    suspend fun loadAudioModel(
        modelPath: String,
        config: InferenceConfig
    ): Result<Unit> {
        val accel = if (config.nGpuLayers > 0) {
            HardwareAccelerationMode.GPU
        } else {
            HardwareAccelerationMode.CPU
        }
        return loadInternal(modelPath, config, accel, enableVision = false, enableAudio = true)
    }

    /**
     * Load a multimodal model with vision and/or audio support.
     */
    suspend fun loadMultimodalModel(
        modelPath: String,
        config: InferenceConfig,
        enableVision: Boolean = true,
        enableAudio: Boolean = true
    ): Result<Unit> {
        val accel = if (config.nGpuLayers > 0) {
            HardwareAccelerationMode.GPU
        } else {
            HardwareAccelerationMode.CPU
        }
        return loadInternal(modelPath, config, accel, enableVision, enableAudio)
    }

    private suspend fun loadInternal(
        modelPath: String,
        config: InferenceConfig,
        accel: HardwareAccelerationMode,
        enableVision: Boolean,
        enableAudio: Boolean,
    ): Result<Unit> = withContext(Dispatchers.IO + InferenceThreadDiscipline.inferencePriority()) {
        // Engine created by this attempt but not yet owned by [engine]; closed
        // in the failure path so a failed initialize() cannot leak its native
        // allocations (mapped model file, partial backend state).
        var pendingEngine: Engine? = null
        try {
            unloadModel()

            val modelFile = File(modelPath)
            if (!modelFile.exists()) {
                return@withContext Result.failure(
                    IllegalArgumentException("Model file not found: $modelPath")
                )
            }

            val backend: Backend =
                if (accel == HardwareAccelerationMode.CPU) Backend.CPU() else Backend.GPU()
            currentBackendLabel = if (accel == HardwareAccelerationMode.CPU) "CPU" else "GPU"

            Log.w(TAG, "GPUDIAG LiteRT.loadInternal: accel=$accel, backend=$currentBackendLabel")
            Log.i(TAG, "Loading LiteRT-LM model: $modelPath (backend=$currentBackendLabel)")

            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = backend,
                visionBackend = if (enableVision) backend else null,
                // GPU audio compute ops are unsupported on most Android devices; always use CPU.
                audioBackend = if (enableAudio) Backend.CPU() else null,
                maxNumTokens = if (config.contextSize > 0) config.contextSize else null,
                cacheDir = context.cacheDir.absolutePath,
            )

            var newEngine = Engine(engineConfig)
            pendingEngine = newEngine
            var effectiveAccel = accel
            try {
                newEngine.initialize() // Heavy (~10s); already on Dispatchers.IO.
            } catch (gpuEx: Throwable) {
                if (accel != HardwareAccelerationMode.GPU) throw gpuEx
                Log.w(TAG, "GPU backend failed for ${modelFile.name}, retrying with CPU: ${gpuEx.message}")
                // Release native resources from the failed GPU engine before creating a new one.
                pendingEngine = null
                try { newEngine.close() } catch (closeEx: Exception) {
                    Log.w(TAG, "Error closing failed GPU engine: ${closeEx.message}")
                }
                effectiveAccel = HardwareAccelerationMode.CPU
                currentBackendLabel = "CPU-fallback"
                val cpuConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU(),
                    visionBackend = if (enableVision) Backend.CPU() else null,
                    audioBackend = if (enableAudio) Backend.CPU() else null,
                    maxNumTokens = if (config.contextSize > 0) config.contextSize else null,
                    cacheDir = context.cacheDir.absolutePath,
                )
                newEngine = Engine(cpuConfig)
                pendingEngine = newEngine
                newEngine.initialize()
            }

            engine = newEngine
            pendingEngine = null
            this@LiteRTEngine.modelPath = modelPath
            this@LiteRTEngine.modelName = modelFile.nameWithoutExtension
            loadedAccelerator = effectiveAccel
            loadedContextSize = if (config.contextSize > 0) config.contextSize else 4096
            _supportsVision = enableVision
            _supportsAudio = enableAudio
            _isModelLoaded = true
            currentSamplerParams = null
            conversation = null

            Log.i(TAG, "LiteRT-LM model loaded: ${modelFile.name}")
            Result.success(Unit)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load LiteRT-LM model", e)
            try {
                pendingEngine?.close()
            } catch (closeEx: Throwable) {
                Log.w(TAG, "Error closing engine after failed load: ${closeEx.message}")
            }
            engine = null
            _isModelLoaded = false
            Result.failure(
                IllegalStateException(e.message ?: "Failed to load LiteRT-LM model", e)
            )
        }
    }

    // --- Conversation management -------------------------------------------

    /**
     * Ensure a [Conversation] exists whose sampling options match [params].
     * The native conversation owns the KV cache, so it is reused across turns
     * and only rebuilt when sampling options change.
     */
    private fun ensureConversation(params: GenerationParams): Conversation? {
        val eng = engine ?: return null
        val sampler = SamplerParams(
            temperature = params.temperature,
            topK = params.topK.coerceAtLeast(1),
            topP = params.topP,
        )
        val existing = conversation
        if (existing != null && currentSamplerParams == sampler) {
            return existing
        }
        if (existing != null) {
            try {
                existing.close()
            } catch (e: Throwable) {
                Log.w(TAG, "Error closing stale conversation", e)
            }
        }
        return try {
            val sys = systemMessage
            val conversationConfig = if (sys.isNullOrBlank()) {
                ConversationConfig(
                    samplerConfig = SamplerConfig(
                        topK = sampler.topK,
                        topP = sampler.topP.toDouble(),
                        temperature = sampler.temperature.toDouble(),
                    ),
                )
            } else {
                ConversationConfig(
                    systemInstruction = Contents.of(sys),
                    samplerConfig = SamplerConfig(
                        topK = sampler.topK,
                        topP = sampler.topP.toDouble(),
                        temperature = sampler.temperature.toDouble(),
                    ),
                )
            }
            val fresh = eng.createConversation(conversationConfig)
            conversation = fresh
            currentSamplerParams = sampler
            fresh
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to create conversation", e)
            conversation = null
            currentSamplerParams = null
            null
        }
    }

    fun setSystemMessage(message: String?) {
        if (systemMessage != message) {
            systemMessage = message
            // Drop the conversation so the next call picks up the new system
            // instruction.
            try {
                conversation?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing conversation after system-message change", e)
            }
            conversation = null
            currentSamplerParams = null
        }
    }

    override suspend fun resetConversation() {
        try {
            conversation?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing conversation during reset", e)
        }
        conversation = null
        currentSamplerParams = null
    }

    override fun unloadModel() {
        try {
            conversation?.close()
        } catch (e: Throwable) {
            Log.w(TAG, "Error closing conversation during unload", e)
        }
        conversation = null
        currentSamplerParams = null
        try {
            engine?.close()
        } catch (e: Throwable) {
            Log.w(TAG, "Error closing engine during unload", e)
        }
        engine = null
        modelPath = null
        modelName = null
        loadedAccelerator = null
        loadedContextSize = 0
        _isModelLoaded = false
        _supportsVision = false
        _supportsAudio = false
        Log.d(TAG, "LiteRT-LM model unloaded")
    }

    // --- Generation ---------------------------------------------------------

    override suspend fun generate(
        prompt: String,
        params: GenerationParams
    ): String = withContext(Dispatchers.IO + InferenceThreadDiscipline.inferencePriority()) {
        val conv = ensureConversation(params)
            ?: throw IllegalStateException("No model loaded")
        _isGenerating = true
        try {
            textOf(conv.sendMessage(prompt, thinkingContext(params)))
        } catch (e: Throwable) {
            throw IllegalStateException("Generation failed: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            _isGenerating = false
        }
    }

    override suspend fun generateStream(
        prompt: String,
        callback: StreamCallback,
        params: GenerationParams
    ): Boolean = withContext(Dispatchers.IO + InferenceThreadDiscipline.inferencePriority()) {
        val conv = ensureConversation(params)
        if (conv == null) {
            callback.onError("No model loaded")
            return@withContext false
        }
        val extraContext = thinkingContext(params)
        runStreaming(callback, params.enableThinking) { mc ->
            conv.sendMessageAsync(prompt, mc, extraContext)
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
        val conv = ensureConversation(params)
        if (conv == null) {
            callback.onError("No model loaded")
            return@withContext false
        }
        val message = Message.user(
            Contents.of(Content.ImageFile(imagePath), Content.Text(prompt))
        )
        val extraContext = thinkingContext(params)
        runStreaming(callback, params.enableThinking) { mc ->
            conv.sendMessageAsync(message, mc, extraContext)
        }
    }

    /**
     * Generate with audio input (for audio transcription / translation).
     */
    suspend fun generateWithAudio(
        prompt: String,
        audioBytes: ByteArray,
        callback: StreamCallback,
        params: GenerationParams
    ): Boolean = withContext(Dispatchers.IO + InferenceThreadDiscipline.inferencePriority()) {
        if (!_supportsAudio) {
            callback.onError("Audio is not supported for this model")
            return@withContext false
        }
        val conv = ensureConversation(params)
        if (conv == null) {
            callback.onError("No model loaded")
            return@withContext false
        }
        val message = Message.user(
            Contents.of(Content.AudioBytes(audioBytes), Content.Text(prompt))
        )
        val extraContext = thinkingContext(params)
        runStreaming(callback, params.enableThinking) { mc ->
            conv.sendMessageAsync(message, mc, extraContext)
        }
    }

    /**
     * Extra-context map handed to the native `sendMessageAsync` call. The
     * LiteRT-LM runtime reads `enable_thinking` to switch reasoning models
     * between chain-of-thought and direct-answer modes.
     */
    private fun thinkingContext(params: GenerationParams): Map<String, String> =
        mapOf("enable_thinking" to params.enableThinking.toString())

    /**
     * Bridge the native [MessageCallback] streaming API onto QuantLM's
     * [StreamCallback] as a cancellable suspending call. The coroutine suspends
     * — freeing its thread — until the native runtime reports completion. If
     * the coroutine is cancelled (e.g. the consumer navigates away), native
     * generation is cancelled via [Conversation.cancelProcess].
     */
    private suspend fun runStreaming(
        callback: StreamCallback,
        enableThinking: Boolean,
        start: (MessageCallback) -> Unit,
    ): Boolean {
        _isGenerating = true
        return try {
            withTimeoutOrNull(STREAM_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    // onDone and onError may race; only the first resumes.
                    val resumed = AtomicBoolean(false)
                    // Reasoning models deliver chain-of-thought on the separate
                    // `thought` channel. Synthesize inline <think>…</think> tags
                    // around it so the rest of the pipeline (parseThinkingStream)
                    // sees the single internal format.
                    var thinkingOpen = false
                    var thinkingClosed = false
                    val nativeCallback = object : MessageCallback {
                        override fun onMessage(message: Message) {
                            if (enableThinking && !thinkingClosed) {
                                val thought = message.channels["thought"]
                                if (!thought.isNullOrEmpty()) {
                                    if (!thinkingOpen) {
                                        callback.onToken("<think>")
                                        thinkingOpen = true
                                    }
                                    callback.onToken(thought)
                                }
                            }
                            val text = textOf(message)
                            if (text.isNotEmpty()) {
                                if (thinkingOpen && !thinkingClosed) {
                                    callback.onToken("</think>")
                                    thinkingClosed = true
                                }
                                callback.onToken(text)
                            }
                        }

                        override fun onDone() {
                            if (resumed.compareAndSet(false, true)) {
                                if (thinkingOpen && !thinkingClosed) {
                                    callback.onToken("</think>")
                                    thinkingClosed = true
                                }
                                callback.onComplete()
                                cont.resume(true)
                            }
                        }

                        override fun onError(throwable: Throwable) {
                            if (resumed.compareAndSet(false, true)) {
                                callback.onError(throwable.message ?: "LiteRT-LM generation error")
                                cont.resume(false)
                            }
                        }
                    }
                    cont.invokeOnCancellation {
                        try {
                            conversation?.cancelProcess()
                        } catch (e: Throwable) {
                            Log.w(TAG, "Error cancelling generation on coroutine cancellation", e)
                        }
                    }
                    try {
                        start(nativeCallback)
                    } catch (e: Throwable) {
                        if (resumed.compareAndSet(false, true)) {
                            Log.e(TAG, "LiteRT-LM streaming failed to start", e)
                            callback.onError(e.message ?: "LiteRT-LM generation error")
                            cont.resume(false)
                        }
                    }
                }
            } ?: run {
                // Timed out: withTimeoutOrNull cancelled the coroutine above,
                // which already triggered cancelProcess() via invokeOnCancellation.
                Log.e(TAG, "LiteRT-LM generation timed out")
                callback.onError("Generation timed out")
                false
            }
        } finally {
            _isGenerating = false
        }
    }

    override fun stopGeneration() {
        _isGenerating = false
        try {
            conversation?.cancelProcess()
        } catch (e: Throwable) {
            Log.w(TAG, "Error cancelling generation", e)
        }
        Log.d(TAG, "Stop generation requested")
    }

    /** Concatenate the text [Content]s of a [Message]. */
    private fun textOf(message: Message): String =
        message.contents.contents
            .filterIsInstance<Content.Text>()
            .joinToString("") { it.text }

    // --- Metadata -----------------------------------------------------------

    override fun getModelInfo(): Map<String, String> {
        val info = mutableMapOf(
            "format" to "LiteRT-LM (.litertlm)",
            "engine" to "LiteRT-LM (native)",
            "status" to if (_isModelLoaded) "loaded" else "not loaded",
            "backend" to currentBackendLabel,
            "litertLmAvailable" to isLiteRtLmAvailable().toString(),
            "supportsVision" to _supportsVision.toString(),
            "supportsAudio" to _supportsAudio.toString(),
        )
        modelPath?.let {
            info["modelPath"] = it
            info["modelFile"] = File(it).name
        }
        return info
    }

    override fun canHandle(filePath: String): Boolean {
        return filePath.lowercase().endsWith(".litertlm")
    }

    /**
     * Honest active-backend label for the loaded model.
     */
    fun getActiveBackendLabel(): String {
        if (!_isModelLoaded) return "Unknown"
        return "LiteRT-LM ($currentBackendLabel)"
    }

    /** Get the loaded model name. */
    fun getLoadedModelName(): String? = modelName

    /** Check if vision is supported by the currently loaded model. */
    fun isVisionSupported(): Boolean = _supportsVision

    override fun getRuntimeCapabilities(): Set<ModelCapability> {
        if (!_isModelLoaded) return emptySet()
        return buildSet {
            if (_supportsVision) add(ModelCapability.VISION)
            if (_supportsAudio) add(ModelCapability.AUDIO)
            if (modelFilenameSuggestsReasoning()) add(ModelCapability.REASONING)
        }
    }

    /**
     * The LiteRT-LM runtime exposes no reasoning-capability introspection, so
     * REASONING is inferred from the model filename. This provides reasoning-UI
     * activation for user-imported `.litertlm` models that are not in the
     * [com.quantlm.yaser.domain.model.AvailableModels] manifest.
     */
    private fun modelFilenameSuggestsReasoning(): Boolean {
        val name = (modelName ?: modelPath?.let { File(it).name } ?: "").lowercase()
        return name.contains("deepseek-r1") ||
            name.contains("smollm3") ||
            name.contains("gemma-4")
    }
}
