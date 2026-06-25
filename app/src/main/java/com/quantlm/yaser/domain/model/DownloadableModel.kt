package com.quantlm.yaser.domain.model

// Phase 1: HardwareAccelerationMode currently lives in the data layer. Imported here
// to keep the domain manifest data-only while the enum is repurposed. Promote the
// enum to domain in a follow-up cleanup if/when wider refactor lands.
import com.quantlm.yaser.data.local.GenerationPreferences.HardwareAccelerationMode

/**
 * Represents an additional data file required by a model.
 * For example, audio encoder, vision projector, or tokenizer files.
 */
data class ExtraDataFile(
    val name: String,
    val url: String,
    val downloadFileName: String,
    val sizeInBytes: Long = 0L
)

data class DownloadableModel(
    val id: String,
    val name: String,
    val description: String,
    val downloadUrl: String,
    val fileName: String,
    val quantization: String,
    val creator: String = "Unknown",
    val capabilitiesText: String = "",
    val recommendedFor: String = "",
    // Multimodal projector (vision) — paired with VISION capability.
    val mmprojUrl: String? = null,
    val mmprojFileName: String? = null,
    // File sizes are optional - if 0, will be fetched dynamically from server
    val fileSize: Long = 0L,
    val mmprojFileSize: Long = 0L,
    // Model format - defaults to GGUF but supports TFLite for future expansion
    val format: ModelFormat = ModelFormat.GGUF,
    // Versioning support (commit hash for update detection)
    val version: String = "_",
    // Extra data files (multiple files per model)
    val extraDataFiles: List<ExtraDataFile> = emptyList(),
    // HuggingFace access token for gated models
    val accessToken: String? = null,
    // Learn more URL for model info links
    val learnMoreUrl: String = "",
    // Phase 1: capability matrix (replaces single isVisionModel Boolean).
    val capabilities: Set<ModelCapability> = emptySet(),
    // Optional per-capability explainer surfaced in the chooser (Phase 2 UI).
    val capabilityNotes: Map<ModelCapability, String> = emptyMap(),
    // Which hardware accelerators this model can run on. Default keeps prior behavior
    // (GPU + CPU available; user-selected via HardwareSettings.accelerationMode).
    val supportedAccelerators: List<HardwareAccelerationMode> =
        listOf(HardwareAccelerationMode.GPU, HardwareAccelerationMode.CPU),
    // Optional override for the image encoder accelerator. null = inherit main accelerator.
    val visionAccelerator: HardwareAccelerationMode? = null,
    // Phase 2 follow-up: when the bundled engine/runtime is known to be unable
    // to load this file (e.g. a newer .litertlm sub-model type the bundled
    // tasks-genai SDK doesn't recognise — see Phase 2 §6 unknown), set
    // [loadable] = false and supply a user-facing [unsupportedReason] so the
    // download is allowed but the load is refused gracefully instead of
    // crashing the app via a native absl::CHECK abort.
    val loadable: Boolean = true,
    val unsupportedReason: String? = null,
) {
    /**
     * Detect format from filename if not explicitly set
     */
    fun getEffectiveFormat(): ModelFormat {
        return if (format == ModelFormat.GGUF) {
            ModelFormat.fromFileName(fileName)
        } else {
            format
        }
    }

    /**
     * Get total size including main file and all extra files
     */
    fun getTotalSize(): Long {
        val mmprojSize = if (isVisionModel) mmprojFileSize else 0L
        val extraSize = extraDataFiles.sumOf { it.sizeInBytes }
        return fileSize + mmprojSize + extraSize
    }

    /**
     * Returns the UI section title in the format "{AI name} by {Creator}".
     */
    fun getSectionTitle(): String {
        val lowerId = id.lowercase()
        return when {
            lowerId.startsWith("qwen") -> "Qwen by Alibaba"
            lowerId.startsWith("phi") -> "Phi by Microsoft"
            lowerId.startsWith("smol") -> "SmolVLM/SmolLM by Hugging Face"
            lowerId.startsWith("llama") -> "Llama by Meta"
            lowerId.startsWith("gemma") -> "Gemma by Google"
            lowerId.startsWith("deepseek") -> "DeepSeek by DeepSeek"
            else -> "${name.substringBefore(" ")} by $creator"
        }
    }
}

