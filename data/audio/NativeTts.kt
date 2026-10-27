package com.jarvis.os.data.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Offline fallback voice using the device's built-in TextToSpeech engine. */
class NativeTts(context: Context) {

    private val appContext = context.applicationContext
    private var engine: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null

    private suspend fun ensureReady(): TextToSpeech? = withContext(Dispatchers.Main) {
        val deferred = ready ?: CompletableDeferred<Boolean>().also { d ->
            ready = d
            engine = TextToSpeech(appContext) { status -> d.complete(status == TextToSpeech.SUCCESS) }
        }
        if (deferred.await()) engine else null
    }

    /** @return true if the utterance finished normally. Cancelling the caller stops speech. */
    suspend fun speak(text: String): Boolean {
        val tts = ensureReady() ?: return false
        selectLanguage(tts, text)
        val utteranceId = UUID.randomUUID().toString()
        val limit = TextToSpeech.getMaxSpeechInputLength() - 1

        return suspendCancellableCoroutine { cont ->
            val finished = AtomicBoolean(false)
            fun finish(success: Boolean) {
                if (finished.compareAndSet(false, true) && cont.isActive) cont.resume(success)
            }

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) = finish(true)

                @Deprecated("Deprecated in Java")
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(utteranceId: String?) = finish(false)

                override fun onError(utteranceId: String?, errorCode: Int) = finish(false)

                override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(false)
            })
            cont.invokeOnCancellation { tts.stop() }

            val result = tts.speak(text.take(limit), TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result != TextToSpeech.SUCCESS) finish(false)
        }
    }

    private fun selectLanguage(tts: TextToSpeech, text: String) {
        val hasDevanagari = text.any { it in '\u0900'..'\u097F' }
        val locale = if (hasDevanagari) Locale.forLanguageTag("hi-IN") else Locale.getDefault()
        val result = tts.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.getDefault())
        }
    }

    fun stop() {
        engine?.stop()
    }

    fun shutdown() {
        engine?.shutdown()
        engine = null
        ready = null
    }
}
