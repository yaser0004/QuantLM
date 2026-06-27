package com.quantlm.yaser.data.local.entity

import androidx.room.ColumnInfo
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
    val generationModelFormat: String? = null, // Model format (GGUF, TASK, etc.)
    // Phase 2 (§3.6): persisted thinking content extracted from <thinking> blocks.
    val thinkingContent: String? = null,
    val thoughtSummary: String? = null,
    // Phase 2 (§3.8): JSON array of audio file paths attached to the message.
    val audioPaths: String? = null,
    // Web Search: Gson-serialized JSON array of WebSourceRef used to ground the
    // answer. Null for offline messages and all pre-existing rows.
    val webSources: String? = null,
    // Model switch: marks the point in a conversation where the model changed.
    // Invisible to the LLM; used as a context boundary and rendered as a
    // visual separator in the chat UI. Defaults to 0 (false) for all
    // existing rows — fully additive, no data loss.
    @ColumnInfo(defaultValue = "0")
    val isModelChangeMarker: Boolean = false,
    // Nullable, no SQL default — matches MIGRATION_9_10's plain `ADD COLUMN
    // markerModelName TEXT`. A @ColumnInfo(defaultValue = "") here would make
    // Room's post-migration schema validation fail (entity expects DEFAULT '',
    // migrated DB has none) and crash the app on first DB access at launch.
    val markerModelName: String? = null,
    // Versioning (ChatGPT-style regenerate). The user-message id this assistant
    // answer replies to; null for user messages, markers, and all legacy rows.
    // Nullable INTEGER with NO @ColumnInfo default — matches MIGRATION_10_11's
    // plain `ADD COLUMN parentMessageId INTEGER` (same rule as markerModelName).
    val parentMessageId: Long? = null,
    // Only the active sibling of a turn reaches the model and the UI. Defaults
    // to 1 so every legacy/user/marker row stays visible — so this MUST carry
    // @ColumnInfo(defaultValue = "1") to match `... INTEGER NOT NULL DEFAULT 1`.
    @ColumnInfo(defaultValue = "1")
    val isActiveVersion: Boolean = true,
)
