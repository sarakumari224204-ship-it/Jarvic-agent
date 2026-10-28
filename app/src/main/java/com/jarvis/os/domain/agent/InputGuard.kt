package com.jarvis.os.domain.agent

import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Blocks TTS echoes and accidental duplicate speech callbacks from starting new tasks.
 *
 * Legitimate intentional repeats are allowed after [duplicateWindowMs].
 */
class InputGuard(
    private val duplicateWindowMs: Long = 2_500L,
    private val echoWindowMs: Long = 8_000L,
    private val maxEchoEntries: Int = 12
) {
    private data class Echo(val normalized: String, val atMs: Long)

    @Volatile
    private var lastAcceptedNorm: String = ""

    @Volatile
    private var lastAcceptedAtMs: Long = 0L

    private val recentEchoes = ConcurrentLinkedQueue<Echo>()

    /** Call whenever the assistant speaks so STT cannot treat it as a user command. */
    fun registerSpoken(text: String) {
        val norm = normalize(text)
        if (norm.isEmpty()) return
        recentEchoes.add(Echo(norm, System.currentTimeMillis()))
        while (recentEchoes.size > maxEchoEntries) recentEchoes.poll()
        Log.d(TAG, "ECHO_REGISTER len=${norm.length}")
    }

    /**
     * @return null if input should be accepted; otherwise a short reason for rejection.
     */
    fun rejectReason(raw: String, nowMs: Long = System.currentTimeMillis()): String? {
        val norm = normalize(raw)
        if (norm.isEmpty()) return "empty"

        // Drop very short noise
        if (norm.length < 2) return "too_short"

        // TTS / assistant echo
        purgeOldEchoes(nowMs)
        for (echo in recentEchoes) {
            if (isSimilar(norm, echo.normalized)) {
                Log.i(TAG, "DUPLICATE_BLOCKED reason=tts_echo input=$norm")
                return "tts_echo"
            }
        }

        // Same command fired twice in a short window (double callback)
        if (norm == lastAcceptedNorm && nowMs - lastAcceptedAtMs < duplicateWindowMs) {
            Log.i(TAG, "DUPLICATE_BLOCKED reason=repeat_window input=$norm")
            return "repeat_window"
        }

        return null
    }

    fun markAccepted(raw: String, nowMs: Long = System.currentTimeMillis()) {
        lastAcceptedNorm = normalize(raw)
        lastAcceptedAtMs = nowMs
        Log.i(TAG, "INPUT_ACCEPTED norm=$lastAcceptedNorm")
    }

    fun normalize(text: String): String {
        return text.lowercase(Locale.ROOT)
            .replace(Regex("[\\p{Punct}…]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun isSimilar(a: String, b: String): Boolean {
        if (a == b) return true
        // Substring match for partial STT of longer TTS lines
        if (a.length >= 6 && b.contains(a)) return true
        if (b.length >= 6 && a.contains(b)) return true
        // High overlap on tokens
        val ta = a.split(' ').filter { it.length > 2 }.toSet()
        val tb = b.split(' ').filter { it.length > 2 }.toSet()
        if (ta.isEmpty() || tb.isEmpty()) return false
        val inter = ta.intersect(tb).size.toDouble()
        val union = ta.union(tb).size.toDouble()
        return inter / union >= 0.75
    }

    private fun purgeOldEchoes(nowMs: Long) {
        while (true) {
            val head = recentEchoes.peek() ?: break
            if (nowMs - head.atMs <= echoWindowMs) break
            recentEchoes.poll()
        }
    }

    companion object {
        private const val TAG = "InputGuard"
    }
}
