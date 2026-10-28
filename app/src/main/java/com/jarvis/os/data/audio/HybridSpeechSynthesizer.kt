package com.jarvis.os.data.audio

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.jarvis.os.data.remote.ElevenLabsClient
import com.jarvis.os.data.secure.SecureKeyStore
import com.jarvis.os.domain.model.VoiceMode
import com.jarvis.os.domain.speech.SpeechSynthesizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single entry-point for SARA voice.
 *
 * Routing (default = AUTO):
 *   online + ElevenLabs configured → try ElevenLabs (with timeout)
 *   any failure / offline / OFFLINE mode → Native Android TTS
 *
 * The rest of the app only calls [speak] / [stop] / [shutdown].
 */
class HybridSpeechSynthesizer(
    context: Context,
    private val keyStore: SecureKeyStore,
    private val elevenLabs: ElevenLabsClient,
    private val player: PcmStreamPlayer = PcmStreamPlayer(ElevenLabsClient.PCM_SAMPLE_RATE),
    private val nativeTts: NativeTts = NativeTts(context)
) : SpeechSynthesizer {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private var activeJob: Job? = null
    private val activeCount = AtomicInteger(0)

    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    override suspend fun speak(text: String) {
        val clean = sanitize(text)
        if (clean.isEmpty()) return

        val job = lock.withLock {
            // Stop any in-flight speech (online stream + native)
            activeJob?.cancelAndJoin()
            nativeTts.stop()
            scope.launch { speakNow(clean) }.also { activeJob = it }
        }
        try {
            job.join()
        } catch (e: CancellationException) {
            job.cancel()
            throw e
        }
    }

    override fun stop() {
        scope.launch {
            lock.withLock {
                activeJob?.cancelAndJoin()
                activeJob = null
                nativeTts.stop()
            }
            _isSpeaking.value = false
        }
    }

    override fun shutdown() {
        scope.cancel()
        nativeTts.shutdown()
    }

    private suspend fun speakNow(text: String) {
        activeCount.incrementAndGet()
        _isSpeaking.value = true
        try {
            val settings = keyStore.settings.first()
            val mode = settings.voiceMode

            val usedOnline = when (mode) {
                VoiceMode.OFFLINE -> false
                VoiceMode.ONLINE,
                VoiceMode.AUTO -> tryElevenLabs(text, settings.elevenLabsKey, settings.elevenLabsVoiceId, settings.elevenLabsModel)
            }

            if (!usedOnline) {
                val ok = nativeTts.speak(text)
                if (!ok) Log.w(TAG, "Native TTS also failed for: ${text.take(40)}")
            }
        } finally {
            if (activeCount.decrementAndGet() == 0) _isSpeaking.value = false
        }
    }

    /**
     * Returns true only if ElevenLabs produced playable audio.
     * Any error / timeout / missing config → false (caller falls back).
     */
    private suspend fun tryElevenLabs(
        text: String,
        apiKey: String?,
        voiceId: String?,
        modelId: String
    ): Boolean {
        if (apiKey.isNullOrBlank() || voiceId.isNullOrBlank()) return false
        if (!isOnline()) {
            Log.i(TAG, "Offline → skip ElevenLabs")
            return false
        }

        var gotAudio = false
        return try {
            val result = withTimeoutOrNull(ELEVEN_TIMEOUT_MS) {
                player.play(
                    elevenLabs.streamSpeech(apiKey, voiceId, text, modelId)
                        .onEach { gotAudio = true }
                )
                true
            }
            if (result == null) {
                Log.w(TAG, "ElevenLabs timed out after ${ELEVEN_TIMEOUT_MS}ms")
                // If we already received some audio, consider it a partial success;
                // otherwise fall back so SARA still speaks.
                gotAudio
            } else {
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "ElevenLabs failed: ${e.message}")
            // Only treat as success if we actually played something
            gotAudio
        }
    }

    private fun isOnline(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }

    private fun sanitize(text: String): String =
        text.replace(MARKDOWN_NOISE, " ").replace(WHITESPACE, " ").trim()

    private companion object {
        const val TAG = "HybridSpeech"
        /** Hard ceiling so UI never waits forever on a slow/hung stream. */
        const val ELEVEN_TIMEOUT_MS = 12_000L
        val MARKDOWN_NOISE = Regex("[*#`_~>|]")
        val WHITESPACE = Regex("\\s+")
    }
}
