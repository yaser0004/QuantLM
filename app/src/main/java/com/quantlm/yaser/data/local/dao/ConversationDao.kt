package com.quantlm.yaser.data.local.dao

import androidx.room.*
import com.quantlm.yaser.data.local.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: ConversationEntity): Long
    
    @Update
    suspend fun update(conversation: ConversationEntity)
    
    @Query("UPDATE conversations SET updatedAt = :timestamp WHERE id = :id")
    suspend fun updateTimestamp(id: Long, timestamp: Long)
    
    @Delete
    suspend fun delete(conversation: ConversationEntity)
    
    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: Long): ConversationEntity?
    
    @Query(
        """
        SELECT * FROM conversations
        WHERE EXISTS (
            SELECT 1 FROM messages WHERE messages.conversationId = conversations.id
        )
        ORDER BY updatedAt DESC
        """
    )
    fun getAll(): Flow<List<ConversationEntity>>
    
    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: Long)
    
    @Query("DELETE FROM conversations")
    suspend fun deleteAll()
}
