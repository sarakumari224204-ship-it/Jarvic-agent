package com.jarvis.os.data.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Offline / device TTS provider.
 *
 * - Works with no internet.
 * - Prefers Hindi when Devanagari is present, otherwise English/device default.
 * - Tries to pick a natural (preferably female) installed voice for SARA.
 * - Never crashes the app if a language/voice is missing.
 */
class NativeTts(context: Context) {

    private val appContext = context.applicationContext
    private var engine: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null

    private suspend fun ensureReady(): TextToSpeech? = withContext(Dispatchers.Main) {
        val deferred = ready ?: CompletableDeferred<Boolean>().also { d ->
            ready = d
            engine = TextToSpeech(appContext) { status ->
                d.complete(status == TextToSpeech.SUCCESS)
            }
        }
        if (deferred.await()) engine else null
    }

    /**
     * Speak [text]. Returns true if TTS accepted and finished (or was interrupted cleanly).
     */
    suspend fun speak(text: String): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return false

        val tts = ensureReady() ?: run {
            Log.w(TAG, "TTS engine not ready")
            return false
        }

        selectLanguageAndVoice(tts, clean)

        val limit = (TextToSpeech.getMaxSpeechInputLength() - 1).coerceAtLeast(200)
        // Speak in chunks if the device has a low limit (rare on modern Android).
        val chunks = clean.chunked(limit)
        var allOk = true
        for (chunk in chunks) {
            if (!speakChunk(tts, chunk)) {
                allOk = false
                break
            }
        }
        return allOk
    }

    private suspend fun speakChunk(tts: TextToSpeech, text: String): Boolean {
        val utteranceId = UUID.randomUUID().toString()
        return suspendCancellableCoroutine { cont ->
            val finished = AtomicBoolean(false)
            fun finish(success: Boolean) {
                if (finished.compareAndSet(false, true) && cont.isActive) {
                    cont.resume(success)
                }
            }

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = finish(true)

                @Deprecated("Deprecated in Java")
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(utteranceId: String?) = finish(false)

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.w(TAG, "TTS error code=$errorCode")
                    finish(false)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(false)
            })

            cont.invokeOnCancellation {
                runCatching { tts.stop() }
            }

            val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                Log.w(TAG, "tts.speak returned $result")
                finish(false)
            }
        }
    }

    private fun selectLanguageAndVoice(tts: TextToSpeech, text: String) {
        val hasDevanagari = text.any { it in '\u0900'..'\u097F' }
        val preferredLocales = if (hasDevanagari) {
            listOf(
                Locale.forLanguageTag("hi-IN"),
                Locale("hi", "IN"),
                Locale.getDefault()
            )
        } else {
            listOf(
                Locale.forLanguageTag("en-IN"),
                Locale.US,
                Locale.UK,
                Locale.getDefault()
            )
        }

        var languageSet = false
        for (locale in preferredLocales) {
            val result = tts.setLanguage(locale)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                languageSet = true
                trySelectVoice(tts, locale)
                break
            }
        }
        if (!languageSet) {
            // Last resort — do not crash
            runCatching { tts.setLanguage(Locale.getDefault()) }
        }
    }

    /** Prefer a natural / female-sounding installed voice when the API exposes one. */
    private fun trySelectVoice(tts: TextToSpeech, locale: Locale) {
        try {
            val voices = tts.voices ?: return
            val matching = voices.filter { voice ->
                voice.locale.language.equals(locale.language, ignoreCase = true) &&
                    !voice.isNetworkConnectionRequired &&
                    voice.quality >= Voice.QUALITY_NORMAL
            }
            if (matching.isEmpty()) return

            // Prefer names that often indicate female / neural voices on Android
            val preferred = matching.firstOrNull { v ->
                val n = v.name.lowercase()
                n.contains("female") || n.contains("fema") ||
                    n.contains("neural") || n.contains("natural") ||
                    n.contains("en-in-x-ena") || n.contains("hi-in-x-hif")
            } ?: matching.maxByOrNull { it.quality }

            preferred?.let { tts.voice = it }
        } catch (e: Exception) {
            Log.w(TAG, "Voice selection skipped: ${e.message}")
        }
    }

    fun stop() {
        runCatching { engine?.stop() }
    }

    fun shutdown() {
        runCatching {
            engine?.stop()
            engine?.shutdown()
        }
        engine = null
        ready = null
    }

    private companion object {
        const val TAG = "NativeTts"
    }
}
