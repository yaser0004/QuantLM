package com.quantlm.yaser.domain.model

data class ModelProfile(
    val family: ModelFamily,
    val defaultTemperature: Float,
    val repetitionPenalty: Float,
    val minP: Float,
    val topP: Float,
    val topK: Int,
    val stopTokens: List<String>
)
