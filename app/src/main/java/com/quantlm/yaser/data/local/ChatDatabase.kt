package com.quantlm.yaser.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.quantlm.yaser.data.local.dao.ConversationDao
import com.quantlm.yaser.data.local.dao.MessageDao
import com.quantlm.yaser.data.local.entity.ConversationEntity
import com.quantlm.yaser.data.local.entity.MessageEntity

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add imagePath column for vision model support
                database.execSQL("ALTER TABLE messages ADD COLUMN imagePath TEXT")
            }
        }
        
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add imagePaths column for multiple images support
                database.execSQL("ALTER TABLE messages ADD COLUMN imagePaths TEXT")
                // Migrate existing imagePath data to imagePaths as JSON array
                database.execSQL("""
                    UPDATE messages 
                    SET imagePaths = CASE 
                        WHEN imagePath IS NOT NULL AND imagePath != '' 
                        THEN '["' || imagePath || '"]' 
                        ELSE NULL 
                    END
                """)
            }
        }
        
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add generation statistics columns
                database.execSQL("ALTER TABLE messages ADD COLUMN generationTimeMs INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE messages ADD COLUMN tokensGenerated INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE messages ADD COLUMN tokensPerSecond REAL NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE messages ADD COLUMN promptTokens INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE messages ADD COLUMN memoryUsedBytes INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE messages ADD COLUMN modelName TEXT")
                database.execSQL("ALTER TABLE messages ADD COLUMN generationTemperature REAL NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE messages ADD COLUMN generationTopP REAL NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE messages ADD COLUMN generationTopK INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE messages ADD COLUMN generationMaxTokens INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE messages ADD COLUMN wasVisionRequest INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE messages ADD COLUMN generationImageCount INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
    }
}
