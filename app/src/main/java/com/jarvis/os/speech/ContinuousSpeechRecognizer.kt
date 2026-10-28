package com.jarvis.os.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Continuous listening loop built on Android SpeechRecognizer.
 * Automatically restarts after end-of-speech or errors (except permission/client errors).
 */
class ContinuousSpeechRecognizer(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var recognizer: SpeechRecognizer? = null
    private var restartJob: Job? = null
    private var running = false

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _partial = MutableStateFlow("")
    val partialText: StateFlow<String> = _partial.asStateFlow()

    private val _results = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val results: SharedFlow<String> = _results.asSharedFlow()

    fun start() {
        if (running) return
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            Log.e(TAG, "Speech recognition not available on this device")
            return
        }
        running = true
        createAndStart()
    }

    fun stop() {
        running = false
        restartJob?.cancel()
        restartJob = null
        destroyRecognizer()
        _isListening.value = false
        _partial.value = ""
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    private fun createAndStart() {
        destroyRecognizer()
        val sr = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer = sr
        sr.setRecognitionListener(listener)
        sr.startListening(buildIntent())
        _isListening.value = true
    }

    private fun destroyRecognizer() {
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }

    private fun scheduleRestart(delayMs: Long = 350L) {
        if (!running) return
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(delayMs)
            if (running) createAndStart()
        }
    }

    private fun buildIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // Prefer Hindi + English; system will fall back as needed
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _isListening.value = true
            _partial.value = ""
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            _isListening.value = false
        }

        override fun onError(error: Int) {
            _isListening.value = false
            _partial.value = ""
            Log.w(TAG, "Speech error: $error")
            when (error) {
                SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    // Do not auto-restart; user must fix permission / state
                    running = false
                }
                else -> scheduleRestart()
            }
        }

        override fun onResults(results: Bundle?) {
            _isListening.value = false
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val best = texts?.firstOrNull()?.trim().orEmpty()
            _partial.value = ""
            if (best.isNotEmpty()) {
                scope.launch { _results.emit(best) }
            }
            scheduleRestart()
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
