package com.quantlm.yaser.domain.model

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
    val capabilities: String = "",
    val recommendedFor: String = "",
    // Vision model support
    val isVisionModel: Boolean = false,
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
    val learnMoreUrl: String = ""
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

// Predefined list of available models for download
// File sizes are dynamically fetched from the server during download
object AvailableModels {
    
        // ========== SmolVLM/SmolLM by Hugging Face ==========
    
    val SMOLVLM_256M_F16 = DownloadableModel(
        id = "smolvlm-256m-instruct-f16",
        name = "SmolVLM 256M F16 (Vision)",
        description = "HuggingFace SmolVLM 256M Instruct, F16 precision (ultra-compact vision model)",
        downloadUrl = "https://huggingface.co/ggml-org/SmolVLM-256M-Instruct-GGUF/resolve/main/SmolVLM-256M-Instruct-f16.gguf",
        fileName = "SmolVLM-256M-Instruct-f16.gguf",
        quantization = "F16",
        creator = "HuggingFace",
        capabilities = "• Parameters: 256M\n" +
                "• Vision/Image understanding\n" +
                "• Base: SmolLM2 135M + SigLIP Base Patch16 512\n" +
                "• Instruct-tuned\n" +
                "• F16 precision",
        recommendedFor = "Best for: Tiny vision demos and quick image Q&A\n\n" +
                "Settings: Context 2048-4096, Temp 0.7\n" +
                "RAM: 2-3GB minimum",
        isVisionModel = true,
        mmprojUrl = "https://huggingface.co/ggml-org/SmolVLM-256M-Instruct-GGUF/resolve/main/mmproj-SmolVLM-256M-Instruct-f16.gguf",
        mmprojFileName = "mmproj-SmolVLM-256M-Instruct-f16.gguf",
        fileSize = 327_809_728L,
        mmprojFileSize = 190_031_616L
    )
    
    val SMOLVLM_500M_F16 = DownloadableModel(
        id = "smolvlm-500m-instruct-f16",
        name = "SmolVLM 500M F16 (Vision)",
        description = "HuggingFace SmolVLM 500M Instruct, F16 precision (compact vision model)",
        downloadUrl = "https://huggingface.co/ggml-org/SmolVLM-500M-Instruct-GGUF/resolve/main/SmolVLM-500M-Instruct-f16.gguf",
        fileName = "SmolVLM-500M-Instruct-f16.gguf",
        quantization = "F16",
        creator = "HuggingFace",
        capabilities = "• Parameters: 500M\n" +
                "• Context: 8K tokens\n" +
                "• Vision/Image understanding\n" +
                "• Base: SmolLM2 360M + SigLIP Base Patch16 512\n" +
                "• F16 precision",
        recommendedFor = "Best for: Mobile image Q&A with improved quality\n\n" +
                "Settings: Context 2048-4096, Temp 0.7\n" +
                "RAM: 3-4GB minimum",
        isVisionModel = true,
        mmprojUrl = "https://huggingface.co/ggml-org/SmolVLM-500M-Instruct-GGUF/resolve/main/mmproj-SmolVLM-500M-Instruct-f16.gguf",
        mmprojFileName = "mmproj-SmolVLM-500M-Instruct-f16.gguf",
        fileSize = 820_422_912L,
        mmprojFileSize = 199_468_800L
    )
    