/**
 * Derived view kept for source-compat with all existing call sites that read
 * `model.isVisionModel`. Phase 1: VISION capability is the single source of truth.
 */
val DownloadableModel.isVisionModel: Boolean
    get() = ModelCapability.VISION in capabilities

// Predefined list of available models for download
// File sizes are dynamically fetched from the server during download
object AvailableModels {

    // ========== SmolVLM/SmolLM by Hugging Face ==========

    val SMOLVLM_1_8B_Q8_0 = DownloadableModel(
        id = "smolvlm-1.8b-instruct-q8_0",
        name = "SmolVLM 1.8B Q8 (Vision HQ)",
        description = "HuggingFace SmolVLM Instruct, Q8_0 quantization (higher-quality vision)",
        downloadUrl = "https://huggingface.co/ggml-org/SmolVLM-Instruct-GGUF/resolve/main/SmolVLM-Instruct-Q8_0.gguf",
        fileName = "SmolVLM-Instruct-Q8_0.gguf",
        quantization = "Q8_0",
        creator = "HuggingFace",
        capabilitiesText = "• Parameters: 1.8B\n" +
                "• Context: 16K tokens\n" +
                "• Vision/Image understanding\n" +
                "• Base: SmolLM2 1.7B + SigLIP So400M Patch14 384\n" +
                "• Q8_0 quantization",
        recommendedFor = "Best for: Detailed visual Q&A and richer image understanding\n\n" +
                "Settings: Context 4096-8192, Temp 0.7\n" +
                "RAM: 4-6GB minimum",
        mmprojUrl = "https://huggingface.co/ggml-org/SmolVLM-Instruct-GGUF/resolve/main/mmproj-SmolVLM-Instruct-Q8_0.gguf",
        mmprojFileName = "mmproj-SmolVLM-Instruct-Q8_0.gguf",
        fileSize = 1_927_383_680L,
        mmprojFileSize = 592_521_344L,
        capabilities = setOf(ModelCapability.VISION),
    )

    val SMOLVLM2_2_2B_Q8_0 = DownloadableModel(
        id = "smolvlm2-2.2b-instruct-q8_0",
        name = "SmolVLM2 2.2B Q8 (Vision HQ)",
        description = "HuggingFace SmolVLM2 2.2B Instruct, Q8_0 quantization (vision + video-tuned)",
        downloadUrl = "https://huggingface.co/ggml-org/SmolVLM2-2.2B-Instruct-GGUF/resolve/main/SmolVLM2-2.2B-Instruct-Q8_0.gguf",
        fileName = "SmolVLM2-2.2B-Instruct-Q8_0.gguf",
        quantization = "Q8_0",
        creator = "HuggingFace",
        capabilitiesText = "• Parameters: 2.2B\n" +
                "• Context: 8K tokens\n" +
                "• Vision/Image understanding\n" +
                "• Base: SmolVLM Instruct\n" +
                "• Q8_0 quantization",
        recommendedFor = "Best for: Quality multimodal understanding (images in-app)\n\n" +
                "Settings: Context 4096-8192, Temp 0.7\n" +
                "RAM: 5-6GB minimum",
        mmprojUrl = "https://huggingface.co/ggml-org/SmolVLM2-2.2B-Instruct-GGUF/resolve/main/mmproj-SmolVLM2-2.2B-Instruct-Q8_0.gguf",
        mmprojFileName = "mmproj-SmolVLM2-2.2B-Instruct-Q8_0.gguf",
        fileSize = 1_927_933_984L,
        mmprojFileSize = 592_523_200L,
        capabilities = setOf(ModelCapability.VISION),
    )

    val SMOLLM3_3B_Q4_K_M = DownloadableModel(
        id = "smollm3-3b-q4_k_m",
        name = "SmolLM3 3B Q4 (Balanced)",
        description = "HuggingFace SmolLM3 3B, Q4_K_M quantization (size/quality balance)",
        downloadUrl = "https://huggingface.co/ggml-org/SmolLM3-3B-GGUF/resolve/main/SmolLM3-Q4_K_M.gguf",
        fileName = "SmolLM3-Q4_K_M.gguf",
        quantization = "Q4_K_M",
        creator = "HuggingFace",
        capabilitiesText = "• Parameters: 3B\n" +
                "• Text-only model\n" +
                "• Q4_K_M quantization\n" +
                "• Optimized for local inference\n" +
                "• Great speed/quality balance",
        recommendedFor = "Best for: Faster local chat on mid-range devices\n\n" +
                "Settings: Context 2048-4096, Temp 0.7\n" +
                "RAM: 4-6GB minimum",
        fileSize = 1_915_305_312L,
        // Phase 1: enable_thinking supported per the SmolLM3 model card; runtime
        // detection (LlamaEngine.nativeChatTemplateSupportsThinking) gates whether
        // the toggle actually fires. AGENT_SKILLS deferred until verified end-to-end.
        capabilities = setOf(ModelCapability.REASONING),
    )

