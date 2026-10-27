package com.jarvis.os.data.remote

import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException

enum class ApiErrorKind {
    OFFLINE, UNAUTHORIZED, NOT_FOUND, RATE_LIMITED, SERVER, BAD_REQUEST, BLOCKED, PARSE, UNKNOWN
}

data class ApiError(val kind: ApiErrorKind, val message: String, val httpCode: Int? = null) {
    val isRetryable: Boolean
        get() = kind == ApiErrorKind.OFFLINE || kind == ApiErrorKind.RATE_LIMITED || kind == ApiErrorKind.SERVER

    /** Short, safe-to-show text for the HUD. */
    fun userMessage(): String = when (kind) {
        ApiErrorKind.OFFLINE -> "No internet connection."
        ApiErrorKind.UNAUTHORIZED -> "The API key was rejected. Please check it."
        ApiErrorKind.NOT_FOUND -> "Not found: $message"
        ApiErrorKind.RATE_LIMITED -> "Rate limit or quota reached. Try again shortly."
        ApiErrorKind.SERVER -> "The service is having problems. Try again shortly."
        ApiErrorKind.BLOCKED -> "The request was blocked by the service's safety filters."
        else -> message
    }
}

class ApiException(val error: ApiError) : Exception(error.message)

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val error: ApiError) : ApiResult<Nothing>
}

fun <T> ApiResult<T>.getOrNull(): T? = (this as? ApiResult.Success)?.value

internal suspend fun <T> apiCall(block: suspend () -> T): ApiResult<T> = try {
    ApiResult.Success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: ApiException) {
    ApiResult.Failure(e.error)
} catch (e: HttpRequestTimeoutException) {
    ApiResult.Failure(ApiError(ApiErrorKind.OFFLINE, "The request timed out."))
} catch (e: IOException) {
    ApiResult.Failure(ApiError(ApiErrorKind.OFFLINE, "Network error."))
} catch (e: SerializationException) {
    ApiResult.Failure(ApiError(ApiErrorKind.PARSE, "Unexpected response format."))
} catch (e: Exception) {
    ApiResult.Failure(ApiError(ApiErrorKind.UNKNOWN, e.message ?: "Unknown error."))
}

/** Maps a non-2xx response (Google or ElevenLabs error body) to an [ApiError]. */
internal suspend fun HttpResponse.toApiError(): ApiError {
    val body = runCatching { bodyAsText() }.getOrDefault("")
    val message = extractErrorMessage(body) ?: status.description
    val code = status.value
    val kind = when {
        code == 401 || code == 403 -> ApiErrorKind.UNAUTHORIZED
        // Gemini reports an invalid key as 400 INVALID_ARGUMENT.
        code == 400 && message.contains("api key", ignoreCase = true) -> ApiErrorKind.UNAUTHORIZED
        code == 404 -> ApiErrorKind.NOT_FOUND
        code == 429 -> ApiErrorKind.RATE_LIMITED
        code in 500..599 -> ApiErrorKind.SERVER
        code in 400..499 -> ApiErrorKind.BAD_REQUEST
        else -> ApiErrorKind.UNKNOWN
    }
    return ApiError(kind, message, code)
}

private fun extractErrorMessage(body: String): String? {
    if (body.isBlank()) return null
    val element = runCatching { AppJson.parseToJsonElement(body) }.getOrNull() ?: return body.take(200)
    val obj = element as? JsonObject ?: return null

    fun JsonElement?.str(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    val error = obj["error"]
    return (error as? JsonObject)?.get("message").str()      // Google: {"error":{"message":...}}
        ?: error.str()
        ?: (obj["detail"] as? JsonObject)?.get("message").str() // ElevenLabs: {"detail":{"message":...}}
        ?: obj["detail"].str()
        ?: obj["message"].str()
}
