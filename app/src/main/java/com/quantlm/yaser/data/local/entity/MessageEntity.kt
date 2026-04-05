package com.quantlm.yaser.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val conversationId: Long,
    val content: String,
    val isUser: Boolean,
    val timestamp: Long,
    val tokenCount: Int = 0,
    val imagePath: String? = null,  // Legacy: single image path (for migration compatibility)
    val imagePaths: String? = null,  // JSON array of image paths for multiple images
    // Generation statistics (stored as JSON for AI messages)
    val generationTimeMs: Long = 0,           // Time taken to generate response
    val tokensGenerated: Int = 0,              // Tokens in the response
    val tokensPerSecond: Float = 0f,           // Generation speed
    val promptTokens: Int = 0,                 // Tokens in the prompt
    val memoryUsedBytes: Long = 0,             // Memory during generation
    val modelName: String? = null,             // Model used for generation
    val generationTemperature: Float = 0f,     // Temperature setting
    val generationTopP: Float = 0f,            // Top-P setting
    val generationTopK: Int = 0,               // Top-K setting
    val generationMaxTokens: Int = 0,          // Max tokens setting
    val wasVisionRequest: Boolean = false,     // Was this a vision request
    val generationImageCount: Int = 0,         // Images in prompt (assistant stats; user message paths are separate)
    val generationBackend: String? = null,     // Backend used (CPU/GPU)
    val generationModelFormat: String? = null  // Model format (GGUF, TASK, etc.)
)
