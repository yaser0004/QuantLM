package com.quantlm.yaser.domain.inference

/**
 * A sampling parameter an inference engine may or may not honor. Drives the
 * Settings UI so it does not present controls the active engine would silently
 * ignore (the [GenerationParams] surface is uniform, but the engines are not).
 */
enum class SamplingParam {
    TEMPERATURE,
    TOP_P,
    TOP_K,
    MIN_P,
    REPEAT_PENALTY,
    REPEAT_LAST_N,
    TFS_Z,
    TYPICAL_P,
    MIROSTAT,
    MAX_TOKENS,
    STOP_SEQUENCES,
}

/**
 * Predefined sampling-capability sets for QuantLM's engines.
 */
object SamplingCapabilities {

    /** llama.cpp exposes the full sampling surface. */
    val FULL: Set<SamplingParam> = SamplingParam.values().toSet()

    /**
     * The MediaPipe and LiteRT-LM SDKs expose only temperature / topK / topP.
     * `maxTokens` is honored at load time, and stop sequences are enforced by
     * the repository layer (see `InferenceRepositoryImpl`), so both are kept.
     */
    val BASIC: Set<SamplingParam> = setOf(
        SamplingParam.TEMPERATURE,
        SamplingParam.TOP_P,
        SamplingParam.TOP_K,
        SamplingParam.MAX_TOKENS,
        SamplingParam.STOP_SEQUENCES,
    )
}
