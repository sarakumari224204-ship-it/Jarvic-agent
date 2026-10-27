package com.jarvis.os.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class ChatRole { USER, ASSISTANT }

@Serializable
data class ChatMessage(
    val role: ChatRole,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis()
)
