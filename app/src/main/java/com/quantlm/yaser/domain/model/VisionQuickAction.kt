package com.quantlm.yaser.domain.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Quick action prompts for common vision model tasks.
 * These provide optimized prompts for specific use cases.
 */
enum class VisionQuickAction(
    val displayName: String,
    val icon: ImageVector,
    val promptText: String,
    val description: String
) {
    DESCRIBE(
        displayName = "Describe",
        icon = Icons.Default.Description,
        promptText = "Describe this image in detail. Include the main subjects, colors, composition, and any notable elements.",
        description = "Get a detailed description of the image"
    ),
    
    READ_TEXT(
        displayName = "Read Text",
        icon = Icons.Default.Description,
        promptText = "Extract and read all the text visible in this image. Format the text clearly and preserve the original structure if possible.",
        description = "Extract text from documents, signs, or screenshots"
    ),
    
    ANALYZE(
        displayName = "Analyze",
        icon = Icons.Default.Search,
        promptText = "Analyze this image thoroughly. What is happening? What can you infer about the context, mood, or meaning? Provide insights and observations.",
        description = "Deep analysis of content and context"
    ),
    
    IDENTIFY(
        displayName = "Identify",
        icon = Icons.Default.Search,
        promptText = "Identify the main objects, people, places, or items in this image. List them with brief descriptions.",
        description = "Identify objects, places, or items"
    ),
    
    TRANSLATE(
        displayName = "Translate",
        icon = Icons.Default.Description,
        promptText = "Read any text in this image and translate it to English. Provide the original text and the translation.",
        description = "Translate text found in the image"
    ),
    
    QUESTION(
        displayName = "Ask",
        icon = Icons.Default.Help,
        promptText = "",  // User provides their own question
        description = "Ask your own question about the image"
    )
}
