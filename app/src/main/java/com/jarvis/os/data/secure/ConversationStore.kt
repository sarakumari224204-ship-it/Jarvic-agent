package com.jarvis.os.data.secure

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jarvis.os.data.remote.AppJson
import com.jarvis.os.domain.model.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import java.io.IOException

class ConversationStore(context: Context) {

    private val store = context.applicationContext.jarvisDataStore
    private val cipher = KeystoreCipher()
    private val serializer = ListSerializer(ChatMessage.serializer())

    val messages: Flow<List<ChatMessage>> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { decode(it[KEY]) }

    suspend fun recent(count: Int = 8): List<ChatMessage> = messages.first().takeLast(count)

    suspend fun append(message: ChatMessage) {
        store.edit { prefs ->
            val updated = (decode(prefs[KEY]) + message).takeLast(MAX_MESSAGES)
            prefs[KEY] = cipher.encrypt(AppJson.encodeToString(serializer, updated))
        }
    }

    suspend fun clear() {
        store.edit { it.remove(KEY) }
    }

    private fun decode(encrypted: String?): List<ChatMessage> {
        if (encrypted == null) return emptyList()
        val json = cipher.decrypt(encrypted) ?: return emptyList()
        return runCatching { AppJson.decodeFromString(serializer, json) }.getOrDefault(emptyList())
    }

    private companion object {
        val KEY = stringPreferencesKey("conversation_enc")
        const val MAX_MESSAGES = 100
    }
}
