package com.jarvis.os.domain.model

object ApiDefaults {
    /** Fastest practical model for voice agent loop (low latency). */
    const val GEMINI_MODEL = "gemini-3.5-flash-lite"

    val GEMINI_MODELS = listOf(
        "gemini-3.5-flash-lite",
        "gemini-2.5-flash-lite",
        "gemini-2.5-flash",
        "gemini-3.6-flash",
        "gemini-3.8-flash",
        "gemini-4-argon"
    )

    const val ELEVENLABS_MODEL = "eleven_flash_v2_5"

    /** Default voice routing: try online first, fall back offline. */
    val VOICE_MODE: VoiceMode = VoiceMode.AUTO
}

enum class VoiceMode {
    /** Prefer ElevenLabs when online + configured; always fall back to native TTS. */
    AUTO,
    /** Force ElevenLabs only (still falls back if it fails). */
    ONLINE,
    /** Always use device native TTS (no network). */
    OFFLINE
}

data class ApiSettings(
    val geminiKey: String? = null,
    val geminiModel: String = ApiDefaults.GEMINI_MODEL,
    val elevenLabsKey: String? = null,
    val elevenLabsVoiceId: String? = null,
    val elevenLabsModel: String = ApiDefaults.ELEVENLABS_MODEL,
    val voiceMode: VoiceMode = ApiDefaults.VOICE_MODE
) {
    val hasGemini: Boolean get() = !geminiKey.isNullOrBlank()
    val hasElevenLabs: Boolean get() = !elevenLabsKey.isNullOrBlank() && !elevenLabsVoiceId.isNullOrBlank()

    override fun toString(): String =
        "ApiSettings(geminiModel=$geminiModel, hasGemini=$hasGemini, hasElevenLabs=$hasElevenLabs, voiceMode=$voiceMode)"
}
