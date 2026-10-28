package com.jarvis.os.domain.ai

/**
 * Common interface for online (Gemini) and offline (local GGUF) engines.
 * UI / TaskEngine should depend on this, not on a specific backend.
 */
interface AIEngine {
    val name: String
    suspend fun isAvailable(): Boolean
    suspend fun generate(
        systemPrompt: String,
        userText: String,
        maxTokens: Int = 256
    ): AIEngineResult
}

sealed class AIEngineResult {
    data class Success(val text: String) : AIEngineResult()
    data class Failure(
        val message: String,
        val kind: FailureKind = FailureKind.UNKNOWN
    ) : AIEngineResult()

    enum class FailureKind {
        NO_NETWORK,
        INVALID_KEY,
        QUOTA,
        TIMEOUT,
        MODEL_NOT_INSTALLED,
        MODEL_LOAD_FAILED,
        INSUFFICIENT_MEMORY,
        UNSUPPORTED,
        UNKNOWN
    }
}
