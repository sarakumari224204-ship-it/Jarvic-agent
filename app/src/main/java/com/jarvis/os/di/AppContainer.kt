package com.jarvis.os.di

import android.content.Context
import com.jarvis.os.data.audio.HybridSpeechSynthesizer
import com.jarvis.os.data.remote.ElevenLabsClient
import com.jarvis.os.data.remote.GeminiClient
import com.jarvis.os.data.remote.createHttpClient
import com.jarvis.os.data.secure.ConversationStore
import com.jarvis.os.data.secure.SecureKeyStore
import com.jarvis.os.domain.speech.SpeechSynthesizer
import io.ktor.client.HttpClient

/** Manual dependency container (no Hilt/KSP). Everything is lazy so cold start stays fast. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val httpClient: HttpClient by lazy { createHttpClient() }
    val keyStore: SecureKeyStore by lazy { SecureKeyStore(appContext) }
    val conversationStore: ConversationStore by lazy { ConversationStore(appContext) }
    val geminiClient: GeminiClient by lazy { GeminiClient(httpClient) }
    val elevenLabsClient: ElevenLabsClient by lazy { ElevenLabsClient(httpClient) }
    val speech: SpeechSynthesizer by lazy {
        HybridSpeechSynthesizer(appContext, keyStore, elevenLabsClient)
    }
}
