package com.quantlm.yaser.di

import com.quantlm.yaser.data.worker.WorkManagerDownloadRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for WorkManager-related dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object WorkerModule {
    
    // WorkManagerDownloadRepository is already @Singleton annotated with constructor injection
    // This module can be extended for additional WorkManager configurations
}
