package com.jarvis.os.data.secure

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jarvis.os.domain.model.ApiDefaults
import com.jarvis.os.domain.model.ApiSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

internal val Context.jarvisDataStore: DataStore<Preferences> by preferencesDataStore(name = "jarvis_store")

class SecureKeyStore(context: Context) {

    private val store = context.applicationContext.jarvisDataStore
    private val cipher = KeystoreCipher()

    val settings: Flow<ApiSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            val rawModel = prefs[GEMINI_MODEL] ?: ApiDefaults.GEMINI_MODEL
            ApiSettings(
                geminiKey = prefs[GEMINI_KEY]?.let(cipher::decrypt),
                geminiModel = migrateModel(rawModel),
                elevenLabsKey = prefs[ELEVEN_KEY]?.let(cipher::decrypt),
                elevenLabsVoiceId = prefs[ELEVEN_VOICE],
                elevenLabsModel = prefs[ELEVEN_MODEL] ?: ApiDefaults.ELEVENLABS_MODEL
            )
        }
        .distinctUntilChanged()

    suspend fun current(): ApiSettings {
        val s = settings.first()
        // Persist migration so next launches stay on the good model
        val raw = store.data.first()[GEMINI_MODEL]
        if (raw != null && migrateModel(raw) != raw) {
            store.edit { it[GEMINI_MODEL] = migrateModel(raw) }
        }
        return s
    }

    suspend fun saveGemini(apiKey: String, model: String) {
        store.edit {
            it[GEMINI_KEY] = cipher.encrypt(apiKey.trim())
            it[GEMINI_MODEL] = migrateModel(model.trim().ifEmpty { ApiDefaults.GEMINI_MODEL })
        }
    }

    suspend fun saveElevenLabs(apiKey: String, voiceId: String, model: String = ApiDefaults.ELEVENLABS_MODEL) {
        store.edit {
            it[ELEVEN_KEY] = cipher.encrypt(apiKey.trim())
            it[ELEVEN_VOICE] = voiceId.trim()
            it[ELEVEN_MODEL] = model.trim().ifEmpty { ApiDefaults.ELEVENLABS_MODEL }
        }
    }

    suspend fun clearGemini() {
        store.edit {
            it.remove(GEMINI_KEY)
            it.remove(GEMINI_MODEL)
        }
    }

    suspend fun clearElevenLabs() {
        store.edit {
            it.remove(ELEVEN_KEY)
            it.remove(ELEVEN_VOICE)
            it.remove(ELEVEN_MODEL)
        }
    }

    suspend fun clearAllKeys() {
        clearGemini()
        clearElevenLabs()
    }

    companion object {
        private val GEMINI_KEY = stringPreferencesKey("gemini_key_enc")
        private val GEMINI_MODEL = stringPreferencesKey("gemini_model")
        private val ELEVEN_KEY = stringPreferencesKey("eleven_key_enc")
        private val ELEVEN_VOICE = stringPreferencesKey("eleven_voice_id")
        private val ELEVEN_MODEL = stringPreferencesKey("eleven_model")

        /** Models Google has shut down or renamed — always remap to a working default. */
        private val DEAD_MODELS = setOf(
            "gemini-2.0-flash",
            "gemini-2.0-flash-exp",
            "gemini-1.5-flash",
            "gemini-1.5-flash-latest",
            "gemini-1.5-pro",
            "gemini-1.5-pro-latest",
            "gemini-pro",
            "gemini-pro-vision"
        )

        fun migrateModel(model: String): String {
            val clean = model.trim().removePrefix("models/")
            if (clean.isEmpty() || clean in DEAD_MODELS) return ApiDefaults.GEMINI_MODEL
            return clean
        }
    }
}
