package com.quantlm.yaser.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.quantlm.yaser.data.diagnostics.AppEventLogger
import com.quantlm.yaser.data.diagnostics.PerformanceSnapshotLogger
import com.quantlm.yaser.data.inference.LiteRTEngine
import com.quantlm.yaser.data.inference.LlamaEngine
import com.quantlm.yaser.data.inference.MediaPipeEngine
import com.quantlm.yaser.domain.inference.GenerationParams
import com.quantlm.yaser.domain.inference.SamplingCapabilities
import com.quantlm.yaser.domain.inference.SamplingParam
import com.quantlm.yaser.domain.model.AvailableModels
import com.quantlm.yaser.domain.model.CapabilityResolver
import com.quantlm.yaser.domain.model.GenerationState
import com.quantlm.yaser.domain.model.ModelCapability
import com.quantlm.yaser.domain.repository.InferenceRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Inference repository implementation that routes to the appropriate engine.
 * 
 * Engine routing: dispatches to whichever engine currently has a model
 * loaded — LlamaEngine (GGUF / llama.cpp), LiteRTEngine (.litertlm / native
 * LiteRT-LM) or MediaPipeEngine (.task / MediaPipe GenAI).
 */
@Singleton
class InferenceRepositoryImpl @Inject constructor(
    private val llamaEngine: LlamaEngine,
    private val mediaPipeEngine: MediaPipeEngine,
    private val liteRTEngine: LiteRTEngine,
    @ApplicationContext private val context: Context,
) : InferenceRepository {

    companion object {
        private const val TAG = "InferenceRepo"
        // Streaming emit cadence. 250 ms was tight enough that the markdown /
        // thinking-block reparse inside the streaming bubble could not keep up,
        // showing as visible jank. 400 ms is the base case.
        //
        // Phase 1D: the cost of recomposing the streaming bubble plus any
        // surrounding LazyColumn items grows with text length. Past a few
        // hundred chars, 400 ms is no longer enough headroom and the main
        // thread starts skipping frames (visible as "Davey!" warnings in
        // logcat). Use a length-aware schedule that backs off as the reply
        // grows. Numbers chosen to keep perceived streaming smooth for
        // short answers while preventing the long-answer jank cliff.
        private const val STREAM_INTERVAL_SHORT_MS = 200L
        private const val STREAM_INTERVAL_MEDIUM_MS = 400L
        private const val STREAM_INTERVAL_LONG_MS = 600L
        private const val STREAM_LENGTH_MEDIUM = 200   // chars
        private const val STREAM_LENGTH_LONG = 800     // chars

        // Kept for legacy callers that pass a fixed interval (LlamaEngine's
        // own onToken path uses this — its emission rate is already throttled
        // by the JNI callback dispatch).
        private const val STREAM_PROGRESS_UPDATE_INTERVAL_MS = STREAM_INTERVAL_SHORT_MS

        /**
         * Safety cap on the inference wake lock if a release path is missed.
         * A generation that exceeds this is by definition hung and must be cancelled,
         * not propped up — the wake lock is the only thing keeping the CPU clocked.
         */
        private const val INFERENCE_WAKE_LOCK_TIMEOUT_MS = 5 * 60 * 1000L

        /**
         * Pick the inter-emit floor based on how much text has accumulated.
         * The render cost of the streaming bubble + surrounding LazyColumn
         * grows with text length, so the emit cadence has to grow with it.
         */
        internal fun pickStreamInterval(currentLength: Int): Long = when {
            currentLength < STREAM_LENGTH_MEDIUM -> STREAM_INTERVAL_SHORT_MS
            currentLength < STREAM_LENGTH_LONG -> STREAM_INTERVAL_MEDIUM_MS
            else -> STREAM_INTERVAL_LONG_MS
        }

        /**
         * Longest edge handed to the llama (GGUF) vision path. The JNI side
         * bilinear-resizes to 512 px regardless (MAX_IMAGE_DIMENSION in
         * llama_jni.cpp); 1024 keeps 2× headroom so the final downsample is
         * visually unchanged, while a 12 MP camera JPEG no longer has to be
         * decoded full-size by single-threaded stb_image in native code
         * (~0.3–1 s and ~36 MB of RGB on a low-end device).
         */
        private const val LLAMA_VISION_MAX_EDGE = 1024
    }

    /**
     * Held for the duration of any active generation so the CPU governor does
     * not down-clock between layer computations. Non-reference-counted so a
     * missed release cannot wedge it on; the acquire timeout is a final
     * backstop.
     */
    private val inferenceWakeLock: PowerManager.WakeLock by lazy {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QuantLM::Inference")
            .apply { setReferenceCounted(false) }
    }

    private fun acquireInferenceWakeLock() {
        try {
            if (!inferenceWakeLock.isHeld) {
                inferenceWakeLock.acquire(INFERENCE_WAKE_LOCK_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire inference wake lock", e)
        }
    }

    private fun releaseInferenceWakeLock() {
        try {
            if (inferenceWakeLock.isHeld) inferenceWakeLock.release()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release inference wake lock", e)
        }
    }

    private inline fun <T> withInferenceWakeLock(block: () -> T): T {
        acquireInferenceWakeLock()
        return try {
            block()
        } finally {
            releaseInferenceWakeLock()
        }
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

        /**
         * Append a token and report whether the accumulated text now contains
         * any stop sequence. On a hit the buffer is truncated at the stop
         * boundary — the stop string and everything after it are dropped.
         *
         * Scanning the whole buffer (not just the latest token) handles a stop
         * string that arrives split across multiple tokens.
         */
        fun appendAndCheckStop(token: String, stopSequences: List<String>): Boolean {
            synchronized(lock) {
                fullText.append(token)
                if (stopSequences.isEmpty()) return false
                for (stop in stopSequences) {
                    if (stop.isEmpty()) continue
                    val idx = fullText.indexOf(stop)
                    if (idx >= 0) {
                        fullText.setLength(idx)
                        return true
                    }
                }
                return false
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

        /**
         * Phase 1D: variant that derives the inter-emit floor from the current
         * accumulator length via [pickStreamInterval]. Use this from any path
         * where the engine fires raw tokens at native speed (MediaPipe,
         * LiteRT). LlamaEngine's onToken path already throttles in the JNI
         * callback and can keep using the fixed-interval overload.
         */
        fun emitIfDueAdaptive(
            force: Boolean = false,
            emit: (String) -> Unit
        ) {
            val now = SystemClock.elapsedRealtime()
            val snapshot: String? = synchronized(lock) {
                val currentLength = fullText.length
                val minIntervalMs = pickStreamInterval(currentLength)
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
     * Build a stream callback that enforces [stopSequences] for engines that do
     * not honor them natively (LiteRT, MediaPipe). When accumulated output hits
     * a stop sequence the buffer is trimmed at the boundary, native generation
     * is asked to halt via [onStopRequested], and the flow is completed.
     *
     * LlamaEngine enforces stop sequences in its native layer, so its
     * repository branches keep a plain accumulator and must NOT use this
     * callback — doing so would double-trim and could truncate output a token
     * early.
     */
    private fun ProducerScope<GenerationState>.stopAwareStreamCallback(
        streamProgress: StreamProgressAccumulator,
        stopSequences: List<String>,
        onStopRequested: () -> Unit,
    ): LlamaEngine.StreamCallback = object : LlamaEngine.StreamCallback {
        override fun onToken(token: String) {
            val stopHit = streamProgress.appendAndCheckStop(token, stopSequences)
            // Phase 1D: adaptive cadence — short answers stream fluidly at
            // 400 ms; longer ones back off to keep the UI thread alive.
            streamProgress.emitIfDueAdaptive(force = stopHit) { partialText ->
                trySend(GenerationState.Generating(partialText))
            }
            if (stopHit) {
                onStopRequested()
                trySend(GenerationState.Complete(streamProgress.snapshot()))
                close()
            }
        }

        override fun onComplete() {
            streamProgress.emitIfDueAdaptive(force = true) { partialText ->
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

    /**
     * Pre-downscale an image for the llama vision path using the platform's
     * hardware-accelerated JPEG decode (inSampleSize), mirroring what
     * MediaPipeEngine.decodeDownsampledBitmap already does for `.task` models.
     * Returns a cache temp file the CALLER must delete, or null when the image
     * is already small enough or cannot be decoded — in which case the
     * original path is used, exactly as before.
     */
    private suspend fun predownscaleForLlamaVision(imagePath: String): java.io.File? =
        withContext(Dispatchers.IO) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(imagePath, bounds)
                val longest = maxOf(bounds.outWidth, bounds.outHeight)
                if (longest <= LLAMA_VISION_MAX_EDGE) return@withContext null
                // Power-of-two sampling that never undershoots the target edge:
                // result is in [LLAMA_VISION_MAX_EDGE, 2*LLAMA_VISION_MAX_EDGE).
                var sampleSize = 1
                while (longest / (sampleSize * 2) >= LLAMA_VISION_MAX_EDGE) sampleSize *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                val bitmap = BitmapFactory.decodeFile(imagePath, opts) ?: return@withContext null
                val temp = java.io.File.createTempFile("llama_vision_", ".jpg", context.cacheDir)
                try {
                    java.io.FileOutputStream(temp).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                } finally {
                    bitmap.recycle()
                }
                AppEventLogger.debug(TAG, "vision_predownscale", "src=${bounds.outWidth}x${bounds.outHeight}, sample=$sampleSize")
                temp
            } catch (e: Exception) {
                // OutOfMemory on a huge source decodes as a Throwable, not
                // Exception — catch broadly so we always degrade to the original
                // rather than crashing the whole inference flow.
                AppEventLogger.error(TAG, "vision_predownscale_failed", "path=$imagePath", e)
                null
            } catch (t: Throwable) {
                AppEventLogger.error(TAG, "vision_predownscale_oom", "path=$imagePath", t)
                null
            }
        }

    /** Non-suspending stop of whichever engine currently has a model loaded. */
    private fun stopActiveEngine() {
        when {
            isLlamaActive() -> llamaEngine.stopGeneration()
            isLiteRTActive() -> liteRTEngine.stopGeneration()
            isMediaPipeActive() -> mediaPipeEngine.stopGeneration()
        }
    }

    /**
     * Determine which engine is currently active (has a model loaded)
     */
    private fun isLlamaActive(): Boolean = llamaEngine.isLoaded()
    private fun isLiteRTActive(): Boolean = liteRTEngine.isModelLoaded
    private fun isMediaPipeActive(): Boolean = mediaPipeEngine.isModelLoaded

    override fun getActiveCapabilities(): Set<ModelCapability> {
        val engineCaps = when {
            isLlamaActive() -> llamaEngine.getRuntimeCapabilities()
            isLiteRTActive() -> liteRTEngine.getRuntimeCapabilities()
            isMediaPipeActive() -> mediaPipeEngine.getRuntimeCapabilities()
            else -> return emptySet()
        }
        val loadedName = when {
            isLlamaActive() -> llamaEngine.getLoadedModelName()
            isLiteRTActive() -> liteRTEngine.getLoadedModelName()
            isMediaPipeActive() -> mediaPipeEngine.getLoadedModelName()
            else -> null
        } ?: return engineCaps
        val manifestEntry = AvailableModels.getAllModels().firstOrNull {
            // Match against either the bare file name or the registered name.
            it.fileName.equals(loadedName, ignoreCase = true) ||
                it.fileName.substringBeforeLast('.').equals(loadedName, ignoreCase = true) ||
                it.name.equals(loadedName, ignoreCase = true)
        }
        // Merge engine introspection with the manifest via the single shared
        // resolution policy (VISION/AUDIO intersected, REASONING unioned).
        return CapabilityResolver.resolve(engineCaps, manifestEntry?.capabilities)
    }

    override suspend fun resetConversation() {
        when {
            isLlamaActive() -> llamaEngine.resetConversation()
            isLiteRTActive() -> liteRTEngine.resetConversation()
            isMediaPipeActive() -> mediaPipeEngine.resetConversation()
        }
    }
    
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
        mirostatEta: Float,
        stopSequences: List<String>
    ): Result<String> = withInferenceWakeLock {
        when {
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
            isLiteRTActive() -> {
                Log.d(TAG, "Generating with LiteRTEngine")
                AppEventLogger.debug(component = TAG, action = "generate_route", details = "engine=LITERT")
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
                        mirostatEta = mirostatEta,
                        stopSequences = stopSequences
                    )
                    Result.success(liteRTEngine.generate(prompt, params))
                } catch (e: Exception) {
                    AppEventLogger.error(
                        component = TAG,
                        action = "generate_failed",
                        details = "engine=LITERT, reason=${e.message ?: "unknown"}",
                        throwable = e
                    )
                    Result.failure(e)
                }
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
                        mirostatEta = mirostatEta,
                        stopSequences = stopSequences
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
        mirostatEta: Float,
        stopSequences: List<String>,
        reasoningEnabled: Boolean
    ): Flow<GenerationState> = callbackFlow {

        trySend(GenerationState.Loading)
        acquireInferenceWakeLock()
        val perfReason = "generate:${getLoadedModelName() ?: "unknown"}"
        PerformanceSnapshotLogger.begin(perfReason)

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
                            // Phase 1D: adaptive emit cadence — see companion-
                            // object comment on STREAM_INTERVAL_*.
                            streamProgress.emitIfDueAdaptive { partialText ->
                                trySend(GenerationState.Generating(partialText))
                            }
                        },
                        onComplete = {
                            streamProgress.emitIfDueAdaptive(force = true) { partialText ->
                                trySend(GenerationState.Generating(partialText))
                            }
                            trySend(GenerationState.Complete(streamProgress.snapshot()))
                            close()
                        }
                    )
                }
                isLiteRTActive() -> {
                    Log.d(TAG, "Streaming with LiteRTEngine")
                    AppEventLogger.debug(component = TAG, action = "stream_route", details = "engine=LITERT")
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
                        mirostatEta = mirostatEta,
                        stopSequences = stopSequences,
                        enableThinking = reasoningEnabled
                    )
                    val callback = stopAwareStreamCallback(streamProgress, stopSequences) {
                        liteRTEngine.stopGeneration()
                    }
                    liteRTEngine.generateStream(prompt, callback, params)
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
                        mirostatEta = mirostatEta,
                        stopSequences = stopSequences,
                        enableThinking = reasoningEnabled
                    )
                    val callback = stopAwareStreamCallback(streamProgress, stopSequences) {
                        mediaPipeEngine.stopGeneration()
                    }
                    mediaPipeEngine.generateStream(prompt, callback, params)
                }
                else -> {
                    AppEventLogger.warn(component = TAG, action = "stream_blocked_no_model")
                    trySend(GenerationState.Error("No model loaded"))
                    close()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Throwable: native engines can surface Errors (OOM, JNI aborts that
            // become Errors). Letting one escape the callbackFlow body would
            // crash the process instead of ending the stream with an error.
            Log.e(TAG, "Generation error", e)
            AppEventLogger.error(
                component = TAG,
                action = "stream_failed",
                details = "reason=${e.message ?: "unknown"}",
                throwable = e
            )
            trySend(GenerationState.Error(e.message ?: "Unknown error"))
            close()
        }

        awaitClose {
            // Flow closed or cancelled (e.g. the consumer navigated away):
            // make sure native generation is not left running. Safe after
            // normal completion too — stopGeneration() is a no-op once the
            // engine has already finished.
            stopActiveEngine()
            releaseInferenceWakeLock()
            PerformanceSnapshotLogger.end(perfReason)
        }
    }

    override suspend fun stopGeneration() {
        AppEventLogger.info(component = TAG, action = "stop_generation_requested")
        stopActiveEngine()
        // 2F.1: synchronously close any open perf-sampling spans. The
        // callbackFlow's awaitClose runs eventually, but if the user
        // immediately starts a new generation the second begin() lands while
        // the old end() hasn't fired yet, producing activeCount=2 and a
        // sampler that never quiesces. Force it to 0 here so the next
        // generation starts clean.
        PerformanceSnapshotLogger.endAll(reason = "stop_generation")
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
            isLiteRTActive() -> liteRTEngine.getLoadedModelName()
            isMediaPipeActive() -> mediaPipeEngine.getLoadedModelName()
            else -> null
        }
    }

    override fun getActiveBackendLabel(): String {
        return when {
            isLlamaActive() -> llamaEngine.getActiveBackendLabel()
            isLiteRTActive() -> liteRTEngine.getActiveBackendLabel()
            isMediaPipeActive() -> mediaPipeEngine.getActiveBackendLabel()
            else -> "Unknown"
        }
    }

    override fun getActiveModelFormatLabel(): String {
        return when {
            isLlamaActive() -> "GGUF"
            isLiteRTActive() -> "LiteRT-LM"
            isMediaPipeActive() -> "TASK"
            else -> "Unknown"
        }
    }

    override fun getActiveContextTokens(): Int {
        return when {
            isLlamaActive() -> llamaEngine.getLoadedContextSize()
            isLiteRTActive() -> liteRTEngine.getLoadedContextSize()
            isMediaPipeActive() -> mediaPipeEngine.getLoadedContextSize()
            else -> 0
        }
    }

    override fun getActiveSamplingCapabilities(): Set<SamplingParam> {
        return when {
            isLlamaActive() -> llamaEngine.samplingCapabilities
            isLiteRTActive() -> liteRTEngine.samplingCapabilities
            isMediaPipeActive() -> mediaPipeEngine.samplingCapabilities
            else -> SamplingCapabilities.FULL
        }
    }

    override fun isVisionSupported(): Boolean {
        return when {
            isLlamaActive() -> llamaEngine.isVisionSupported()
            isLiteRTActive() -> liteRTEngine.isVisionSupported()
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
        mirostatEta: Float,
        stopSequences: List<String>
    ): Result<String> = withInferenceWakeLock {
        when {
            isLlamaActive() && llamaEngine.isVisionSupported() -> {
                Log.d(TAG, "Generating with image using LlamaEngine")
                val scaledImage = predownscaleForLlamaVision(imagePath)
                val result = llamaEngine.generateWithImage(
                    prompt = prompt,
                    imagePath = scaledImage?.absolutePath ?: imagePath,
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
                // Engine returns a Result (never throws), so plain cleanup is safe.
                scaledImage?.delete()
                result
            }
            isLiteRTActive() && liteRTEngine.isVisionSupported() -> {
                Log.d(TAG, "Generating with image using LiteRTEngine")
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
                        mirostatEta = mirostatEta,
                        stopSequences = stopSequences
                    )
                    val resultBuilder = StringBuilder()
                    val callback = object : LlamaEngine.StreamCallback {
                        override fun onToken(token: String) {
                            resultBuilder.append(token)
                        }
                        override fun onComplete() {}
                        override fun onError(message: String) {}
                    }
                    liteRTEngine.generateWithImage(prompt, imagePath, callback, params)
                    Result.success(resultBuilder.toString())
                } catch (e: Exception) {
                    Result.failure(e)
                }
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
                        mirostatEta = mirostatEta,
                        stopSequences = stopSequences
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
        mirostatEta: Float,
        stopSequences: List<String>,
        reasoningEnabled: Boolean
    ): Flow<GenerationState> = callbackFlow {

        trySend(GenerationState.Loading)
        acquireInferenceWakeLock()
        val perfReason = "generate_image:${getLoadedModelName() ?: "unknown"}"
        PerformanceSnapshotLogger.begin(perfReason)

        val streamProgress = StreamProgressAccumulator()
        // Temp file from the llama-path pre-downscale; deleted in awaitClose.
        var llamaVisionTemp: java.io.File? = null

        try {
            when {
                isLlamaActive() && llamaEngine.isVisionSupported() -> {
                    Log.d(TAG, "Streaming with image using LlamaEngine")
                    val scaledImage = predownscaleForLlamaVision(imagePath)
                    llamaVisionTemp = scaledImage
                    llamaEngine.generateStreamWithImage(
                        prompt = prompt,
                        imagePath = scaledImage?.absolutePath ?: imagePath,
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
                            // Phase 1D: adaptive emit cadence (vision branch).
                            streamProgress.emitIfDueAdaptive { partialText ->
                                trySend(GenerationState.Generating(partialText))
                            }
                        },
                        onComplete = {
                            streamProgress.emitIfDueAdaptive(force = true) { partialText ->
                                trySend(GenerationState.Generating(partialText))
                            }
                            trySend(GenerationState.Complete(streamProgress.snapshot()))
                            close()
                        }
                    )
                }
                isLiteRTActive() && liteRTEngine.isVisionSupported() -> {
                    Log.d(TAG, "Streaming with image using LiteRTEngine")
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
                        mirostatEta = mirostatEta,
                        stopSequences = stopSequences,
                        enableThinking = reasoningEnabled
                    )
                    val callback = stopAwareStreamCallback(streamProgress, stopSequences) {
                        liteRTEngine.stopGeneration()
                    }
                    liteRTEngine.generateWithImage(prompt, imagePath, callback, params)
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
                        mirostatEta = mirostatEta,
                        stopSequences = stopSequences,
                        enableThinking = reasoningEnabled
                    )
                    val callback = stopAwareStreamCallback(streamProgress, stopSequences) {
                        mediaPipeEngine.stopGeneration()
                    }
                    mediaPipeEngine.generateWithImage(prompt, imagePath, callback, params)
                }
                else -> {
                    trySend(GenerationState.Error("Vision not supported for current model"))
                    close()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Vision generation error", e)
            trySend(GenerationState.Error(e.message ?: "Unknown error"))
            close()
        }

        awaitClose {
            // Flow closed or cancelled (e.g. the consumer navigated away):
            // make sure native generation is not left running. Safe after
            // normal completion too — stopGeneration() is a no-op once the
            // engine has already finished.
            stopActiveEngine()
            releaseInferenceWakeLock()
            llamaVisionTemp?.delete()
            PerformanceSnapshotLogger.end(perfReason)
        }
    }

    override fun isAudioSupported(): Boolean = isLiteRTActive() && liteRTEngine.supportsAudio

    override fun generateStreamWithAudio(
        prompt: String,
        audioPath: String,
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
        mirostatEta: Float,
        stopSequences: List<String>,
        reasoningEnabled: Boolean
    ): Flow<GenerationState> = callbackFlow {

        trySend(GenerationState.Loading)
        acquireInferenceWakeLock()
        val perfReason = "generate_audio:${getLoadedModelName() ?: "unknown"}"
        PerformanceSnapshotLogger.begin(perfReason)

        val streamProgress = StreamProgressAccumulator()

        try {
            when {
                isLiteRTActive() && liteRTEngine.supportsAudio -> {
                    Log.d(TAG, "Streaming with audio using LiteRTEngine")
                    AppEventLogger.debug(component = TAG, action = "stream_route", details = "engine=LITERT, audio=true")
                    val audioFile = java.io.File(audioPath)
                    // The callbackFlow body runs in the collector's context
                    // (often Main) — keep the file read off that thread.
                    val audioBytes = withContext(Dispatchers.IO) {
                        if (audioFile.exists()) audioFile.readBytes() else null
                    }
                    if (audioBytes == null) {
                        trySend(GenerationState.Error("Audio file not found"))
                        close()
                        return@callbackFlow
                    }
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
                        mirostatEta = mirostatEta,
                        stopSequences = stopSequences,
                        enableThinking = reasoningEnabled
                    )
                    val callback = stopAwareStreamCallback(streamProgress, stopSequences) {
                        liteRTEngine.stopGeneration()
                    }
                    liteRTEngine.generateWithAudio(prompt, audioBytes, callback, params)
                }
                else -> {
                    trySend(GenerationState.Error("Audio not supported for current model"))
                    close()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Audio generation error", e)
            AppEventLogger.error(
                component = TAG,
                action = "stream_failed",
                details = "engine=LITERT, audio=true, reason=${e.message ?: "unknown"}",
                throwable = e
            )
            trySend(GenerationState.Error(e.message ?: "Unknown error"))
            close()
        }

        awaitClose {
            stopActiveEngine()
            releaseInferenceWakeLock()
            PerformanceSnapshotLogger.end(perfReason)
        }
    }

    /**
     * Get the name of the currently active engine
     */
    fun getActiveEngineName(): String {
        return when {
            isLlamaActive() -> "LlamaEngine (llama.cpp)"
            isLiteRTActive() -> "LiteRTEngine (LiteRT-LM)"
            isMediaPipeActive() -> "MediaPipeEngine"
            else -> "None"
        }
    }
}
