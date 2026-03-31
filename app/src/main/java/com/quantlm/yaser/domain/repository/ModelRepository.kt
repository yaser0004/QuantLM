package com.quantlm.yaser.domain.repository

import com.quantlm.yaser.domain.model.ModelConfig
import com.quantlm.yaser.domain.model.ModelInfo
import com.quantlm.yaser.domain.model.ModelLoadingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface ModelRepository {
    
    /** Current loading state (loading, unloading, switching, idle) */
    val loadingState: StateFlow<ModelLoadingState>
    
    suspend fun loadModel(config: ModelConfig): Result<Unit>
    
    suspend fun unloadModel()
    
    fun getLoadedModel(): Flow<ModelInfo?>
    
    suspend fun getAvailableModels(): List<ModelInfo>
    
    suspend fun deleteModel(filePath: String): Result<Unit>
    
    suspend fun getModelInfo(filePath: String): ModelInfo?
}
