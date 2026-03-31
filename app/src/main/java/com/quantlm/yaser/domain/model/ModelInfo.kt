package com.quantlm.yaser.domain.model

data class ModelInfo(
    val name: String,
    val filePath: String,
    val size: Long,
    val isLoaded: Boolean = false,
    val metadata: String = "",
    // Vision model support
    val isVisionModel: Boolean = false,
    val mmprojPath: String? = null,
    // True if this is a vision model but mmproj is not yet downloaded
    val isVisionIncomplete: Boolean = false
)
