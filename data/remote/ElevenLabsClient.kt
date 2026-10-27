package com.jarvis.os.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
private data class ElevenVoice(val name: String? = null)

@Serializable
private data class TtsRequest(
    val text: String,
    @SerialName("model_id") val modelId: String
)

class ElevenLabsClient(private val http: HttpClient) {

    /** Verifies the key and voice ID together. Returns the voice's display name. */
    suspend fun validate(apiKey: String, voiceId: String): ApiResult<String> = apiCall {
        val response = http.get("$BASE/voices/${voiceId.trim().encodeURLPathPart()}") {
            header(KEY_HEADER, apiKey.trim())
        }
        if (!response.status.isSuccess()) throw ApiException(response.toApiError())
        response.body<ElevenVoice>().name ?: voiceId.trim()
    }

    /**
     * Streams 16-bit mono PCM at [PCM_SAMPLE_RATE] Hz as it is generated.
     * Throws [ApiException] on HTTP errors; the collector decides about fallback.
     */
    fun streamSpeech(
        apiKey: String,
        voiceId: String,
        text: String,
        modelId: String
    ): Flow<ByteArray> = flow {
        http.preparePost("$BASE/text-to-speech/${voiceId.trim().encodeURLPathPart()}/stream") {
            header(KEY_HEADER, apiKey.trim())
            parameter("output_format", PCM_FORMAT)
            contentType(ContentType.Application.Json)
            setBody(TtsRequest(text = text, modelId = modelId))
        }.execute { response ->
            if (!response.status.isSuccess()) throw ApiException(response.toApiError())
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(8 * 1024)
            while (!channel.isClosedForRead) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read > 0) emit(buffer.copyOf(read))
            }
        }
    }

    companion object {
        const val PCM_SAMPLE_RATE = 22_050
        private const val PCM_FORMAT = "pcm_22050"
        private const val BASE = "https://api.elevenlabs.io/v1"
        private const val KEY_HEADER = "xi-api-key"
    }
}