    val SMOLLM3_3B_Q8_0 = DownloadableModel(
        id = "smollm3-3b-q8_0",
        name = "SmolLM3 3B Q8 (High Quality)",
        description = "HuggingFace SmolLM3 3B, Q8_0 quantization (best quality)",
        downloadUrl = "https://huggingface.co/ggml-org/SmolLM3-3B-GGUF/resolve/main/SmolLM3-Q8_0.gguf",
        fileName = "SmolLM3-Q8_0.gguf",
        quantization = "Q8_0",
        creator = "HuggingFace",
        capabilitiesText = "• Parameters: 3B\n" +
                "• Text-only model\n" +
                "• Q8_0 quantization\n" +
                "• Higher quality than Q4\n" +
                "• More detailed responses",
        recommendedFor = "Best for: Higher-quality text chat and reasoning\n\n" +
                "Settings: Context 4096-8192, Temp 0.7\n" +
                "RAM: 6-8GB minimum",
        fileSize = 3_275_574_624L,
        capabilities = setOf(ModelCapability.REASONING),
    )

    // ========== Qwen by Alibaba ==========
    // LiteRT .task models loaded via MediaPipeEngine (LlmInference API)

    val QWEN2_5_1_5B_LITERT = DownloadableModel(
        id = "qwen2.5-1.5b-instruct-litert",
        name = "Qwen 2.5 1.5B Q8 (LiteRT)",
        description = "Alibaba Qwen 2.5 1.5B Instruct, Q8 quantization in Task format",
        downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task",
        fileName = "Qwen2.5-1.5B-Instruct_q8_ekv4096.task",
        quantization = "INT8",
        creator = "Alibaba",
        capabilitiesText = "• Parameters: 1.5B\n" +
                "• Context: 4K tokens (optimized)\n" +
                "• Text-only model\n" +
                "• Strong multilingual support\n" +
                "• Good reasoning ability\n" +
                "• LiteRT optimized",
        recommendedFor = "Best for: General chat, Q&A, multilingual tasks\n\n" +
                "Settings: Context 4096, Temp 0.7, Top-P 0.8\n" +
                "RAM: 6GB minimum",
        format = ModelFormat.TFLITE,
        fileSize = 1_717_986_816L,
        // AGENT_SKILLS deferred until tool-call JSON output is verified end-to-end.
        capabilities = emptySet(),
    )

    val QWEN2_5_VL_3B_Q8_0 = DownloadableModel(
        id = "qwen2.5-vl-3b-instruct-q8_0",
        name = "Qwen2.5-VL 3B Q8 (Vision)",
        description = "Qwen2.5-VL 3B Instruct, Q8_0 quantization (vision model)",
        downloadUrl = "https://huggingface.co/ggml-org/Qwen2.5-VL-3B-Instruct-GGUF/resolve/main/Qwen2.5-VL-3B-Instruct-Q8_0.gguf",
        fileName = "Qwen2.5-VL-3B-Instruct-Q8_0.gguf",
        quantization = "Q8_0",
        creator = "Alibaba",
        capabilitiesText = "• Parameters: 3B\n" +
                "• Vision/Image understanding\n" +
                "• Instruct-tuned\n" +
                "• Image size: 560\n" +
                "• Q8_0 quantization",
        recommendedFor = "Best for: High-quality image Q&A and vision tasks\n\n" +
                "Settings: Context 2048-4096, Temp 0.7\n" +
                "RAM: 6-8GB minimum",
        mmprojUrl = "https://huggingface.co/ggml-org/Qwen2.5-VL-3B-Instruct-GGUF/resolve/main/mmproj-Qwen2.5-VL-3B-Instruct-Q8_0.gguf",
        mmprojFileName = "mmproj-Qwen2.5-VL-3B-Instruct-Q8_0.gguf",
        fileSize = 3_285_474_304L,
        mmprojFileSize = 844_757_728L,
        capabilities = setOf(ModelCapability.VISION),
    )

