package com.jarvis.os.data.offline

import com.jarvis.os.data.remote.ApiResult
import com.jarvis.os.data.remote.GeminiClient
import com.jarvis.os.data.secure.SecureKeyStore
import com.jarvis.os.domain.ai.AIEngine
import com.jarvis.os.domain.ai.AIEngineResult
import com.jarvis.os.domain.model.ApiDefaults

class OnlineAIEngine(
    private val keyStore: SecureKeyStore,
    private val gemini: GeminiClient
) : AIEngine {

    override val name: String = "Online"

    override suspend fun isAvailable(): Boolean {
        val s = keyStore.current()
        return s.hasGemini
    }

    override suspend fun generate(
        systemPrompt: String,
        userText: String,
        maxTokens: Int
    ): AIEngineResult {
        val settings = keyStore.current()
        val key = settings.geminiKey
        if (key.isNullOrBlank()) {
            return AIEngineResult.Failure(
                "Gemini API key missing. Settings mein daalo.",
                AIEngineResult.FailureKind.INVALID_KEY
            )
        }
        val model = settings.geminiModel.ifBlank { ApiDefaults.GEMINI_MODEL }
        return when (val result = gemini.generateJson(key, model, systemPrompt, userText)) {
            is ApiResult.Success -> AIEngineResult.Success(result.value)
            is ApiResult.Failure -> {
                val msg = result.error.userMessage()
                val kind = when {
                    msg.contains("key", ignoreCase = true) ||
                        msg.contains("401") || msg.contains("403") ->
                        AIEngineResult.FailureKind.INVALID_KEY
                    msg.contains("quota", ignoreCase = true) ||
                        msg.contains("429") ->
                        AIEngineResult.FailureKind.QUOTA
                    msg.contains("timeout", ignoreCase = true) ||
                        msg.contains("network", ignoreCase = true) ->
                        AIEngineResult.FailureKind.TIMEOUT
                    else -> AIEngineResult.FailureKind.UNKNOWN
                }
                AIEngineResult.Failure(msg, kind)
            }
        }
    }
}