    val SMOLVLM_1_8B_Q8_0 = DownloadableModel(
        id = "smolvlm-1.8b-instruct-q8_0",
        name = "SmolVLM 1.8B Q8 (Vision HQ)",
        description = "HuggingFace SmolVLM Instruct, Q8_0 quantization (higher-quality vision)",
        downloadUrl = "https://huggingface.co/ggml-org/SmolVLM-Instruct-GGUF/resolve/main/SmolVLM-Instruct-Q8_0.gguf",
        fileName = "SmolVLM-Instruct-Q8_0.gguf",
        quantization = "Q8_0",
        creator = "HuggingFace",
        capabilities = "• Parameters: 1.8B\n" +
                "• Context: 16K tokens\n" +
                "• Vision/Image understanding\n" +
                "• Base: SmolLM2 1.7B + SigLIP So400M Patch14 384\n" +
                "• Q8_0 quantization",
        recommendedFor = "Best for: Detailed visual Q&A and richer image understanding\n\n" +
                "Settings: Context 4096-8192, Temp 0.7\n" +
                "RAM: 4-6GB minimum",
        isVisionModel = true,
        mmprojUrl = "https://huggingface.co/ggml-org/SmolVLM-Instruct-GGUF/resolve/main/mmproj-SmolVLM-Instruct-Q8_0.gguf",
        mmprojFileName = "mmproj-SmolVLM-Instruct-Q8_0.gguf",
        fileSize = 1_927_383_680L,
        mmprojFileSize = 592_521_344L
    )
    
    val SMOLVLM2_256M_VIDEO_F16 = DownloadableModel(
        id = "smolvlm2-256m-video-instruct-f16",
        name = "SmolVLM2 256M Video F16 (Vision)",
        description = "HuggingFace SmolVLM2 256M Video Instruct, F16 precision (video-tuned vision model)",
        downloadUrl = "https://huggingface.co/ggml-org/SmolVLM2-256M-Video-Instruct-GGUF/resolve/main/SmolVLM2-256M-Video-Instruct-f16.gguf",
        fileName = "SmolVLM2-256M-Video-Instruct-f16.gguf",
        quantization = "F16",
        creator = "HuggingFace",
        capabilities = "• Parameters: 256M\n" +
                "• Context: 8K tokens\n" +
                "• Vision/Image understanding\n" +
                "• Video-tuned dataset\n" +
                "• Base: SmolVLM 256M Instruct",
        recommendedFor = "Best for: Tiny vision model with video-tuned training\n\n" +
                "Settings: Context 2048-4096, Temp 0.7\n" +
                "RAM: 2-3GB minimum",
        isVisionModel = true,
        mmprojUrl = "https://huggingface.co/ggml-org/SmolVLM2-256M-Video-Instruct-GGUF/resolve/main/mmproj-SmolVLM2-256M-Video-Instruct-f16.gguf",
        mmprojFileName = "mmproj-SmolVLM2-256M-Video-Instruct-f16.gguf",
        fileSize = 327_811_552L,
        mmprojFileSize = 190_033_440L
    )
    
    val SMOLVLM2_500M_VIDEO_F16 = DownloadableModel(
        id = "smolvlm2-500m-video-instruct-f16",
        name = "SmolVLM2 500M Video F16 (Vision)",
        description = "HuggingFace SmolVLM2 500M Video Instruct, F16 precision (video-tuned vision model)",
        downloadUrl = "https://huggingface.co/ggml-org/SmolVLM2-500M-Video-Instruct-GGUF/resolve/main/SmolVLM2-500M-Video-Instruct-f16.gguf",
        fileName = "SmolVLM2-500M-Video-Instruct-f16.gguf",
        quantization = "F16",
        creator = "HuggingFace",
        capabilities = "• Parameters: 500M\n" +
                "• Context: 8K tokens\n" +
                "• Vision/Image understanding\n" +
                "• Video-tuned dataset\n" +
                "• Base: SmolVLM 500M Instruct",
        recommendedFor = "Best for: Higher-quality video-tuned vision tasks\n\n" +
                "Settings: Context 2048-4096, Temp 0.7\n" +
                "RAM: 3-4GB minimum",
        isVisionModel = true,
        mmprojUrl = "https://huggingface.co/ggml-org/SmolVLM2-500M-Video-Instruct-GGUF/resolve/main/mmproj-SmolVLM2-500M-Video-Instruct-f16.gguf",
        mmprojFileName = "mmproj-SmolVLM2-500M-Video-Instruct-f16.gguf",
        fileSize = 820_424_704L,
        mmprojFileSize = 199_470_624L
    )
    
