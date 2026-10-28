package com.jarvis.os.data.offline

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.jarvis.os.domain.ai.AIEngine
import com.jarvis.os.domain.ai.AIEngineResult
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * On-device GGUF inference via llama-android.
 * Tuned for phones with ~6 GB RAM:
 * - context 1024 (not 2048+)
 * - max 2–3 threads
 * - only one model loaded
 * - unload after use when memory is tight
 */
class OfflineAIEngine(
    private val context: Context,
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

        // Quality (~850MB) on low free RAM is risky — warn via failure if critically low
        val availMb = availableMemoryMb()
        if (availMb in 1..899 && info.id == "quality") {
            Log.w(TAG, "Low free RAM (~${availMb}MB) for Quality model")
        }

        val path = modelManager.modelFile(info).absolutePath
        try {
            val model = ensureLoaded(info.id, path)
            val result = Llama.complete(
                model = model,
                prompt = userText,
                systemPrompt = systemPrompt,
                maxTokens = maxTokens.coerceIn(32, 160) // keep generations short on mobile
            )
            val text = result.text.trim()
            if (text.isEmpty()) {
                AIEngineResult.Failure("Empty offline response", AIEngineResult.FailureKind.UNKNOWN)
            } else {
                // On 6GB devices, prefer releasing after each turn to free RAM for system/UI
                if (availMb in 1..2048) {
                    unloadInternal()
                }
                AIEngineResult.Success(text)
            }
        } catch (e: OutOfMemoryError) {
            unloadInternal()
            System.gc()
            AIEngineResult.Failure(
                "RAM kam hai. Lite model use karo ya baaki apps band karo.",
                AIEngineResult.FailureKind.INSUFFICIENT_MEMORY
            )
        } catch (e: Exception) {
            Log.e(TAG, "Offline generate failed", e)
            unloadInternal()
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

        val cores = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        // 6GB phones: keep threads low so UI stays responsive
        val threads = cores.coerceAtMost(3)

        val model = Llama.loadModel(
            modelPath = path,
            config = LlamaConfig(
                contextSize = 1024,
                threads = threads
            )
        )
        loaded = id to model
        Log.i(TAG, "Loaded model=$id threads=$threads ctx=1024")
        return model
    }

    suspend fun unload() = lock.withLock { unloadInternal() }

    private fun unloadInternal() {
        loaded?.let {
            runCatching { Llama.releaseModel(it.second) }
            loaded = null
        }
    }

    private fun availableMemoryMb(): Long {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.availMem / (1024 * 1024)
        } catch (_: Exception) {
            -1L
        }
    }

    private companion object {
        const val TAG = "OfflineAIEngine"
    }
}