    val QWEN3_VL_2B_F16 = DownloadableModel(
        id = "qwen3-vl-2b-instruct-f16",
        name = "Qwen3-VL 2B F16 (Vision)",
        description = "Qwen3-VL 2B Instruct, F16 precision (vision model)",
        downloadUrl = "https://huggingface.co/Qwen/Qwen3-VL-2B-Instruct-GGUF/resolve/main/Qwen3VL-2B-Instruct-F16.gguf",
        fileName = "Qwen3VL-2B-Instruct-F16.gguf",
        quantization = "F16",
        creator = "Alibaba",
        capabilitiesText = "• Parameters: 2B\n" +
                "• Vision/Image understanding\n" +
                "• Instruct-tuned\n" +
                "• Image size: 768\n" +
                "• F16 precision",
        recommendedFor = "Best for: High-fidelity vision tasks and image Q&A\n\n" +
                "Settings: Context 2048-4096, Temp 0.7\n" +
                "RAM: 7-9GB minimum",
        mmprojUrl = "https://huggingface.co/Qwen/Qwen3-VL-2B-Instruct-GGUF/resolve/main/mmproj-Qwen3VL-2B-Instruct-F16.gguf",
        mmprojFileName = "mmproj-Qwen3VL-2B-Instruct-F16.gguf",
        fileSize = 3_447_350_304L,
        mmprojFileSize = 819_394_848L,
        // REASONING tentative — Instruct variant may not expose enable_thinking
        // in its chat template. Default off; runtime detection can promote later.
        capabilities = setOf(ModelCapability.VISION),
    )

    // ========== Phi by Microsoft ==========
    // LiteRT .task models loaded via MediaPipeEngine (LlmInference API)

    val PHI_4_MINI_LITERT = DownloadableModel(
        id = "phi-4-mini-instruct-litert",
        name = "Phi-4 Mini Q8 (LiteRT)",
        description = "Microsoft Phi-4 Mini Instruct in Task format, 4096 context",
        downloadUrl = "https://huggingface.co/litert-community/Phi-4-mini-instruct/resolve/main/Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.task",
        fileName = "Phi-4-mini-instruct_q8_ekv4096.task",
        quantization = "INT8",
        creator = "Microsoft",
        capabilitiesText = "• Parameters: 3.8B\n" +
                "• Context: 4K tokens (optimized)\n" +
                "• Text-only model\n" +
                "• Strong reasoning & coding\n" +
                "• Function calling support\n" +
                "• 22 language support",
        recommendedFor = "Best for: Coding, complex reasoning, Q&A\n\n" +
                "Settings: Context 4096, Temp 1.0, Top-P 0.95\n" +
                "RAM: 6GB minimum",
        format = ModelFormat.TFLITE,
        fileSize = 4_198_498_304L,
        // AGENT_SKILLS deferred until tool-call JSON output is verified end-to-end.
        capabilities = emptySet(),
    )

    val PHI_4_MINI_Q3_K_L = DownloadableModel(
        id = "phi-4-mini-instruct-q3_k_l",
        name = "Phi-4 Mini Q3 (Light)",
        description = "Microsoft Phi-4 Mini 3.8B, Q3_K_L quantization (compact & fast)",
        downloadUrl = "https://huggingface.co/lmstudio-community/Phi-4-mini-instruct-GGUF/resolve/main/Phi-4-mini-instruct-Q3_K_L.gguf",
        fileName = "Phi-4-mini-instruct-Q3_K_L.gguf",
        quantization = "Q3_K_L",
        creator = "Microsoft",
        capabilitiesText = "• Parameters: 3.8B\n" +
                "• Context: 128K tokens (native)\n" +
                "• Text-only model\n" +
                "• Strong coding ability\n" +
                "• Function calling support\n" +
                "• 22 language support",
        recommendedFor = "Best for: Budget devices, quick responses, basic coding\n\n" +
                "Settings: Context 2048-4096, Temp 0.7\n" +
                "RAM: 4GB minimum",
        fileSize = 2_416_967_680L,
        capabilities = emptySet(),
    )

