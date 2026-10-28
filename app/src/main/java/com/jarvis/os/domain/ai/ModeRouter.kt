package com.jarvis.os.domain.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.jarvis.os.data.offline.OfflineAIEngine
import com.jarvis.os.data.offline.OnlineAIEngine
import com.jarvis.os.data.secure.SecureKeyStore
import kotlinx.coroutines.flow.first

/**
 * Routes generate() calls to Online or Offline engine based on [AIMode] and network.
 */
class ModeRouter(
    private val context: Context,
    private val keyStore: SecureKeyStore,
    private val online: OnlineAIEngine,
    private val offline: OfflineAIEngine
) {

    suspend fun generate(
        systemPrompt: String,
        userText: String,
        maxTokens: Int = 256
    ): AIEngineResult {
        val mode = keyStore.aiMode.first()
        return when (mode) {
            AIMode.ONLINE_ONLY -> online.generate(systemPrompt, userText, maxTokens)
            AIMode.OFFLINE_ONLY -> offline.generate(systemPrompt, userText, maxTokens)
            AIMode.AUTO -> autoRoute(systemPrompt, userText, maxTokens)
        }
    }

    private suspend fun autoRoute(
        systemPrompt: String,
        userText: String,
        maxTokens: Int
    ): AIEngineResult {
        if (isOnline() && online.isAvailable()) {
            val result = online.generate(systemPrompt, userText, maxTokens)
            if (result is AIEngineResult.Success) return result
            if (result is AIEngineResult.Failure) {
                val networkish = result.kind == AIEngineResult.FailureKind.NO_NETWORK ||
                    result.kind == AIEngineResult.FailureKind.TIMEOUT
                if (networkish && offline.isAvailable()) {
                    Log.i(TAG, "Online failed (${result.kind}) → offline fallback")
                    return offline.generate(systemPrompt, userText, maxTokens)
                }
                return result
            }
        }
        if (offline.isAvailable()) {
            return offline.generate(systemPrompt, userText, maxTokens)
        }
        if (!online.isAvailable()) {
            return AIEngineResult.Failure(
                "No online API key and no offline model installed.",
                AIEngineResult.FailureKind.MODEL_NOT_INSTALLED
            )
        }
        return online.generate(systemPrompt, userText, maxTokens)
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private companion object {
        const val TAG = "ModeRouter"
    }
}
