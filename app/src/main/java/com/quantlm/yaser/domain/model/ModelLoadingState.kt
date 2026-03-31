package com.quantlm.yaser.domain.model

/**
 * Represents the current state of model loading operations.
 * Used to provide visual feedback to users during model loading/unloading.
 */
sealed class ModelLoadingState {
    /** No model operation in progress */
    object Idle : ModelLoadingState()
    
    /** Model is being loaded into memory */
    data class Loading(val modelName: String) : ModelLoadingState()
    
    /** Model is being unloaded from memory */
    data class Unloading(val modelName: String? = null) : ModelLoadingState()
    
    /** Switching from one model to another */
    data class Switching(val fromModel: String?, val toModel: String) : ModelLoadingState()
    
    /** Loading completed successfully */
    data class Loaded(val modelName: String) : ModelLoadingState()
    
    /** Loading failed with an error */
    data class Error(val message: String) : ModelLoadingState()
    
    /** Get a human-readable description of the current state */
    fun getStatusMessage(): String = when (this) {
        is Idle -> ""
        is Loading -> "Loading $modelName..."
        is Unloading -> modelName?.let { "Unloading $it..." } ?: "Unloading model..."
        is Switching -> "Switching to $toModel..."
        is Loaded -> "$modelName loaded"
        is Error -> "Error: $message"
    }
}