    val PHI_4_MINI_Q4_K_M = DownloadableModel(
        id = "phi-4-mini-instruct-q4_k_m",
        name = "Phi-4 Mini Q4 (Recommended)",
        description = "Microsoft Phi-4 Mini 3.8B, Q4_K_M quantization (best balance)",
        downloadUrl = "https://huggingface.co/lmstudio-community/Phi-4-mini-instruct-GGUF/resolve/main/Phi-4-mini-instruct-Q4_K_M.gguf",
        fileName = "Phi-4-mini-instruct-Q4_K_M.gguf",
        quantization = "Q4_K_M",
        creator = "Microsoft",
        capabilitiesText = "• Parameters: 3.8B\n" +
                "• Context: 128K tokens (native)\n" +
                "• Text-only model\n" +
                "• Strong reasoning & coding\n" +
                "• Function calling support\n" +
                "• 22 language support",
        recommendedFor = "Best for: General chat, coding, Q&A, complex reasoning\n\n" +
                "Settings: Context 4096-8192, Temp 0.7\n" +
                "RAM: 6GB minimum",
        fileSize = 2_674_644_992L,
        capabilities = emptySet(),
    )

    val PHI_4_MINI_Q6_K = DownloadableModel(
        id = "phi-4-mini-instruct-q6_k",
        name = "Phi-4 Mini Q6 (High Quality)",
        description = "Microsoft Phi-4 Mini 3.8B, Q6_K quantization (best quality)",
        downloadUrl = "https://huggingface.co/lmstudio-community/Phi-4-mini-instruct-GGUF/resolve/main/Phi-4-mini-instruct-Q6_K.gguf",
        fileName = "Phi-4-mini-instruct-Q6_K.gguf",
        quantization = "Q6_K",
        creator = "Microsoft",
        capabilitiesText = "• Parameters: 3.8B\n" +
                "• Context: 128K tokens (native)\n" +
                "• Text-only model\n" +
                "• Near-lossless quality\n" +
                "• Advanced reasoning\n" +
                "• 22 language support",
        recommendedFor = "Best for: Professional use, complex coding, reasoning\n\n" +
                "Settings: Context 4096-8192, Temp 0.6-0.7\n" +
                "RAM: 8GB minimum",
        fileSize = 3_393_462_272L,
        capabilities = emptySet(),
    )

    // ========== Gemma by Google ==========
    // LiteRT .litertlm models loaded via MediaPipeEngine. Capabilities mirror
    // Edge_AI_Gallery model_allowlists/1_0_13.json. SPECULATIVE_DECODING and
    // REASONING for Gemma 4 are metadata-only in Phase 1 — runtime support is
    // documented as TODO (see prompts/QuantLM_Phase1_Opus.md §1.4). AGENT_SKILLS
    // is potentially supported but deferred until tool-call JSON output is verified.

    val GEMMA_4_E2B_IT_LITERT = DownloadableModel(
        id = "gemma-4-e2b-it-litert",
        name = "Gemma 4 E2B IT (LiteRT)",
        description = "Google Gemma 4 E2B Instruct — multimodal (image + audio), 32K context, deployed via LiteRT-LM.",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm?download=true",
        fileName = "gemma-4-E2B-it.litertlm",
        quantization = "Mixed",
        creator = "Google",
        capabilitiesText = "• Parameters: ~2B (effective)\n" +
                "• Context: 32K tokens\n" +
                "• Vision + Audio input\n" +
                "• Thinking (chain-of-thought)\n" +
                "• Speculative decoding ready\n" +
                "• LiteRT-LM optimized",
        recommendedFor = "Best for: Multimodal chat with image and audio, reasoning tasks\n\n" +
                "Settings: Context 8K+, Temp 1.0, Top-K 64, Top-P 0.95\n" +
                "RAM: 8GB minimum",
        format = ModelFormat.TFLITE,
        version = "6e5c4f1e395deb959c494953478fa5cec4b8008f",
        fileSize = 2_588_147_712L,
        capabilities = setOf(
            ModelCapability.VISION,
            ModelCapability.AUDIO,
            ModelCapability.REASONING,
            ModelCapability.SPECULATIVE_DECODING,
        ),
        capabilityNotes = mapOf(
            ModelCapability.SPECULATIVE_DECODING to
                "Manifest-only in Phase 1 — runtime draft-model path pending (see §1.4).",
        ),
        visionAccelerator = HardwareAccelerationMode.GPU,
        // Loads via LiteRTEngine (litertlm-android 0.11.0), which natively supports
        // Gemma 4 multimodal `.litertlm` bundles — see loadWithLiteRTEngine().
    )

