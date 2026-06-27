package com.quantlm.yaser.data.repository

import com.quantlm.yaser.data.local.dao.ConversationDao
import com.quantlm.yaser.data.local.dao.MessageDao
import com.quantlm.yaser.data.diagnostics.AppEventLogger
import com.quantlm.yaser.data.local.entity.ConversationEntity
import com.quantlm.yaser.data.local.entity.MessageEntity
import com.quantlm.yaser.domain.model.Conversation
import com.quantlm.yaser.domain.model.GenerationStats
import com.quantlm.yaser.domain.model.Message
import com.quantlm.yaser.domain.model.WebSourceRef
import com.quantlm.yaser.domain.repository.ChatRepository
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class ChatRepositoryImpl @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) : ChatRepository {

    companion object {
        private const val TAG = "ChatRepository"
        private val gson = Gson()
        private val webSourceListType = object : TypeToken<List<WebSourceRef>>() {}.type
    }
    
    override suspend fun createConversation(title: String, modelName: String): Long {
        val entity = ConversationEntity(
            title = title,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            modelName = modelName
        )
        val id = conversationDao.insert(entity)
        AppEventLogger.info(
            component = TAG,
            action = "conversation_created",
            details = "conversationId=$id, modelName=$modelName, titleLength=${title.length}"
        )
        return id
    }
    
    override suspend fun getConversation(id: Long): Conversation? {
        val conversation = conversationDao.getById(id)?.toDomain()
        if (conversation == null) {
            AppEventLogger.debug(component = TAG, action = "conversation_lookup_miss", details = "conversationId=$id")
        }
        return conversation
    }
    
    override fun getAllConversations(): Flow<List<Conversation>> {
        return conversationDao.getAll().map { list ->
            list.map { it.toDomain() }
        }
    }
    
    override suspend fun updateConversation(conversation: Conversation) {
        conversationDao.update(conversation.toEntity())
        AppEventLogger.info(
            component = TAG,
            action = "conversation_updated",
            details = "conversationId=${conversation.id}, titleLength=${conversation.title.length}"
        )
    }
    
    override suspend fun updateConversationTimestamp(id: Long) {
        conversationDao.updateTimestamp(id, System.currentTimeMillis())
        AppEventLogger.debug(component = TAG, action = "conversation_timestamp_updated", details = "conversationId=$id")
    }
    
    override suspend fun deleteConversation(id: Long) {
        conversationDao.deleteById(id)
        AppEventLogger.info(component = TAG, action = "conversation_deleted", details = "conversationId=$id")
    }
    
    override suspend fun insertMessage(message: Message): Long {
        val id = messageDao.insert(message.toEntity())
        AppEventLogger.debug(
            component = TAG,
            action = "message_inserted",
            details = "messageId=$id, conversationId=${message.conversationId}, isUser=${message.isUser}, length=${message.content.length}, imageCount=${message.imagePaths.size}"
        )
        return id
    }
    
    override fun getMessagesForConversation(conversationId: Long): Flow<List<Message>> {
        return messageDao.getByConversationId(conversationId).map { list ->
            list.map { it.toDomain() }
        }
    }
    
    override suspend fun deleteAllMessages(conversationId: Long) {
        messageDao.deleteByConversationId(conversationId)
        AppEventLogger.info(component = TAG, action = "conversation_messages_deleted", details = "conversationId=$conversationId")
    }
    
    override suspend fun clearAllHistory() {
        conversationDao.deleteAll()
        messageDao.deleteAll()
        AppEventLogger.warn(component = TAG, action = "all_chat_history_cleared")
    }

    override fun observeLastMessagesPerConversation(): Flow<List<Pair<Long, String>>> {
        return messageDao.observeLastMessagesPerConversation().map { rows ->
            rows.map { it.conversationId to it.content }
        }
    }

    override suspend fun deleteMessage(messageId: Long) {
        messageDao.deleteById(messageId)
        AppEventLogger.info(component = TAG, action = "message_deleted", details = "messageId=$messageId")
    }

    override suspend fun updateMessage(message: Message) {
        messageDao.update(message.toEntity())
        AppEventLogger.info(
            component = TAG,
            action = "message_updated",
            details = "messageId=${message.id}, conversationId=${message.conversationId}, length=${message.content.length}"
        )
    }

    override suspend fun insertModelChangeMarker(conversationId: Long, newModelName: String) {
        val entity = MessageEntity(
            conversationId = conversationId,
            content = "",
            isUser = false,
            timestamp = System.currentTimeMillis(),
            isModelChangeMarker = true,
            markerModelName = newModelName,
        )
        val id = messageDao.insert(entity)
        AppEventLogger.info(
            component = TAG,
            action = "model_change_marker_inserted",
            details = "conversationId=$conversationId, newModel=$newModelName, markerId=$id"
        )
    }

    override suspend fun deleteMessagesAfter(conversationId: Long, afterMessageId: Long) {
        messageDao.deleteMessagesAfter(conversationId, afterMessageId)
        AppEventLogger.info(
            component = TAG,
            action = "messages_deleted_after",
            details = "conversationId=$conversationId, afterMessageId=$afterMessageId"
        )
    }

    override suspend fun setActiveVersion(parentId: Long, activeId: Long) {
        messageDao.setActiveVersion(parentId, activeId)
        AppEventLogger.info(
            component = TAG,
            action = "set_active_version",
            details = "parentId=$parentId, activeId=$activeId"
        )
    }
    
    private fun ConversationEntity.toDomain() = Conversation(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        modelName = modelName
    )
    
    private fun Conversation.toEntity() = ConversationEntity(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        modelName = modelName
    )
    
    private fun MessageEntity.toDomain(): Message {
        // Parse imagePaths JSON or fall back to legacy imagePath
        val paths = when {
            !imagePaths.isNullOrBlank() -> parseImagePathsJson(imagePaths)
            !imagePath.isNullOrBlank() -> listOf(imagePath)
            else -> emptyList()
        }
        
        // Build generation stats if available (non-zero values)
        val stats = if (generationTimeMs > 0 || tokensGenerated > 0) {
            val imageCountForStats =
                if (generationImageCount > 0) generationImageCount else paths.size
            GenerationStats(
                generationTimeMs = generationTimeMs,
                tokensGenerated = tokensGenerated,
                tokensPerSecond = tokensPerSecond,
                promptTokens = promptTokens,
                totalTokens = promptTokens + tokensGenerated,
                memoryUsedBytes = memoryUsedBytes,
                modelName = modelName ?: "",
                temperature = generationTemperature,
                topP = generationTopP,
                topK = generationTopK,
                maxTokens = generationMaxTokens,
                wasVisionRequest = wasVisionRequest,
                imageCount = imageCountForStats,
                backend = generationBackend ?: "",
                modelFormat = generationModelFormat ?: ""
            )
        } else null
        
        val audioPathsList = if (!audioPaths.isNullOrBlank()) parseImagePathsJson(audioPaths) else emptyList()
        return Message(
            id = id,
            conversationId = conversationId,
            content = content,
            isUser = isUser,
            timestamp = timestamp,
            tokenCount = tokenCount,
            imagePaths = paths,
            generationStats = stats,
            thinkingContent = thinkingContent,
            thoughtSummary = thoughtSummary,
            audioPaths = audioPathsList,
            sources = parseWebSourcesJson(webSources),
            isModelChangeMarker = isModelChangeMarker,
            markerModelName = markerModelName,
            parentMessageId = parentMessageId,
            isActiveVersion = isActiveVersion,
            // versionIndex/versionCount are projection-only (ChatViewModel) — leave default.
        )
    }

    private fun parseWebSourcesJson(json: String?): List<WebSourceRef> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            gson.fromJson(json, webSourceListType) ?: emptyList()
        } catch (e: Exception) {
            AppEventLogger.warn(
                component = TAG,
                action = "parse_web_sources_failed",
                details = "reason=${e.message ?: "unknown"}"
            )
            emptyList()
        }
    }

    private fun Message.toEntity(): MessageEntity {
        // Convert imagePaths list to JSON string
        val pathsJson = if (imagePaths.isNotEmpty()) {
            imagePaths.joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")
        } else null
        
        val audioJson = if (audioPaths.isNotEmpty()) {
            audioPaths.joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")
        } else null
        return MessageEntity(
            id = id,
            conversationId = conversationId,
            content = content,
            isUser = isUser,
            timestamp = timestamp,
            tokenCount = tokenCount,
            imagePath = imagePaths.firstOrNull(), // Keep legacy field for backward compat
            imagePaths = pathsJson,
            // Generation stats
            generationTimeMs = generationStats?.generationTimeMs ?: 0,
            tokensGenerated = generationStats?.tokensGenerated ?: 0,
            tokensPerSecond = generationStats?.tokensPerSecond ?: 0f,
            promptTokens = generationStats?.promptTokens ?: 0,
            memoryUsedBytes = generationStats?.memoryUsedBytes ?: 0,
            modelName = generationStats?.modelName,
            generationTemperature = generationStats?.temperature ?: 0f,
            generationTopP = generationStats?.topP ?: 0f,
            generationTopK = generationStats?.topK ?: 0,
            generationMaxTokens = generationStats?.maxTokens ?: 0,
            wasVisionRequest = generationStats?.wasVisionRequest ?: false,
            generationImageCount = generationStats?.imageCount ?: 0,
            generationBackend = generationStats?.backend,
            generationModelFormat = generationStats?.modelFormat,
            thinkingContent = thinkingContent,
            thoughtSummary = thoughtSummary,
            audioPaths = audioJson,
            webSources = if (sources.isNotEmpty()) gson.toJson(sources) else null,
            isModelChangeMarker = isModelChangeMarker,
            markerModelName = markerModelName,
            parentMessageId = parentMessageId,
            isActiveVersion = isActiveVersion,
            // versionIndex/versionCount are transient and intentionally not persisted.
        )
    }
    
    private fun parseImagePathsJson(json: String): List<String> {
        return try {
            // Simple JSON array parser: ["path1","path2"]
            json.trim()
                .removePrefix("[")
                .removeSuffix("]")
                .split("\",\"")
                .map { it.trim().removePrefix("\"").removeSuffix("\"") }
                .filter { it.isNotBlank() }
        } catch (e: Exception) {
            AppEventLogger.warn(
                component = TAG,
                action = "parse_image_paths_failed",
                details = "reason=${e.message ?: "unknown"}"
            )
            emptyList()
        }
    }
}