    val SMOLVLM2_2_2B_Q8_0 = DownloadableModel(
        id = "smolvlm2-2.2b-instruct-q8_0",
        name = "SmolVLM2 2.2B Q8 (Vision HQ)",
        description = "HuggingFace SmolVLM2 2.2B Instruct, Q8_0 quantization (vision + video-tuned)",
        downloadUrl = "https://huggingface.co/ggml-org/SmolVLM2-2.2B-Instruct-GGUF/resolve/main/SmolVLM2-2.2B-Instruct-Q8_0.gguf",
        fileName = "SmolVLM2-2.2B-Instruct-Q8_0.gguf",
        quantization = "Q8_0",
        creator = "HuggingFace",
        capabilities = "• Parameters: 2.2B\n" +
                "• Context: 8K tokens\n" +
                "• Vision/Image understanding\n" +
                "• Base: SmolVLM Instruct\n" +
                "• Q8_0 quantization",
        recommendedFor = "Best for: Quality multimodal understanding (images in-app)\n\n" +
                "Settings: Context 4096-8192, Temp 0.7\n" +
                "RAM: 5-6GB minimum",
        isVisionModel = true,
        mmprojUrl = "https://huggingface.co/ggml-org/SmolVLM2-2.2B-Instruct-GGUF/resolve/main/mmproj-SmolVLM2-2.2B-Instruct-Q8_0.gguf",
        mmprojFileName = "mmproj-SmolVLM2-2.2B-Instruct-Q8_0.gguf",
        fileSize = 1_927_933_984L,
        mmprojFileSize = 592_523_200L
    )
    
    val SMOLLM3_3B_Q4_K_M = DownloadableModel(
        id = "smollm3-3b-q4_k_m",
        name = "SmolLM3 3B Q4 (Balanced)",
        description = "HuggingFace SmolLM3 3B, Q4_K_M quantization (size/quality balance)",
        downloadUrl = "https://huggingface.co/ggml-org/SmolLM3-3B-GGUF/resolve/main/SmolLM3-Q4_K_M.gguf",
        fileName = "SmolLM3-Q4_K_M.gguf",
        quantization = "Q4_K_M",
        creator = "HuggingFace",
        capabilities = "• Parameters: 3B\n" +
                "• Text-only model\n" +
                "• Q4_K_M quantization\n" +
                "• Optimized for local inference\n" +
                "• Great speed/quality balance",
        recommendedFor = "Best for: Faster local chat on mid-range devices\n\n" +
                "Settings: Context 2048-4096, Temp 0.7\n" +
                "RAM: 4-6GB minimum",
        fileSize = 1_915_305_312L
    )
    
    val SMOLLM3_3B_Q8_0 = DownloadableModel(
        id = "smollm3-3b-q8_0",
        name = "SmolLM3 3B Q8 (High Quality)",
        description = "HuggingFace SmolLM3 3B, Q8_0 quantization (best quality)",
        downloadUrl = "https://huggingface.co/ggml-org/SmolLM3-3B-GGUF/resolve/main/SmolLM3-Q8_0.gguf",
        fileName = "SmolLM3-Q8_0.gguf",
        quantization = "Q8_0",
        creator = "HuggingFace",
        capabilities = "• Parameters: 3B\n" +
                "• Text-only model\n" +
                "• Q8_0 quantization\n" +
                "• Higher quality than Q4\n" +
                "• More detailed responses",
        recommendedFor = "Best for: Higher-quality text chat and reasoning\n\n" +
                "Settings: Context 4096-8192, Temp 0.7\n" +
                "RAM: 6-8GB minimum",
        fileSize = 3_275_574_624L
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
        capabilities = "• Parameters: 1.5B\n" +
                "• Context: 4K tokens (optimized)\n" +
                "• Text-only model\n" +
                "• Strong multilingual support\n" +
                "• Good reasoning ability\n" +
                "• LiteRT optimized",
        recommendedFor = "Best for: General chat, Q&A, multilingual tasks\n\n" +
                "Settings: Context 4096, Temp 0.7, Top-P 0.8\n" +
                "RAM: 6GB minimum",
        format = ModelFormat.TFLITE,
        fileSize = 1_717_986_816L
    )
    
