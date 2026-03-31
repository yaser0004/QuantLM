package com.quantlm.yaser.domain.util

object ChatNameGenerator {
    
    /**
     * Generates a conversation title from the first user message.
     * Takes the first 50 characters or up to the first sentence.
     */
    fun generateTitle(userMessage: String): String {
        if (userMessage.isBlank()) {
            return "New Chat"
        }
        
        val cleaned = userMessage.trim()
        
        // Try to find the first sentence
        val firstSentence = cleaned.split(Regex("[.!?]")).firstOrNull()?.trim()
        
        return when {
            // If first sentence is short enough, use it
            firstSentence != null && firstSentence.length <= 50 -> firstSentence
            
            // Otherwise take first 50 chars and add ellipsis
            cleaned.length > 50 -> cleaned.take(50).trim() + "..."
            
            // Use the whole message if it's short
            else -> cleaned
        }
    }
    
    /**
     * Alternative: Extract key topic from message (simple keyword extraction)
     */
    fun generateSmartTitle(userMessage: String): String {
        val title = generateTitle(userMessage)
        
        // Remove common question words for cleaner titles
        val cleanedTitle = title
            .replace(Regex("^(what|how|why|when|where|who|can|could|should|would|is|are|do|does)\\s+", RegexOption.IGNORE_CASE), "")
            .replaceFirstChar { it.uppercase() }
        
        return if (cleanedTitle.isNotBlank()) cleanedTitle else title
    }
}
