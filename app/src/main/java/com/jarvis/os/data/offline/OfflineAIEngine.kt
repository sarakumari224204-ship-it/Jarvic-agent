package com.jarvis.os.data.offline

import android.util.Log
import com.jarvis.os.domain.ai.AIEngine
import com.jarvis.os.domain.ai.AIEngineResult
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * On-device GGUF inference via llama-android (llama.cpp).
 * Loads only the currently selected model; releases on [unload].
 */
class OfflineAIEngine(
    private val modelManager: OfflineModelManager
) : AIEngine {

    override val name: String = "Offline"

    private val lock = Mutex()
    private var loaded: Pair<String, LlamaModel>? = null

    override suspend fun isAvailable(): Boolean =
        modelManager.selectedModel() != null

    override suspend fun generate(
        systemPrompt: String,
        userText: String,
        maxTokens: Int
    ): AIEngineResult = lock.withLock {
        val info = modelManager.selectedModel()
            ?: return AIEngineResult.Failure(
                "Offline AI model isn't downloaded yet.",
                AIEngineResult.FailureKind.MODEL_NOT_INSTALLED
            )

        val path = modelManager.modelFile(info).absolutePath
        try {
            val model = ensureLoaded(info.id, path)
            val result = Llama.complete(
                model = model,
                prompt = userText,
                systemPrompt = systemPrompt,
                maxTokens = maxTokens.coerceIn(32, 512)
            )
            val text = result.text.trim()
            if (text.isEmpty()) {
                AIEngineResult.Failure("Empty offline response", AIEngineResult.FailureKind.UNKNOWN)
            } else {
                AIEngineResult.Success(text)
            }
        } catch (e: OutOfMemoryError) {
            unloadInternal()
            AIEngineResult.Failure(
                "Not enough memory for this model. Try Lite or free RAM.",
                AIEngineResult.FailureKind.INSUFFICIENT_MEMORY
            )
        } catch (e: Exception) {
            Log.e(TAG, "Offline generate failed", e)
            AIEngineResult.Failure(
                e.message ?: "Offline model failed",
                AIEngineResult.FailureKind.MODEL_LOAD_FAILED
            )
        }
    }

    private suspend fun ensureLoaded(id: String, path: String): LlamaModel {
        val current = loaded
        if (current != null && current.first == id) return current.second
        unloadInternal()
        val model = Llama.loadModel(
            modelPath = path,
            config = LlamaConfig(contextSize = 2048, threads = Runtime.getRuntime().availableProcessors().coerceAtMost(4))
        )
        loaded = id to model
        return model
    }

    suspend fun unload() = lock.withLock { unloadInternal() }

    private fun unloadInternal() {
        loaded?.let {
            runCatching { Llama.releaseModel(it.second) }
            loaded = null
        }
    }

    private companion object {
        const val TAG = "OfflineAIEngine"
    }
}