    val QWEN2_5_VL_3B_Q8_0 = DownloadableModel(
        id = "qwen2.5-vl-3b-instruct-q8_0",
        name = "Qwen2.5-VL 3B Q8 (Vision)",
        description = "Qwen2.5-VL 3B Instruct, Q8_0 quantization (vision model)",
        downloadUrl = "https://huggingface.co/ggml-org/Qwen2.5-VL-3B-Instruct-GGUF/resolve/main/Qwen2.5-VL-3B-Instruct-Q8_0.gguf",
        fileName = "Qwen2.5-VL-3B-Instruct-Q8_0.gguf",
        quantization = "Q8_0",
        creator = "Alibaba",
        capabilities = "• Parameters: 3B\n" +
                "• Vision/Image understanding\n" +
                "• Instruct-tuned\n" +
                "• Image size: 560\n" +
                "• Q8_0 quantization",
        recommendedFor = "Best for: High-quality image Q&A and vision tasks\n\n" +
                "Settings: Context 2048-4096, Temp 0.7\n" +
                "RAM: 6-8GB minimum",
        isVisionModel = true,
        mmprojUrl = "https://huggingface.co/ggml-org/Qwen2.5-VL-3B-Instruct-GGUF/resolve/main/mmproj-Qwen2.5-VL-3B-Instruct-Q8_0.gguf",
        mmprojFileName = "mmproj-Qwen2.5-VL-3B-Instruct-Q8_0.gguf",
        fileSize = 3_285_474_304L,
        mmprojFileSize = 844_757_728L
    )
    
    val QWEN3_VL_2B_F16 = DownloadableModel(
        id = "qwen3-vl-2b-instruct-f16",
        name = "Qwen3-VL 2B F16 (Vision)",
        description = "Qwen3-VL 2B Instruct, F16 precision (vision model)",
        downloadUrl = "https://huggingface.co/Qwen/Qwen3-VL-2B-Instruct-GGUF/resolve/main/Qwen3VL-2B-Instruct-F16.gguf",
        fileName = "Qwen3VL-2B-Instruct-F16.gguf",
        quantization = "F16",
        creator = "Alibaba",
        capabilities = "• Parameters: 2B\n" +
                "• Vision/Image understanding\n" +
                "• Instruct-tuned\n" +
                "• Image size: 768\n" +
                "• F16 precision",
        recommendedFor = "Best for: High-fidelity vision tasks and image Q&A\n\n" +
                "Settings: Context 2048-4096, Temp 0.7\n" +
                "RAM: 7-9GB minimum",
        isVisionModel = true,
        mmprojUrl = "https://huggingface.co/Qwen/Qwen3-VL-2B-Instruct-GGUF/resolve/main/mmproj-Qwen3VL-2B-Instruct-F16.gguf",
        mmprojFileName = "mmproj-Qwen3VL-2B-Instruct-F16.gguf",
        fileSize = 3_447_350_304L,
        mmprojFileSize = 819_394_848L
    )
    
    // ========== DeepSeek by DeepSeek ==========
    // LiteRT .task models loaded via MediaPipeEngine (LlmInference API)
    
