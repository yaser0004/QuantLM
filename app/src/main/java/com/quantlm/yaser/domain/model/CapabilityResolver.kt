package com.quantlm.yaser.domain.model

/**
 * Single point of truth for resolving a model's *effective* capabilities from
 * the two available sources:
 *
 *  - **engine introspection** — what the loaded engine reports at runtime via
 *    [com.quantlm.yaser.domain.inference.InferenceEngine.getRuntimeCapabilities]
 *    (native vision/audio support, chat-template analysis, filename heuristics).
 *  - **manifest declaration** — the capabilities declared for the model in
 *    [AvailableModels].
 *
 * This replaces the ad-hoc per-call merge logic that previously lived in the
 * repository, so capability behaviour is consistent across all engines.
 */
object CapabilityResolver {

    /**
     * Capabilities resolved by *union*: either a manifest declaration or engine
     * introspection is enough to enable them.
     *
     * REASONING qualifies because engine detection is heuristic — chat-template
     * `<think>` markers or filename matching. A union covers both manifest
     * models whose template lacks explicit markers and user-imported models
     * that are absent from the manifest.
     *
     * Every other capability (notably VISION and AUDIO) is resolved by
     * *intersection*: the model must declare it AND the active engine must be
     * able to deliver it. That intersection is what stops a stubbed modality
     * (e.g. MediaPipe's text-only vision path) from being advertised.
     */
    private val UNION_CAPS = setOf(ModelCapability.REASONING)

    /**
     * Resolve the effective capability set for the loaded model.
     *
     * @param engineCaps capabilities the active engine reports for the model.
     * @param manifestCaps capabilities declared in the manifest, or null when
     *   the loaded model has no manifest entry (e.g. user-imported).
     */
    fun resolve(
        engineCaps: Set<ModelCapability>,
        manifestCaps: Set<ModelCapability>?,
    ): Set<ModelCapability> {
        // No manifest entry: trust the engine's runtime introspection alone.
        if (manifestCaps == null) return engineCaps

        return buildSet {
            for (cap in UNION_CAPS) {
                if (cap in engineCaps || cap in manifestCaps) add(cap)
            }
            // Everything else is intersected — both sources must agree.
            for (cap in engineCaps) {
                if (cap !in UNION_CAPS && cap in manifestCaps) add(cap)
            }
        }
    }
}
