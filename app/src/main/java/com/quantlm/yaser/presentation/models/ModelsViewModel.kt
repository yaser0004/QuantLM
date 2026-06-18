package com.quantlm.yaser.presentation.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quantlm.yaser.data.diagnostics.AppEventLogger
import com.quantlm.yaser.domain.model.ModelInfo
import com.quantlm.yaser.domain.model.ModelLoadingState
import com.quantlm.yaser.domain.repository.ModelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModelsViewModel @Inject constructor(
    private val modelRepository: ModelRepository
) : ViewModel() {

    private companion object {
        const val TAG = "ModelsViewModel"
    }
    
    private val _availableModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val availableModels = _availableModels.asStateFlow()
    
    val loadedModel = modelRepository.getLoadedModel()
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
    
    /** Loading state exposed from the repository for UI indicators */
    val modelLoadingState: StateFlow<ModelLoadingState> = modelRepository.loadingState
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()
    
    init {
        refreshModels()
    }
    
    fun refreshModels() {
        AppEventLogger.info(component = TAG, action = "refresh_models_requested")
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val models = modelRepository.getAvailableModels()
                _availableModels.value = models
                AppEventLogger.info(
                    component = TAG,
                    action = "refresh_models_complete",
                    details = "count=${models.size}"
                )
            } catch (e: Exception) {
                _errorMessage.value = e.message
                AppEventLogger.error(
                    component = TAG,
                    action = "refresh_models_failed",
                    details = "reason=${e.message ?: "unknown"}",
                    throwable = e
                )
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteModel(filePath: String) {
        AppEventLogger.info(component = TAG, action = "delete_model_requested", details = "path=$filePath")
        viewModelScope.launch {
            modelRepository.deleteModel(filePath).onSuccess {
                AppEventLogger.info(component = TAG, action = "delete_model_success", details = "path=$filePath")
                refreshModels()
            }.onFailure {
                _errorMessage.value = it.message
                AppEventLogger.error(
                    component = TAG,
                    action = "delete_model_failed",
                    details = "path=$filePath, reason=${it.message ?: "unknown"}",
                    throwable = it
                )
            }
        }
    }

    fun unloadModel() {
        AppEventLogger.info(component = TAG, action = "unload_model_requested")
        viewModelScope.launch {
            try {
                modelRepository.unloadModel()
                AppEventLogger.info(component = TAG, action = "unload_model_success")
            } catch (e: Exception) {
                _errorMessage.value = e.message
                AppEventLogger.error(
                    component = TAG,
                    action = "unload_model_failed",
                    details = "reason=${e.message ?: "unknown"}",
                    throwable = e
                )
            }
        }
    }

    fun clearError() {
        AppEventLogger.debug(component = TAG, action = "clear_error")
        _errorMessage.value = null
    }
}
