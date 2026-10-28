package com.jarvis.os.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

@Serializable
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val systemInstruction: GeminiContent? = null,
    val generationConfig: GenerationConfig? = null
)

@Serializable
data class GeminiContent(val role: String? = null, val parts: List<GeminiPart>)

@Serializable
data class GeminiPart(
    val text: String? = null,
    val inlineData: InlineData? = null,
    val thought: Boolean? = null
)

@Serializable
data class InlineData(val mimeType: String, val data: String)

@Serializable
data class GenerationConfig(
    val temperature: Float? = null,
    val maxOutputTokens: Int? = null,
    val responseMimeType: String? = null
)

@Serializable
private data class GeminiResponse(
    val candidates: List<Candidate> = emptyList(),
    val promptFeedback: PromptFeedback? = null
)

@Serializable
private data class Candidate(val content: GeminiContent? = null, val finishReason: String? = null)

@Serializable
private data class PromptFeedback(val blockReason: String? = null)

@Serializable
private data class ModelListResponse(val models: List<ModelEntry> = emptyList())

@Serializable
private data class ModelEntry(
    val name: String,
    val supportedGenerationMethods: List<String> = emptyList()
)

class GeminiClient(private val http: HttpClient) {

    suspend fun validate(apiKey: String, model: String): ApiResult<Unit> = apiCall {
        val response = http.get("$BASE/models/${cleanModel(model)}") {
            header(KEY_HEADER, apiKey.trim())
        }
        if (!response.status.isSuccess()) throw ApiException(response.toApiError())
        Unit
    }

    suspend fun listModels(apiKey: String): ApiResult<List<String>> = apiCall {
        val response = http.get("$BASE/models") {
            header(KEY_HEADER, apiKey.trim())
            parameter("pageSize", 200)
        }
        if (!response.status.isSuccess()) throw ApiException(response.toApiError())
        response.body<ModelListResponse>().models
            .filter { "generateContent" in it.supportedGenerationMethods }
            .map { it.name.removePrefix("models/") }
            .filter { it.startsWith("gemini") }
            .sorted()
    }

    /** Strict JSON mode for the agent action planner. */
    suspend fun generateJson(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userText: String,
        screenshotJpegBase64: String? = null
    ): ApiResult<String> {
        val parts = buildList {
            add(GeminiPart(text = userText))
            screenshotJpegBase64?.let { add(GeminiPart(inlineData = InlineData("image/jpeg", it))) }
        }
        val request = GeminiRequest(
            contents = listOf(GeminiContent(role = "user", parts = parts)),
            systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = systemPrompt))),
            generationConfig = GenerationConfig(
                temperature = 0.1f,
                maxOutputTokens = 256,
                responseMimeType = "application/json"
            )
        )
        return generate(apiKey, model, request)
    }

    /** Plain text reply for conversation / chat (no JSON forced). */
    suspend fun generateText(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userText: String,
        maxTokens: Int = 200
    ): ApiResult<String> {
        val request = GeminiRequest(
            contents = listOf(
                GeminiContent(role = "user", parts = listOf(GeminiPart(text = userText)))
            ),
            systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = systemPrompt))),
            generationConfig = GenerationConfig(
                temperature = 0.6f,
                maxOutputTokens = maxTokens
            )
        )
        return generate(apiKey, model, request)
    }

    suspend fun generate(apiKey: String, model: String, request: GeminiRequest): ApiResult<String> {
        var attempt = 0
        while (true) {
            val result = apiCall { postGenerate(apiKey, model, request) }
            if (result is ApiResult.Failure && result.error.isRetryable && attempt < MAX_RETRIES) {
                delay(RETRY_BASE_MS shl attempt)
                attempt++
                continue
            }
            return result
        }
    }

    private suspend fun postGenerate(apiKey: String, model: String, request: GeminiRequest): String {
        val response = http.post("$BASE/models/${cleanModel(model)}:generateContent") {
            header(KEY_HEADER, apiKey.trim())
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (!response.status.isSuccess()) throw ApiException(response.toApiError())

        val body: GeminiResponse = response.body()
        val candidate = body.candidates.firstOrNull()
        val text = candidate?.content?.parts
            ?.filter { it.thought != true }
            ?.mapNotNull { it.text }
            ?.joinToString("")
            ?.trim()
            .orEmpty()

        if (text.isEmpty()) {
            val blockReason = body.promptFeedback?.blockReason
            throw ApiException(
                if (blockReason != null) ApiError(ApiErrorKind.BLOCKED, "Blocked: $blockReason")
                else ApiError(ApiErrorKind.PARSE, "Empty reply (finishReason=${candidate?.finishReason}).")
            )
        }
        return text
    }

    private fun cleanModel(model: String): String = model.trim().removePrefix("models/")

    private companion object {
        const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        const val KEY_HEADER = "x-goog-api-key"
        const val MAX_RETRIES = 1
        const val RETRY_BASE_MS = 400L
    }
}
