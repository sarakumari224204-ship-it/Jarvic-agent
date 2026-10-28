package com.jarvis.os.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Continuous listening via Android SpeechRecognizer.
 *
 * Rules:
 * - Transient errors (NO_MATCH, SPEECH_TIMEOUT, CLIENT, NETWORK…) → restart
 * - Only ERROR_INSUFFICIENT_PERMISSIONS permanently stops listening
 * - No overlapping startListening calls
 * - No restart after stop()/shutdown()
 */
class ContinuousSpeechRecognizer(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private val running = AtomicBoolean(false)
    private val starting = AtomicBoolean(false)

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _partial = MutableStateFlow("")
    val partialText: StateFlow<String> = _partial.asStateFlow()

    private val _results = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val results: SharedFlow<String> = _results.asSharedFlow()

    private val restartRunnable = Runnable {
        if (running.get()) {
            Log.d(TAG, "restartRunnable → createAndStart")
            createAndStart()
        }
    }

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            Log.e(TAG, "Speech recognition not available on this device")
            return
        }
        if (!running.compareAndSet(false, true)) {
            Log.d(TAG, "start() ignored – already running")
            return
        }
        Log.i(TAG, "SPEECH_ENGINE_START")
        mainHandler.post { createAndStart() }
    }

    fun stop() {
        Log.i(TAG, "SPEECH_ENGINE_STOP")
        running.set(false)
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.post {
            destroyRecognizer()
            _isListening.value = false
            _partial.value = ""
            starting.set(false)
        }
    }

    fun shutdown() = stop()

    private fun createAndStart() {
        if (!running.get()) return
        if (!starting.compareAndSet(false, true)) {
            Log.d(TAG, "createAndStart skipped – already starting")
            return
        }
        try {
            destroyRecognizer()
            val sr = SpeechRecognizer.createSpeechRecognizer(appContext)
            recognizer = sr
            sr.setRecognitionListener(listener)
            try {
                sr.startListening(buildIntent())
                _isListening.value = true
                Log.d(TAG, "startListening OK")
            } catch (e: Exception) {
                Log.e(TAG, "startListening threw", e)
                _isListening.value = false
                scheduleRestart(600L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "createAndStart failed", e)
            scheduleRestart(800L)
        } finally {
            starting.set(false)
        }
    }

    private fun destroyRecognizer() {
        try {
            recognizer?.setRecognitionListener(null)
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "destroyRecognizer: ${e.message}")
        }
        recognizer = null
    }

    private fun scheduleRestart(delayMs: Long = 400L) {
        if (!running.get()) return
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.postDelayed(restartRunnable, delayMs)
        Log.d(TAG, "scheduled restart in ${delayMs}ms")
    }

    private fun buildIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            // Prefer online when available but allow offline engines too
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }
    }

    private fun errorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "SERVER_DISCONNECTED"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE"
        else -> "UNKNOWN_$code"
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _isListening.value = true
            _partial.value = ""
            Log.d(TAG, "onReadyForSpeech")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "onBeginningOfSpeech")
        }

        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            _isListening.value = false
            Log.d(TAG, "onEndOfSpeech")
        }

        override fun onError(error: Int) {
            _isListening.value = false
            _partial.value = ""
            val name = errorName(error)
            Log.w(TAG, "SPEECH_ERROR code=$error name=$name")

            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    // Hard stop – user must grant mic permission
                    Log.e(TAG, "Mic permission missing – stopping engine")
                    running.set(false)
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    // Back off a bit longer
                    scheduleRestart(900L)
                }
                SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER,
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
                SpeechRecognizer.ERROR_AUDIO,
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> {
                    // Transient – always recover
                    scheduleRestart(450L)
                }
                else -> scheduleRestart(600L)
            }
        }

        override fun onResults(results: Bundle?) {
            _isListening.value = false
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val best = texts?.firstOrNull()?.trim().orEmpty()
            _partial.value = ""
            if (best.isNotEmpty()) {
                Log.i(TAG, "SPEECH_RECEIVED: $best")
                val emitted = _results.tryEmit(best)
                if (!emitted) Log.w(TAG, "results buffer full – dropped: $best")
            } else {
                Log.d(TAG, "onResults empty")
            }
            scheduleRestart(350L)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            _partial.value = texts?.firstOrNull().orEmpty()
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    companion object {
        private const val TAG = "ContinuousSTT"
    }
}
