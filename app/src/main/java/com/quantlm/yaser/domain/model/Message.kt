package com.quantlm.yaser.domain.model

data class Message(
    val id: Long = 0,
    val conversationId: Long,
    val content: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val tokenCount: Int = 0,
    val imagePaths: List<String> = emptyList(),  // Paths to attached images for vision models
    val generationStats: GenerationStats? = null,  // Generation statistics (AI messages only)
    // Phase 2 (§3.6): chain-of-thought block extracted from `<thinking>...</thinking>`
    // during streaming. Null when reasoning is off or the model emits no block.
    val thinkingContent: String? = null,
    val thoughtSummary: String? = null,
    // Phase 2 (§3.8): paths to attached audio files for audio-capable models.
    val audioPaths: List<String> = emptyList(),
    // Web Search: sources used to ground this answer. Non-empty only for
    // assistant messages produced while the Web Search toggle was on. Surfaced
    // in the collapsible "Sources" UI under the message.
    val sources: List<WebSourceRef> = emptyList(),
    // Model switch: marks the point in the conversation where the inference
    // model changed. Not shown to the LLM; used only for context boundary
    // enforcement and as a visual separator in the chat UI.
    val isModelChangeMarker: Boolean = false,
    val markerModelName: String? = null,
) {
    // Backward compatibility - get first image path if any
    val imagePath: String? get() = imagePaths.firstOrNull()
    
    // Helper to check if this message has images
    val hasImages: Boolean get() = imagePaths.isNotEmpty()
    
    // Helper to check if generation stats are available
    val hasGenerationStats: Boolean get() = generationStats?.hasStats == true
}
