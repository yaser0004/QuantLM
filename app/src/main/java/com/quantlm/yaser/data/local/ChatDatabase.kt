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
    version = 10,
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

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN generationBackend TEXT")
                database.execSQL("ALTER TABLE messages ADD COLUMN generationModelFormat TEXT")
            }
        }

        // Phase 2 (§3.6 / §3.8): add nullable columns for thinking content
        // (reasoning stream parse) and audio attachment paths. Existing rows
        // default to NULL — fully additive, no destructive migration.
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN thinkingContent TEXT")
                database.execSQL("ALTER TABLE messages ADD COLUMN audioPaths TEXT")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN thoughtSummary TEXT")
            }
        }

        // Web Search: nullable column holding the JSON list of sources used to
        // ground an answer. Existing rows default to NULL — fully additive.
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN webSources TEXT")
            }
        }

        // Model-switch markers: two columns that track when the user changed
        // models mid-conversation. Fully additive — existing rows default to
        // 0 / NULL so no data is affected.
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN isModelChangeMarker INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE messages ADD COLUMN markerModelName TEXT")
            }
        }
    }
}
