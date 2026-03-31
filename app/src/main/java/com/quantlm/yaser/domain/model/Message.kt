package com.quantlm.yaser.domain.model

data class Message(
    val id: Long = 0,
    val conversationId: Long,
    val content: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val tokenCount: Int = 0,
    val imagePaths: List<String> = emptyList(),  // Paths to attached images for vision models
    val generationStats: GenerationStats? = null  // Generation statistics (AI messages only)
) {
    // Backward compatibility - get first image path if any
    val imagePath: String? get() = imagePaths.firstOrNull()
    
    // Helper to check if this message has images
    val hasImages: Boolean get() = imagePaths.isNotEmpty()
    
    // Helper to check if generation stats are available
    val hasGenerationStats: Boolean get() = generationStats?.hasStats == true
}