    val GEMMA_4_E4B_IT_LITERT = DownloadableModel(
        id = "gemma-4-e4b-it-litert",
        name = "Gemma 4 E4B IT (LiteRT)",
        description = "Google Gemma 4 E4B Instruct — multimodal (image + audio), 32K context, deployed via LiteRT-LM.",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/28299f30ee4d43294517a4ac93abd6163412f07f/gemma-4-E4B-it.litertlm?download=true",
        fileName = "gemma-4-E4B-it.litertlm",
        quantization = "Mixed",
        creator = "Google",
        capabilitiesText = "• Parameters: ~4B (effective)\n" +
                "• Context: 32K tokens\n" +
                "• Vision + Audio input\n" +
                "• Thinking (chain-of-thought)\n" +
                "• Speculative decoding ready\n" +
                "• LiteRT-LM optimized",
        recommendedFor = "Best for: Higher-quality multimodal chat and reasoning\n\n" +
                "Settings: Context 8K+, Temp 1.0, Top-K 64, Top-P 0.95\n" +
                "RAM: 12GB minimum",
        format = ModelFormat.TFLITE,
        version = "28299f30ee4d43294517a4ac93abd6163412f07f",
        fileSize = 3_659_530_240L,
        capabilities = setOf(
            ModelCapability.VISION,
            ModelCapability.AUDIO,
            ModelCapability.REASONING,
            ModelCapability.SPECULATIVE_DECODING,
        ),
        capabilityNotes = mapOf(
            ModelCapability.SPECULATIVE_DECODING to
                "Manifest-only in Phase 1 — runtime draft-model path pending (see §1.4).",
        ),
        visionAccelerator = HardwareAccelerationMode.GPU,
        // Loads via LiteRTEngine (litertlm-android 0.11.0) — see loadWithLiteRTEngine().
    )

    val GEMMA_3_1B_IT_LITERT = DownloadableModel(
        id = "gemma3-1b-it-litert",
        name = "Gemma 3 1B IT (LiteRT)",
        description = "Google Gemma 3 1B Instruct — text-only, INT4, LiteRT-LM packaging.",
        downloadUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/42d538a932e8d5b12e6b3b455f5572560bd60b2c/gemma3-1b-it-int4.litertlm?download=true",
        fileName = "gemma3-1b-it-int4.litertlm",
        quantization = "INT4",
        creator = "Google",
        capabilitiesText = "• Parameters: 1B\n" +
                "• Text-only model\n" +
                "• INT4 quantization\n" +
                "• Compact LiteRT-LM bundle",
        recommendedFor = "Best for: Fast on-device chat on memory-constrained devices\n\n" +
                "Settings: Context 2048-4096, Temp 1.0, Top-K 64, Top-P 0.95\n" +
                "RAM: 6GB minimum",
        format = ModelFormat.TFLITE,
        version = "42d538a932e8d5b12e6b3b455f5572560bd60b2c",
        fileSize = 584_417_280L,
        capabilities = emptySet(),
    )

    fun getAllModels(): List<DownloadableModel> = listOf(
        // ====== SmolVLM/SmolLM by Hugging Face ======
        SMOLVLM_1_8B_Q8_0,
        SMOLVLM2_2_2B_Q8_0,
        SMOLLM3_3B_Q4_K_M,
        SMOLLM3_3B_Q8_0,

        // ====== Qwen by Alibaba ======
        QWEN2_5_1_5B_LITERT,
        QWEN2_5_VL_3B_Q8_0,
        QWEN3_VL_2B_F16,

        // ====== Phi by Microsoft ======
        PHI_4_MINI_LITERT,
        PHI_4_MINI_Q3_K_L,
        PHI_4_MINI_Q4_K_M,
        PHI_4_MINI_Q6_K,

        // ====== Gemma by Google ======
        GEMMA_4_E2B_IT_LITERT,
        GEMMA_4_E4B_IT_LITERT,
        GEMMA_3_1B_IT_LITERT,

    )

    fun getModelsByCreator(): Map<String, List<DownloadableModel>> {
        return getAllModels().groupBy { it.getSectionTitle() }
    }
}
