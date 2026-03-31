package com.quantlm.yaser.domain.model

data class Conversation(
    val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val modelName: String = ""
)
