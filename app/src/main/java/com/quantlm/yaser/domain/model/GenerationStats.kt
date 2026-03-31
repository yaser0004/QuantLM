package com.quantlm.yaser.domain.model

/**
 * Statistics about the generation process for a message.
 * Only applicable to AI-generated messages.
 * 
 * Includes performance metrics inspired by Google AI Edge Gallery:
 * - TTFT (Time To First Token)
 * - Decode speed (tokens/second)
 * - Prefill latency
 * - Total latency
 */
data class GenerationStats(
    val generationTimeMs: Long = 0,           // Total time taken to generate the response in milliseconds
    val tokensGenerated: Int = 0,              // Number of tokens generated
    val tokensPerSecond: Float = 0f,           // Generation speed (tokens/sec) - decode speed
    val promptTokens: Int = 0,                 // Number of tokens in the input prompt
    val totalTokens: Int = 0,                  // Total tokens (prompt + generated)
    val memoryUsedBytes: Long = 0,             // Memory used during generation
    val modelName: String = "",                // Name of the model used
    val temperature: Float = 0f,               // Temperature setting used
    val topP: Float = 0f,                      // Top-P setting used
    val topK: Int = 0,                         // Top-K setting used
    val maxTokens: Int = 0,                    // Max tokens setting used
    val wasVisionRequest: Boolean = false,     // Whether this was a vision/multimodal request
    val imageCount: Int = 0,                   // Number of images processed (for vision)
    // Performance benchmarking metrics (Gallery-inspired)
    val timeToFirstTokenMs: Long = 0,          // TTFT: Time from request to first token
    val prefillTimeMs: Long = 0,               // Time to process input/prompt (prefill phase)
    val decodeTimeMs: Long = 0,                // Time spent generating tokens (decode phase)
    val totalLatencyMs: Long = 0,              // Total end-to-end latency
    val peakMemoryBytes: Long = 0,             // Peak memory usage during generation
    val backend: String = "",                  // Inference backend used (CPU, GPU, NPU)
    val modelFormat: String = ""               // Model format (GGUF, LiteRT, etc.)
) {
    /**
     * Format generation time for display
     */
    fun formatGenerationTime(): String {
        return when {
            generationTimeMs < 1000 -> "${generationTimeMs}ms"
            generationTimeMs < 60000 -> String.format("%.1fs", generationTimeMs / 1000f)
            else -> {
                val minutes = generationTimeMs / 60000
                val seconds = (generationTimeMs % 60000) / 1000
                "${minutes}m ${seconds}s"
            }
        }
    }
    
    /**
     * Format tokens per second for display
     */
    fun formatTokensPerSecond(): String {
        return String.format("%.1f tokens/s", tokensPerSecond)
    }
    
    /**
     * Format TTFT (Time To First Token) for display
     */
    fun formatTimeToFirstToken(): String {
        return when {
            timeToFirstTokenMs < 1000 -> "${timeToFirstTokenMs}ms"
            else -> String.format("%.2fs", timeToFirstTokenMs / 1000f)
        }
    }
    
    /**
     * Format prefill time for display
     */
    fun formatPrefillTime(): String {
        return when {
            prefillTimeMs < 1000 -> "${prefillTimeMs}ms"
            else -> String.format("%.2fs", prefillTimeMs / 1000f)
        }
    }
    
    /**
     * Calculate prefill speed (tokens/second) if we have prompt tokens
     */
    fun calculatePrefillSpeed(): Float {
        return if (prefillTimeMs > 0 && promptTokens > 0) {
            (promptTokens * 1000f) / prefillTimeMs
        } else 0f
    }
    
    /**
     * Format prefill speed for display
     */
    fun formatPrefillSpeed(): String {
        val speed = calculatePrefillSpeed()
        return if (speed > 0) String.format("%.1f tokens/s", speed) else "N/A"
    }
    
    /**
     * Calculate decode speed (output tokens per second)
     */
    fun calculateDecodeSpeed(): Float {
        return if (decodeTimeMs > 0 && tokensGenerated > 0) {
            (tokensGenerated * 1000f) / decodeTimeMs
        } else tokensPerSecond
    }
    
    /**
     * Format decode speed for display
     */
    fun formatDecodeSpeed(): String {
        val speed = calculateDecodeSpeed()
        return String.format("%.1f tokens/s", speed)
    }
    
    /**
     * Format memory usage for display
     */
    fun formatMemoryUsage(): String {
        return formatBytes(memoryUsedBytes)
    }
    
    /**
     * Format peak memory for display
     */
    fun formatPeakMemory(): String {
        return formatBytes(peakMemoryBytes)
    }
    
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes <= 0 -> "N/A"
            bytes < 1024 -> "${bytes} B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024f)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024f * 1024f))
            else -> String.format("%.2f GB", bytes / (1024f * 1024f * 1024f))
        }
    }
    
    /**
     * Get a summary of performance metrics for display
     */
    fun getPerformanceSummary(): String {
        val parts = mutableListOf<String>()
        
        if (timeToFirstTokenMs > 0) {
            parts.add("TTFT: ${formatTimeToFirstToken()}")
        }
        
        val decodeSpeed = calculateDecodeSpeed()
        if (decodeSpeed > 0) {
            parts.add("Decode: ${formatDecodeSpeed()}")
        }
        
        if (tokensGenerated > 0) {
            parts.add("$tokensGenerated tokens")
        }
        
        if (generationTimeMs > 0) {
            parts.add("Total: ${formatGenerationTime()}")
        }
        
        return parts.joinToString(" • ")
    }
    
    /**
     * Check if stats are available (non-default values)
     */
    val hasStats: Boolean get() = generationTimeMs > 0 || tokensGenerated > 0
    
    /**
     * Check if performance benchmarking data is available
     */
    val hasBenchmarkData: Boolean get() = timeToFirstTokenMs > 0 || prefillTimeMs > 0 || decodeTimeMs > 0
}

