package com.quantlm.yaser.data.repository

import android.os.SystemClock
import android.util.Log
import com.quantlm.yaser.data.diagnostics.AppEventLogger
import com.quantlm.yaser.data.inference.LlamaEngine
import com.quantlm.yaser.data.inference.MediaPipeEngine
import com.quantlm.yaser.domain.inference.GenerationParams
import com.quantlm.yaser.domain.model.GenerationState
import com.quantlm.yaser.domain.repository.InferenceRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Inference repository implementation that routes to the appropriate engine.
 * 
 * Engine routing:
 * - Checks if LlamaEngine has a model loaded first
 * - Falls back to MediaPipeEngine if not
 * - Supports both GGUF (llama.cpp) and LiteRT (MediaPipe) models
 */
@Singleton
class InferenceRepositoryImpl @Inject constructor(
    private val llamaEngine: LlamaEngine,
    private val mediaPipeEngine: MediaPipeEngine
) : InferenceRepository {
    
    companion object {
        private const val TAG = "InferenceRepo"
        private const val STREAM_PROGRESS_UPDATE_INTERVAL_MS = 80L
    }

    private class StreamProgressAccumulator {
        private val lock = Any()
        private val fullText = StringBuilder()
        private var lastEmitAtMs: Long = 0L
        private var lastEmitLength: Int = 0

        fun append(token: String) {
            synchronized(lock) {
                fullText.append(token)
            }
        }

        fun snapshot(): String = synchronized(lock) {
            fullText.toString()
        }

        fun emitIfDue(
            minIntervalMs: Long,
            force: Boolean = false,
            emit: (String) -> Unit
        ) {
            val now = SystemClock.elapsedRealtime()
            val snapshot: String? = synchronized(lock) {
                val currentLength = fullText.length
                if (!force && (currentLength == lastEmitLength || now - lastEmitAtMs < minIntervalMs)) {
                    null
                } else {
                    lastEmitAtMs = now
                    lastEmitLength = currentLength
                    fullText.toString()
                }
            }

            snapshot?.let(emit)
        }
    }
    
    /**
     * Determine which engine is currently active (has a model loaded)
     */
    private fun isLlamaActive(): Boolean = llamaEngine.isLoaded()
    private fun isMediaPipeActive(): Boolean = mediaPipeEngine.isModelLoaded
    
    override suspend fun generate(
        prompt: String,
        temperature: Float,
        maxTokens: Int,
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
    ): Result<String> {
        return when {
            isLlamaActive() -> {
                Log.d(TAG, "Generating with LlamaEngine")
                AppEventLogger.debug(component = TAG, action = "generate_route", details = "engine=LLAMA")
                llamaEngine.generate(
                    prompt = prompt,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    topP = topP,
                    topK = topK,
                    repeatPenalty = repeatPenalty,
                    repeatLastN = repeatLastN,
                    minP = minP,
                    tfsZ = tfsZ,
                    typicalP = typicalP,
                    mirostat = mirostat,
                    mirostatTau = mirostatTau,
                    mirostatEta = mirostatEta
                )
            }
            isMediaPipeActive() -> {
                Log.d(TAG, "Generating with MediaPipeEngine")
                AppEventLogger.debug(component = TAG, action = "generate_route", details = "engine=MEDIAPIPE")
                try {
                    val params = GenerationParams(
                        maxTokens = maxTokens,
                        temperature = temperature,
                        topP = topP,
                        topK = topK,
                        repeatPenalty = repeatPenalty,
                        repeatLastN = repeatLastN,
                        minP = minP,
                        tfsZ = tfsZ,
                        typicalP = typicalP,
                        mirostat = mirostat,
                        mirostatTau = mirostatTau,
                        mirostatEta = mirostatEta
                    )
                    val result = mediaPipeEngine.generate(prompt, params)
                    Result.success(result)
                } catch (e: Exception) {
                    AppEventLogger.error(
                        component = TAG,
                        action = "generate_failed",
                        details = "engine=MEDIAPIPE, reason=${e.message ?: "unknown"}",
                        throwable = e
                    )
                    Result.failure(e)
                }
            }
            else -> {
                AppEventLogger.warn(component = TAG, action = "generate_blocked_no_model")
                Result.failure(Exception("No model loaded"))
            }
        }
    }
    
    override fun generateStream(
        prompt: String,
        temperature: Float,
        maxTokens: Int,
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
    ): Flow<GenerationState> = callbackFlow {
        
        trySend(GenerationState.Loading)
        
        val streamProgress = StreamProgressAccumulator()
        
        try {
            when {
                isLlamaActive() -> {
                    Log.d(TAG, "Streaming with LlamaEngine")
                    AppEventLogger.debug(component = TAG, action = "stream_route", details = "engine=LLAMA")
                    llamaEngine.generateStream(
                        prompt = prompt,
                        maxTokens = maxTokens,
                        temperature = temperature,
                        topP = topP,
                        topK = topK,
                        repeatPenalty = repeatPenalty,
                        repeatLastN = repeatLastN,
                        minP = minP,
                        tfsZ = tfsZ,
                        typicalP = typicalP,
                        mirostat = mirostat,
                        mirostatTau = mirostatTau,
                        mirostatEta = mirostatEta,
                        onToken = { token ->
                            streamProgress.append(token)
                            streamProgress.emitIfDue(STREAM_PROGRESS_UPDATE_INTERVAL_MS) { partialText ->
                                trySend(GenerationState.Generating(partialText))
                            }
                        },
                        onComplete = {
                            streamProgress.emitIfDue(
                                minIntervalMs = STREAM_PROGRESS_UPDATE_INTERVAL_MS,
                                force = true
                            ) { partialText ->
                                trySend(GenerationState.Generating(partialText))
                            }
                            trySend(GenerationState.Complete(streamProgress.snapshot()))
                            close()
                        }
                    )
                }
                isMediaPipeActive() -> {
                    Log.d(TAG, "Streaming with MediaPipeEngine")
                    AppEventLogger.debug(component = TAG, action = "stream_route", details = "engine=MEDIAPIPE")
                    val params = GenerationParams(
                        maxTokens = maxTokens,
                        temperature = temperature,
                        topP = topP,
                        topK = topK,
                        repeatPenalty = repeatPenalty,
                        repeatLastN = repeatLastN,
                        minP = minP,
                        tfsZ = tfsZ,
                        typicalP = typicalP,
                        mirostat = mirostat,
                        mirostatTau = mirostatTau,
                        mirostatEta = mirostatEta
                    )
                    val callback = object : LlamaEngine.StreamCallback {
                        override fun onToken(token: String) {
                            streamProgress.append(token)
                            streamProgress.emitIfDue(STREAM_PROGRESS_UPDATE_INTERVAL_MS) { partialText ->
                                trySend(GenerationState.Generating(partialText))
                            }
                        }
                        override fun onComplete() {
                            streamProgress.emitIfDue(
                                minIntervalMs = STREAM_PROGRESS_UPDATE_INTERVAL_MS,
                                force = true
                            ) { partialText ->
                                trySend(GenerationState.Generating(partialText))
                            }
                            trySend(GenerationState.Complete(streamProgress.snapshot()))
                            close()
                        }
                        override fun onError(message: String) {
                            trySend(GenerationState.Error(message))
                            close()
                        }
                    }
                    mediaPipeEngine.generateStream(prompt, callback, params)
                }
                else -> {
                    AppEventLogger.warn(component = TAG, action = "stream_blocked_no_model")
                    trySend(GenerationState.Error("No model loaded"))
                    close()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Generation error", e)
            AppEventLogger.error(
                component = TAG,
                action = "stream_failed",
                details = "reason=${e.message ?: "unknown"}",
                throwable = e
            )
            trySend(GenerationState.Error(e.message ?: "Unknown error"))
            close(e)
        }
        
        awaitClose {
            // Cleanup if needed
        }
    }
    
    override suspend fun stopGeneration() {
        AppEventLogger.info(component = TAG, action = "stop_generation_requested")
        when {
            isLlamaActive() -> llamaEngine.stopGeneration()
            isMediaPipeActive() -> mediaPipeEngine.stopGeneration()
        }
    }
    
    override fun getChatTemplate(): String? {
        // Only LlamaEngine has embedded chat templates
        return if (isLlamaActive()) {
            llamaEngine.getChatTemplate()
        } else {
            // MediaPipe handles its own prompt formatting via addQueryChunk().
            // Return null so SendMessageUseCase sends raw text without
            // chat template tokens (which MediaPipe would treat as literal text,
            // causing echoed responses and fake follow-up queries).
            null
        }
    }
    
    override fun getLoadedModelName(): String? {
        return when {
            isLlamaActive() -> llamaEngine.getLoadedModelName()
            isMediaPipeActive() -> mediaPipeEngine.getLoadedModelName()
            else -> null
        }
    }
    
    override fun isVisionSupported(): Boolean {
        return when {
            isLlamaActive() -> llamaEngine.isVisionSupported()
            isMediaPipeActive() -> mediaPipeEngine.supportsVision
            else -> false
        }
    }
    
    override suspend fun generateWithImage(
        prompt: String,
        imagePath: String,
        temperature: Float,
        maxTokens: Int,
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
    ): Result<String> {
        return when {
            isLlamaActive() && llamaEngine.isVisionSupported() -> {
                Log.d(TAG, "Generating with image using LlamaEngine")
                llamaEngine.generateWithImage(
                    prompt = prompt,
                    imagePath = imagePath,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    topP = topP,
                    topK = topK,
                    repeatPenalty = repeatPenalty,
                    repeatLastN = repeatLastN,
                    minP = minP,
                    tfsZ = tfsZ,
                    typicalP = typicalP,
                    mirostat = mirostat,
                    mirostatTau = mirostatTau,
                    mirostatEta = mirostatEta
                )
            }
            isMediaPipeActive() && mediaPipeEngine.supportsVision -> {
                Log.d(TAG, "Generating with image using MediaPipeEngine")
                try {
                    val params = GenerationParams(
                        maxTokens = maxTokens,
                        temperature = temperature,
                        topP = topP,
                        topK = topK,
                        repeatPenalty = repeatPenalty,
                        repeatLastN = repeatLastN,
                        minP = minP,
                        tfsZ = tfsZ,
                        typicalP = typicalP,
                        mirostat = mirostat,
                        mirostatTau = mirostatTau,
                        mirostatEta = mirostatEta
                    )
                    val resultBuilder = StringBuilder()
                    val callback = object : LlamaEngine.StreamCallback {
                        override fun onToken(token: String) {
                            resultBuilder.append(token)
                        }
                        override fun onComplete() {}
                        override fun onError(message: String) {}
                    }
                    mediaPipeEngine.generateWithImage(prompt, imagePath, callback, params)
                    Result.success(resultBuilder.toString())
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            else -> {
                Result.failure(Exception("Vision not supported for current model"))
            }
        }
    }
    
    override fun generateStreamWithImage(
        prompt: String,
        imagePath: String,
        temperature: Float,
        maxTokens: Int,
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
    ): Flow<GenerationState> = callbackFlow {
        
        trySend(GenerationState.Loading)
        
        val streamProgress = StreamProgressAccumulator()
        
        try {
            when {
                isLlamaActive() && llamaEngine.isVisionSupported() -> {
                    Log.d(TAG, "Streaming with image using LlamaEngine")
                    llamaEngine.generateStreamWithImage(
                        prompt = prompt,
                        imagePath = imagePath,
                        maxTokens = maxTokens,
                        temperature = temperature,
                        topP = topP,
                        topK = topK,
                        repeatPenalty = repeatPenalty,
                        repeatLastN = repeatLastN,
                        minP = minP,
                        tfsZ = tfsZ,
                        typicalP = typicalP,
                        mirostat = mirostat,
                        mirostatTau = mirostatTau,
                        mirostatEta = mirostatEta,
                        onToken = { token ->
                            streamProgress.append(token)
                            streamProgress.emitIfDue(STREAM_PROGRESS_UPDATE_INTERVAL_MS) { partialText ->
                                trySend(GenerationState.Generating(partialText))
                            }
                        },
                        onComplete = {
                            streamProgress.emitIfDue(
                                minIntervalMs = STREAM_PROGRESS_UPDATE_INTERVAL_MS,
                                force = true
                            ) { partialText ->
                                trySend(GenerationState.Generating(partialText))
                            }
                            trySend(GenerationState.Complete(streamProgress.snapshot()))
                            close()
                        }
                    )
                }
                isMediaPipeActive() && mediaPipeEngine.supportsVision -> {
                    Log.d(TAG, "Streaming with image using MediaPipeEngine")
                    val params = GenerationParams(
                        maxTokens = maxTokens,
                        temperature = temperature,
                        topP = topP,
                        topK = topK,
                        repeatPenalty = repeatPenalty,
                        repeatLastN = repeatLastN,
                        minP = minP,
                        tfsZ = tfsZ,
                        typicalP = typicalP,
                        mirostat = mirostat,
                        mirostatTau = mirostatTau,
                        mirostatEta = mirostatEta
                    )
                    val callback = object : LlamaEngine.StreamCallback {
                        override fun onToken(token: String) {
                            streamProgress.append(token)
                            streamProgress.emitIfDue(STREAM_PROGRESS_UPDATE_INTERVAL_MS) { partialText ->
                                trySend(GenerationState.Generating(partialText))
                            }
                        }
                        override fun onComplete() {
                            streamProgress.emitIfDue(
                                minIntervalMs = STREAM_PROGRESS_UPDATE_INTERVAL_MS,
                                force = true
                            ) { partialText ->
                                trySend(GenerationState.Generating(partialText))
                            }
                            trySend(GenerationState.Complete(streamProgress.snapshot()))
                            close()
                        }
                        override fun onError(message: String) {
                            trySend(GenerationState.Error(message))
                            close()
                        }
                    }
                    mediaPipeEngine.generateWithImage(prompt, imagePath, callback, params)
                }
                else -> {
                    trySend(GenerationState.Error("Vision not supported for current model"))
                    close()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vision generation error", e)
            trySend(GenerationState.Error(e.message ?: "Unknown error"))
            close(e)
        }
        
        awaitClose {
            // Cleanup if needed
        }
    }
    
    /**
     * Get the name of the currently active engine
     */
    fun getActiveEngineName(): String {
        return when {
            isLlamaActive() -> "LlamaEngine (llama.cpp)"
            isMediaPipeActive() -> "MediaPipeEngine"
            else -> "None"
        }
    }
}
