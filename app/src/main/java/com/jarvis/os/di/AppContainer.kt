package com.jarvis.os.di

import android.content.Context
import com.jarvis.os.data.audio.HybridSpeechSynthesizer
import com.jarvis.os.data.memory.KnowledgeIndexer
import com.jarvis.os.data.memory.MemoryStore
import com.jarvis.os.data.offline.OfflineAIEngine
import com.jarvis.os.data.offline.OfflineModelManager
import com.jarvis.os.data.offline.OnlineAIEngine
import com.jarvis.os.data.remote.ElevenLabsClient
import com.jarvis.os.data.remote.GeminiClient
import com.jarvis.os.data.remote.createHttpClient
import com.jarvis.os.data.secure.ConversationStore
import com.jarvis.os.data.secure.SecureKeyStore
import com.jarvis.os.domain.ai.ModeRouter
import com.jarvis.os.domain.memory.ContextAssembler
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

    val offlineModelManager: OfflineModelManager by lazy { OfflineModelManager(appContext) }
    val onlineAIEngine: OnlineAIEngine by lazy { OnlineAIEngine(keyStore, geminiClient) }
    val offlineAIEngine: OfflineAIEngine by lazy { OfflineAIEngine(appContext, offlineModelManager) }
    val modeRouter: ModeRouter by lazy {
        ModeRouter(appContext, keyStore, onlineAIEngine, offlineAIEngine)
    }

    val memoryStore: MemoryStore by lazy { MemoryStore(appContext) }
    val knowledgeIndexer: KnowledgeIndexer by lazy {
        KnowledgeIndexer(appContext, memoryStore, httpClient)
    }
    val contextAssembler: ContextAssembler by lazy { ContextAssembler(memoryStore) }
}
