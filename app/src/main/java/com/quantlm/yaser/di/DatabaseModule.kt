package com.quantlm.yaser.di

import android.content.Context
import androidx.room.Room
import com.quantlm.yaser.data.local.AppLockManager
import com.quantlm.yaser.data.local.AppPreferences
import com.quantlm.yaser.data.local.ChatDatabase
import com.quantlm.yaser.data.local.GenerationPreferences
import com.quantlm.yaser.data.local.ModelPreferences
import com.quantlm.yaser.data.local.dao.ConversationDao
import com.quantlm.yaser.data.local.dao.MessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    
    @Provides
    @Singleton
    fun provideChatDatabase(
        @ApplicationContext context: Context
    ): ChatDatabase {
        return Room.databaseBuilder(
            context,
            ChatDatabase::class.java,
            "quantlm_chat_database"
        )
            .addMigrations(
                ChatDatabase.MIGRATION_1_2,
                ChatDatabase.MIGRATION_2_3,
                ChatDatabase.MIGRATION_3_4,
                ChatDatabase.MIGRATION_4_5,
                ChatDatabase.MIGRATION_5_6
            )
            .fallbackToDestructiveMigration()
            .build()
    }
    
    @Provides
    @Singleton
    fun provideConversationDao(database: ChatDatabase): ConversationDao {
        return database.conversationDao()
    }
    
    @Provides
    @Singleton
    fun provideMessageDao(database: ChatDatabase): MessageDao {
        return database.messageDao()
    }
    
    @Provides
    @Singleton
    fun provideModelPreferences(
        @ApplicationContext context: Context
    ): ModelPreferences {
        return ModelPreferences(context)
    }
    
    @Provides
    @Singleton
    fun provideGenerationPreferences(
        @ApplicationContext context: Context
    ): GenerationPreferences {
        return GenerationPreferences(context)
    }
    
    @Provides
    @Singleton
    fun provideAppPreferences(
        @ApplicationContext context: Context
    ): AppPreferences {
        return AppPreferences(context)
    }
    
    @Provides
    @Singleton
    fun provideAppLockManager(
        appPreferences: AppPreferences,
        @ApplicationContext context: Context
    ): AppLockManager {
        return AppLockManager(appPreferences, context)
    }
}