    val DEEPSEEK_R1_1_5B_LITERT = DownloadableModel(
        id = "deepseek-r1-distill-qwen-1.5b-litert",
        name = "DeepSeek R1 1.5B Q8 (LiteRT)",
        description = "DeepSeek R1 Distill Qwen 1.5B - reasoning model in Task format",
        downloadUrl = "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.task",
        fileName = "DeepSeek-R1-Distill-Qwen-1.5B_q8_ekv4096.task",
        quantization = "INT8",
        creator = "DeepSeek",
        capabilities = "• Parameters: 1.5B\n" +
                "• Context: 4K tokens (optimized)\n" +
                "• Reasoning-optimized model\n" +
                "• Chain-of-thought capability\n" +
                "• Distilled from DeepSeek R1\n" +
                "• Math & logic specialist",
        recommendedFor = "Best for: Complex reasoning, math problems, logic puzzles\n\n" +
                "Settings: Context 4096, Temp 1.0, Top-P 0.95\n" +
                "RAM: 6GB minimum",
        format = ModelFormat.TFLITE,
        fileSize = 1_965_031_424L
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
        capabilities = "• Parameters: 3.8B\n" +
                "• Context: 4K tokens (optimized)\n" +
                "• Text-only model\n" +
                "• Strong reasoning & coding\n" +
                "• Function calling support\n" +
                "• 22 language support",
        recommendedFor = "Best for: Coding, complex reasoning, Q&A\n\n" +
                "Settings: Context 4096, Temp 1.0, Top-P 0.95\n" +
                "RAM: 6GB minimum",
        format = ModelFormat.TFLITE,
        fileSize = 4_198_498_304L
    )
    
    val PHI_4_MINI_Q3_K_L = DownloadableModel(
        id = "phi-4-mini-instruct-q3_k_l",
        name = "Phi-4 Mini Q3 (Light)",
        description = "Microsoft Phi-4 Mini 3.8B, Q3_K_L quantization (compact & fast)",
        downloadUrl = "https://huggingface.co/lmstudio-community/Phi-4-mini-instruct-GGUF/resolve/main/Phi-4-mini-instruct-Q3_K_L.gguf",
        fileName = "Phi-4-mini-instruct-Q3_K_L.gguf",
        quantization = "Q3_K_L",
        creator = "Microsoft",
        capabilities = "• Parameters: 3.8B\n" +
                "• Context: 128K tokens (native)\n" +
                "• Text-only model\n" +
                "• Strong coding ability\n" +
                "• Function calling support\n" +
                "• 22 language support",
        recommendedFor = "Best for: Budget devices, quick responses, basic coding\n\n" +
                "Settings: Context 2048-4096, Temp 0.7\n" +
                "RAM: 4GB minimum",
        fileSize = 2_416_967_680L
    )
    
    val PHI_4_MINI_Q4_K_M = DownloadableModel(
        id = "phi-4-mini-instruct-q4_k_m",
        name = "Phi-4 Mini Q4 (Recommended)",
        description = "Microsoft Phi-4 Mini 3.8B, Q4_K_M quantization (best balance)",
        downloadUrl = "https://huggingface.co/lmstudio-community/Phi-4-mini-instruct-GGUF/resolve/main/Phi-4-mini-instruct-Q4_K_M.gguf",
        fileName = "Phi-4-mini-instruct-Q4_K_M.gguf",
        quantization = "Q4_K_M",
        creator = "Microsoft",
        capabilities = "• Parameters: 3.8B\n" +
                "• Context: 128K tokens (native)\n" +
                "• Text-only model\n" +
                "• Strong reasoning & coding\n" +
                "• Function calling support\n" +
                "• 22 language support",
        recommendedFor = "Best for: General chat, coding, Q&A, complex reasoning\n\n" +
                "Settings: Context 4096-8192, Temp 0.7\n" +
                "RAM: 6GB minimum",
        fileSize = 2_674_644_992L
    )
    
    val PHI_4_MINI_Q6_K = DownloadableModel(
        id = "phi-4-mini-instruct-q6_k",
        name = "Phi-4 Mini Q6 (High Quality)",
        description = "Microsoft Phi-4 Mini 3.8B, Q6_K quantization (best quality)",
        downloadUrl = "https://huggingface.co/lmstudio-community/Phi-4-mini-instruct-GGUF/resolve/main/Phi-4-mini-instruct-Q6_K.gguf",
        fileName = "Phi-4-mini-instruct-Q6_K.gguf",
        quantization = "Q6_K",
        creator = "Microsoft",
        capabilities = "• Parameters: 3.8B\n" +
                "• Context: 128K tokens (native)\n" +
                "• Text-only model\n" +
                "• Near-lossless quality\n" +
                "• Advanced reasoning\n" +
                "• 22 language support",
        recommendedFor = "Best for: Professional use, complex coding, reasoning\n\n" +
                "Settings: Context 4096-8192, Temp 0.6-0.7\n" +
                "RAM: 8GB minimum",
        fileSize = 3_393_462_272L
    )
    