/**
 * Aggregated benchmark statistics across multiple generations.
 * Useful for comparing model performance over time.
 */
data class BenchmarkSummary(
    val modelName: String,
    val modelFormat: String,
    val backend: String,
    val totalGenerations: Int,
    val avgTimeToFirstTokenMs: Float,
    val avgDecodeSpeed: Float,           // tokens/second
    val avgPrefillSpeed: Float,          // tokens/second
    val avgTotalLatencyMs: Float,
    val avgTokensGenerated: Float,
    val minTTFT: Long,
    val maxTTFT: Long,
    val minDecodeSpeed: Float,
    val maxDecodeSpeed: Float
) {
    companion object {
        /**
         * Create a benchmark summary from a list of generation stats
         */
        fun fromStats(stats: List<GenerationStats>): BenchmarkSummary? {
            if (stats.isEmpty()) return null
            
            val first = stats.first()
            val statsWithBenchmarks = stats.filter { it.hasBenchmarkData }
            
            return BenchmarkSummary(
                modelName = first.modelName,
                modelFormat = first.modelFormat,
                backend = first.backend,
                totalGenerations = stats.size,
                avgTimeToFirstTokenMs = statsWithBenchmarks.map { it.timeToFirstTokenMs }.average().toFloat(),
                avgDecodeSpeed = stats.map { it.calculateDecodeSpeed() }.average().toFloat(),
                avgPrefillSpeed = statsWithBenchmarks.map { it.calculatePrefillSpeed() }.filter { it > 0 }.average().toFloat(),
                avgTotalLatencyMs = stats.map { it.generationTimeMs }.average().toFloat(),
                avgTokensGenerated = stats.map { it.tokensGenerated.toFloat() }.average().toFloat(),
                minTTFT = statsWithBenchmarks.minOfOrNull { it.timeToFirstTokenMs } ?: 0,
                maxTTFT = statsWithBenchmarks.maxOfOrNull { it.timeToFirstTokenMs } ?: 0,
                minDecodeSpeed = stats.map { it.calculateDecodeSpeed() }.minOrNull() ?: 0f,
                maxDecodeSpeed = stats.map { it.calculateDecodeSpeed() }.maxOrNull() ?: 0f
            )
        }
    }
    
    /**
     * Get formatted summary for display
     */
    fun getFormattedSummary(): String {
        return buildString {
            appendLine("📊 Benchmark Summary: $modelName")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("Format: $modelFormat | Backend: $backend")
            appendLine("Generations: $totalGenerations")
            appendLine()
            appendLine("⏱️ Time To First Token:")
            appendLine("   Avg: ${String.format("%.0f", avgTimeToFirstTokenMs)}ms")
            appendLine("   Min: ${minTTFT}ms | Max: ${maxTTFT}ms")
            appendLine()
            appendLine("🚀 Decode Speed:")
            appendLine("   Avg: ${String.format("%.1f", avgDecodeSpeed)} tokens/s")
            appendLine("   Min: ${String.format("%.1f", minDecodeSpeed)} | Max: ${String.format("%.1f", maxDecodeSpeed)}")
            appendLine()
            appendLine("📝 Avg Tokens: ${String.format("%.0f", avgTokensGenerated)}")
            appendLine("⏳ Avg Latency: ${String.format("%.0f", avgTotalLatencyMs)}ms")
        }
    }
}
