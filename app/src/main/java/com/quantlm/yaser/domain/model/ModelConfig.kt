package com.quantlm.yaser.domain.model

import com.quantlm.yaser.data.local.GenerationPreferences.HardwareAccelerationMode

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
    val mmprojPath: String? = null,
    // User-selected hardware accelerator (GPU/CPU) resolved from settings at
    // load time. Null when no preference has been read yet — engines then fall
    // back to their prior nGpuLayers-driven / device-default behavior.
    val accelerationMode: HardwareAccelerationMode? = null,
    // When true, bypasses the Gemma-4 CPU lock so GPU is attempted. Opt-in
    // escape hatch — Gemma-4 GPU init crashes natively on many devices.
    val gemma4GpuOverride: Boolean = false
)