    // ========== Llama by Meta ==========
    
    val LLAMA_3_2_1B_Q8_0 = DownloadableModel(
        id = "llama-3.2-1b-instruct-q8_0",
        name = "Llama 3.2 1B Q8 (Fast)",
        description = "Meta Llama 3.2 1B Instruct, Q8_0 quantization (efficient & fast)",
        downloadUrl = "https://huggingface.co/hugging-quants/Llama-3.2-1B-Instruct-Q8_0-GGUF/resolve/main/llama-3.2-1b-instruct-q8_0.gguf",
        fileName = "llama-3.2-1b-instruct-q8_0.gguf",
        quantization = "Q8_0",
        creator = "Meta",
        capabilities = "• Parameters: 1B\n" +
                "• Context: 128K tokens (native)\n" +
                "• Text-only model\n" +
                "• Great for summarization\n" +
                "• Fast response times\n" +
                "• Good instruction following",
        recommendedFor = "Best for: Quick chat, summarization, basic tasks\n\n" +
                "Settings: Context 2048-4096, Temp 0.5-0.7\n" +
                "RAM: 3GB minimum",
        fileSize = 1_321_079_200L
    )
    
    val LLAMA_3_2_3B_Q6_K = DownloadableModel(
        id = "llama-3.2-3b-instruct-q6_k",
        name = "Llama 3.2 3B Q6 (Balanced)",
        description = "Meta Llama 3.2 3B Instruct, Q6_K quantization (quality & speed)",
        downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q6_K.gguf",
        fileName = "Llama-3.2-3B-Instruct-Q6_K.gguf",
        quantization = "Q6_K",
        creator = "Meta",
        capabilities = "• Parameters: 3B\n" +
                "• Context: 128K tokens (native)\n" +
                "• Text-only model\n" +
                "• Strong instruction following\n" +
                "• Good at summarization\n" +
                "• Rewriting capability",
        recommendedFor = "Best for: General chat, summarization, content rewriting\n\n" +
                "Settings: Context 4096-8192, Temp 0.5-0.7\n" +
                "RAM: 5GB minimum",
        fileSize = 2_643_853_856L
    )
    
        fun getAllModels(): List<DownloadableModel> = listOf(
                // ====== SmolVLM/SmolLM by Hugging Face ======
                SMOLVLM_256M_F16,
                SMOLVLM_500M_F16,
                SMOLVLM_1_8B_Q8_0,
                SMOLVLM2_256M_VIDEO_F16,
                SMOLVLM2_500M_VIDEO_F16,
                SMOLVLM2_2_2B_Q8_0,
                SMOLLM3_3B_Q4_K_M,
                SMOLLM3_3B_Q8_0,

                // ====== Qwen by Alibaba ======
                QWEN2_5_1_5B_LITERT,
                QWEN2_5_VL_3B_Q8_0,
                QWEN3_VL_2B_F16,

                // ====== DeepSeek by DeepSeek ======
                DEEPSEEK_R1_1_5B_LITERT,

                // ====== Phi by Microsoft ======
                PHI_4_MINI_LITERT,
                PHI_4_MINI_Q3_K_L,
                PHI_4_MINI_Q4_K_M,
                PHI_4_MINI_Q6_K,

                // ====== Llama by Meta ======
                LLAMA_3_2_1B_Q8_0,
                LLAMA_3_2_3B_Q6_K
        )
    
        fun getModelsByCreator(): Map<String, List<DownloadableModel>> {
                return getAllModels().groupBy { it.getSectionTitle() }
    }
}
