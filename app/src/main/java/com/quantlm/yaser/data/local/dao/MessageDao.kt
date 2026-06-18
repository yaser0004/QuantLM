package com.quantlm.yaser.data.local.dao

import androidx.room.*
import com.quantlm.yaser.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

data class ConversationLastMessageRow(
    val conversationId: Long,
    val content: String
)

@Dao
interface MessageDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity): Long
    
    @Update
    suspend fun update(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        """
        DELETE FROM messages
        WHERE conversationId = :conversationId AND id > :afterMessageId
        """
    )
    suspend fun deleteMessagesAfter(conversationId: Long, afterMessageId: Long)

    @Query(
        """
        SELECT m.conversationId AS conversationId, m.content AS content FROM messages m
        INNER JOIN (
            SELECT conversationId, MAX(id) AS maxId FROM messages GROUP BY conversationId
        ) t ON m.conversationId = t.conversationId AND m.id = t.maxId
        """
    )
    fun observeLastMessagesPerConversation(): Flow<List<ConversationLastMessageRow>>
    
    @Delete
    suspend fun delete(message: MessageEntity)
    
    // id is the tiebreaker: rows inserted within the same millisecond (e.g. a
    // model-change marker right after a message) must keep insertion order.
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC, id ASC")
    fun getByConversationId(conversationId: Long): Flow<List<MessageEntity>>
    
    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteByConversationId(conversationId: Long)
    
    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}
