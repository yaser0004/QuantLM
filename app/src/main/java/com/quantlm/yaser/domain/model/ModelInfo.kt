package com.quantlm.yaser.domain.model

/**
 * Runtime info about a discovered or loaded model.
 *
 * Phase 1 mirrors the per-model capability set onto runtime info so the UI can
 * gate features off the loaded-model state flow without inspecting the engine
 * or the manifest directly. `isVisionModel`/`mmprojPath`/`isVisionIncomplete`
 * are kept for source-compat with download/check code that already reads them.
 */
data class ModelInfo(
    val name: String,
    val filePath: String,
    val size: Long,
    val isLoaded: Boolean = false,
    val metadata: String = "",
    // Vision model support (kept for source-compat — derive new gating off
    // [capabilities] / [incompleteCapabilities] in new code).
    val isVisionModel: Boolean = false,
    val mmprojPath: String? = null,
    // True if this is a vision model but mmproj is not yet downloaded.
    val isVisionIncomplete: Boolean = false,
    // Phase 1: full capability set from the manifest, intersected with what the
    // active engine reports it can actually run for this model.
    val capabilities: Set<ModelCapability> = emptySet(),
    // Capabilities the manifest claims but cannot currently run (e.g. VISION when
    // mmproj is missing). UI may surface these as "complete download to enable".
    val incompleteCapabilities: Set<ModelCapability> = emptySet(),
)
