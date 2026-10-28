package com.jarvis.os.domain.model

object ApiDefaults {
    /** Current recommended Flash model (Oct 2026). gemini-2.0-flash was shut down. */
    const val GEMINI_MODEL = "gemini-3.8-flash"

    /** Other known working models users can type in Settings */
    val GEMINI_MODELS = listOf(
        "gemini-3.8-flash",
        "gemini-3.6-flash",
        "gemini-3.5-flash-lite",
        "gemini-2.5-flash",
        "gemini-2.5-flash-lite",
        "gemini-4-argon",
        "gemini-3.1-pro-preview"
    )

    const val ELEVENLABS_MODEL = "eleven_flash_v2_5"
}

data class ApiSettings(
    val geminiKey: String? = null,
    val geminiModel: String = ApiDefaults.GEMINI_MODEL,
    val elevenLabsKey: String? = null,
    val elevenLabsVoiceId: String? = null,
    val elevenLabsModel: String = ApiDefaults.ELEVENLABS_MODEL
) {
    val hasGemini: Boolean get() = !geminiKey.isNullOrBlank()
    val hasElevenLabs: Boolean get() = !elevenLabsKey.isNullOrBlank() && !elevenLabsVoiceId.isNullOrBlank()

    override fun toString(): String =
        "ApiSettings(geminiModel=$geminiModel, hasGemini=$hasGemini, hasElevenLabs=$hasElevenLabs)"
}
