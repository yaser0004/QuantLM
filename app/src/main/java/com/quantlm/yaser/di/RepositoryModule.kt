package com.quantlm.yaser.di

import com.quantlm.yaser.data.repository.ChatRepositoryImpl
import com.quantlm.yaser.data.repository.InferenceRepositoryImpl
import com.quantlm.yaser.data.repository.ModelRepositoryImpl
import com.quantlm.yaser.data.tools.ToolExecutorImpl
import com.quantlm.yaser.domain.model.ToolExecutor
import com.quantlm.yaser.domain.repository.ChatRepository
import com.quantlm.yaser.domain.repository.InferenceRepository
import com.quantlm.yaser.domain.repository.ModelRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    
    @Binds
    @Singleton
    abstract fun bindChatRepository(
        chatRepositoryImpl: ChatRepositoryImpl
    ): ChatRepository
    
    @Binds
    @Singleton
    abstract fun bindModelRepository(
        modelRepositoryImpl: ModelRepositoryImpl
    ): ModelRepository
    
    @Binds
    @Singleton
    abstract fun bindInferenceRepository(
        inferenceRepositoryImpl: InferenceRepositoryImpl
    ): InferenceRepository
    
    @Binds
    @Singleton
    abstract fun bindToolExecutor(
        toolExecutorImpl: ToolExecutorImpl
    ): ToolExecutor
}
