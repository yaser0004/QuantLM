package com.quantlm.yaser.domain.model

data class ModelConfig(
    val name: String,
    val filePath: String,
    val size: Long,
    val contextLength: Int = 2048,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 512,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val nThreads: Int = 4,
    val nGpuLayers: Int = 0,
    val systemPrompt: String = "",
    // Vision model support
    val isVisionModel: Boolean = false,
    val mmprojPath: String? = null
)
