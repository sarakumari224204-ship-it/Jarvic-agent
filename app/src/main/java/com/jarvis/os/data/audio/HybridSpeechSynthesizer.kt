package com.jarvis.os.data.audio

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.jarvis.os.data.remote.ElevenLabsClient
import com.jarvis.os.data.secure.SecureKeyStore
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
import java.util.concurrent.atomic.AtomicInteger

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
            activeJob?.cancelAndJoin()
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
            }
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
            if (!speakWithElevenLabs(text)) nativeTts.speak(text)
        } finally {
            if (activeCount.decrementAndGet() == 0) _isSpeaking.value = false
        }
    }

    private suspend fun speakWithElevenLabs(text: String): Boolean {
        val settings = keyStore.settings.first()
        val key = settings.elevenLabsKey
        val voice = settings.elevenLabsVoiceId
        if (key.isNullOrBlank() || voice.isNullOrBlank() || !isOnline()) return false

        var gotAudio = false
        return try {
            player.play(
                elevenLabs.streamSpeech(key, voice, text, settings.elevenLabsModel)
                    .onEach { gotAudio = true }
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "ElevenLabs playback failed: ${e.message}")
            gotAudio
        }
    }

    private fun isOnline(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun sanitize(text: String): String =
        text.replace(MARKDOWN_NOISE, " ").replace(WHITESPACE, " ").trim()

    private companion object {
        const val TAG = "HybridSpeech"
        val MARKDOWN_NOISE = Regex("[*#`_~>|]")
        val WHITESPACE = Regex("\\s+")
    }
}
