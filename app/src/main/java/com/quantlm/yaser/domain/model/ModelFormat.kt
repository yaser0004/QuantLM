package com.quantlm.yaser.domain.model

/**
 * Enum representing the different model formats/frameworks supported by QuantLM.
 * Each framework requires different inference engines and native libraries.
 */
enum class ModelFormat {
    /**
     * GGUF (GGML Universal Format) - Used by llama.cpp
     * Most common format for running LLMs on-device
     * File extension: .gguf
     */
    GGUF,
    
    /**
     * TensorFlow Lite / LiteRT - Google's mobile inference framework
     * Uses FlatBuffers serialization
     * File extensions: .tflite, .literlm
     * 
     * Note: TFLite LLMs are rare and typically smaller models
     * .literlm is Google's LiteRT LLM format
     */
    TFLITE,
    
    /**
     * Safetensors - Hugging Face's safe tensor format
     * Primarily used for storage/transfer, not direct inference
     * Would require conversion to another format for inference
     * File extension: .safetensors
     * 
     * Note: Not directly usable for mobile inference without conversion
     */
    SAFETENSORS,
    
    /**
     * Unknown or unsupported format
     */
    UNKNOWN;
    
    companion object {
        /**
         * Detect model format from file name/extension
         */
        fun fromFileName(fileName: String): ModelFormat {
            val lowerName = fileName.lowercase()
            return when {
                lowerName.endsWith(".gguf") -> GGUF
                lowerName.endsWith(".tflite") -> TFLITE
                lowerName.endsWith(".literlm") -> TFLITE  // LiteRT LLM format (legacy)
                lowerName.endsWith(".litertlm") -> TFLITE  // LiteRT LLM format (new)
                lowerName.endsWith(".task") -> TFLITE  // MediaPipe Task bundle
                lowerName.endsWith(".safetensors") -> SAFETENSORS
                else -> UNKNOWN
            }
        }
        
        /**
         * Get file extensions for this format
         */
        fun ModelFormat.getExtensions(): List<String> = when (this) {
            GGUF -> listOf(".gguf")
            TFLITE -> listOf(".tflite", ".literlm", ".litertlm", ".task")
            SAFETENSORS -> listOf(".safetensors")
            UNKNOWN -> emptyList()
        }
        
        /**
         * Check if this format supports direct inference on mobile
         */
        fun ModelFormat.supportsDirectInference(): Boolean = when (this) {
            GGUF -> true      // llama.cpp
            TFLITE -> true    // TFLite runtime
            SAFETENSORS -> false  // Needs conversion
            UNKNOWN -> false
        }
        
        /**
         * Get human-readable format name
         */
        fun ModelFormat.getDisplayName(): String = when (this) {
            GGUF -> "GGUF (llama.cpp)"
            TFLITE -> "LiteRT / TensorFlow Lite"
            SAFETENSORS -> "Safetensors"
            UNKNOWN -> "Unknown Format"
        }
    }
}
