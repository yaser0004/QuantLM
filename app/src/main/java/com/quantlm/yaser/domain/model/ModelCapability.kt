package com.quantlm.yaser.domain.model

/**
 * Per-model capability flags surfaced through [DownloadableModel] and [ModelInfo].
 *
 * Phase 1 introduces these to replace the single `isVisionModel` Boolean. Components
 * gate UI/behavior off the capability set rather than special-casing model names.
 *
 * Capability semantics:
 * - [VISION]               — model can ingest image inputs (paired with mmproj or
 *                            multimodal `.litertlm`/`.task` bundles).
 * - [AUDIO]                — model can ingest audio inputs directly (Gemma 3n /
 *                            Gemma 4 family today).
 * - [REASONING]            — model emits `<think>...</think>` style chain-of-thought
 *                            (DeepSeek-R1, SmolLM3 with `enable_thinking`, Qwen3
 *                            Thinking, Gemma 4 family).
 * - [SPECULATIVE_DECODING] — model + runtime supports draft-model speculation.
 *                            Phase 1: metadata only; runtime path TODO
 *                            (see prompts/QuantLM_Phase1_Opus.md §1.4).
 * - [AGENT_SKILLS]         — model can be relied upon to emit tool-call JSON the
 *                            existing [com.quantlm.yaser.domain.model.ToolCalling]
 *                            parser understands. Default off until verified end-to-end.
 * - [DEVICE_ACTIONS]       — metadata-only flag reserved for future device-control
 *                            features (flashlight, brightness, etc.).
 */
enum class ModelCapability {
    VISION,
    AUDIO,
    REASONING,
    SPECULATIVE_DECODING,
    AGENT_SKILLS,
    DEVICE_ACTIONS,
}
