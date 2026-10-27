package com.jarvis.os.domain.speech

import kotlinx.coroutines.flow.StateFlow

interface SpeechSynthesizer {
    /** True while audio is playing. Phase 4 pauses the microphone while this is true (echo prevention). */
    val isSpeaking: StateFlow<Boolean>

    /** Speaks [text] and returns when finished. A new call interrupts the previous one. Cancelling the caller stops audio. */
    suspend fun speak(text: String)

    fun stop()
    fun shutdown()
}
