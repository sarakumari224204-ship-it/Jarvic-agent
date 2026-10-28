package com.jarvis.os.domain.speech

import kotlinx.coroutines.flow.StateFlow

interface SpeechSynthesizer {
    val isSpeaking: StateFlow<Boolean>

    suspend fun speak(text: String)

    fun stop()
    fun shutdown()
}
