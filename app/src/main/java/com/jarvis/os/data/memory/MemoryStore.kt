package com.jarvis.os.data.memory

import android.content.Context
import android.util.Log
import com.jarvis.os.data.remote.AppJson
import com.jarvis.os.domain.memory.IndexState
import com.jarvis.os.domain.memory.KnowledgeChunk
import com.jarvis.os.domain.memory.KnowledgeItem
import com.jarvis.os.domain.memory.KnowledgeType
import com.jarvis.os.domain.memory.MemorySettings
import com.jarvis.os.domain.memory.PersonalitySettings
import com.jarvis.os.domain.memory.SaraInstruction
import com.jarvis.os.domain.memory.SaraMemory
import com.jarvis.os.domain.memory.SensitiveContentFilter
import com.jarvis.os.domain.memory.TeachSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Local-only Teach Sara store (instructions, memory, knowledge, chunks).
 * Backed by a single JSON file under app private storage — survives updates.
 */
class MemoryStore(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "teach_sara").also { it.mkdirs() }
    private val dbFile = File(dir, "teach_snapshot.json")
    private val mutex = Mutex()

    private val _snapshot = MutableStateFlow(loadSync())
    val snapshot: StateFlow<TeachSnapshot> = _snapshot.asStateFlow()

    fun current(): TeachSnapshot = _snapshot.value

    // ── Instructions ───────────────────────────────────────────────────────

    suspend fun upsertInstruction(item: SaraInstruction): Result<SaraInstruction> = mutate { snap ->
        val list = snap.instructions.toMutableList()
        val idx = list.indexOfFirst { it.id == item.id }
        val saved = item.copy(updatedAt = System.currentTimeMillis())
        if (idx >= 0) list[idx] = saved else list.add(saved)
        snap.copy(instructions = list.sortedBy { it.order }) to saved
    }

    suspend fun deleteInstruction(id: String) = mutateUnit { snap ->
        snap.copy(instructions = snap.instructions.filterNot { it.id == id })
    }

    suspend fun reorderInstructions(idsInOrder: List<String>) = mutateUnit { snap ->
        val map = snap.instructions.associateBy { it.id }
        val reordered = idsInOrder.mapIndexedNotNull { i, id ->
            map[id]?.copy(order = i, updatedAt = System.currentTimeMillis())
        }
        val remaining = snap.instructions.filter { it.id !in idsInOrder }
        snap.copy(instructions = reordered + remaining)
    }

    // ── Memory ─────────────────────────────────────────────────────────────

    suspend fun upsertMemory(item: SaraMemory): Result<SaraMemory> {
        SensitiveContentFilter.blockReason(item.text)?.let { return Result.failure(IllegalArgumentException(it)) }
        return mutate { snap ->
            val list = snap.memories.toMutableList()
            val idx = list.indexOfFirst { it.id == item.id }
            val saved = item.copy(updatedAt = System.currentTimeMillis())
            if (idx >= 0) list[idx] = saved else list.add(saved)
            snap.copy(memories = list) to saved
        }
    }

    suspend fun deleteMemory(id: String) = mutateUnit { snap ->
        snap.copy(memories = snap.memories.filterNot { it.id == id })
    }

    suspend fun clearMemories() = mutateUnit { it.copy(memories = emptyList()) }

    fun findDuplicateMemory(text: String, existing: List<SaraMemory> = current().memories): SaraMemory? {
        val norm = normalize(text)
        return existing.firstOrNull { normalize(it.text) == norm || tokenOverlap(norm, normalize(it.text)) >= 0.8 }
    }

    fun findConflictingMemory(text: String, existing: List<SaraMemory> = current().memories): SaraMemory? {
        // Simple heuristic: same subject words but contains opposite preference markers
        val norm = normalize(text)
        val neg = listOf("not", "don't", "dont", "never", "nahi", "नहीं")
        return existing.firstOrNull { other ->
            val o = normalize(other.text)
            tokenOverlap(norm, o) >= 0.45 &&
                (neg.any { norm.contains(it) } != neg.any { o.contains(it) }) &&
                normalize(other.text) != norm
        }
    }

    // ── Knowledge ──────────────────────────────────────────────────────────

    suspend fun upsertKnowledge(item: KnowledgeItem): Result<KnowledgeItem> = mutate { snap ->
        val list = snap.knowledge.toMutableList()
        val idx = list.indexOfFirst { it.id == item.id }
        val saved = item.copy(updatedAt = System.currentTimeMillis())
        if (idx >= 0) list[idx] = saved else list.add(saved)
        snap.copy(knowledge = list) to saved
    }

    suspend fun deleteKnowledge(id: String) = mutateUnit { snap ->
        snap.copy(
            knowledge = snap.knowledge.filterNot { it.id == id },
            chunks = snap.chunks.filterNot { it.knowledgeId == id }
        )
    }

    suspend fun replaceChunks(knowledgeId: String, chunks: List<KnowledgeChunk>) = mutateUnit { snap ->
        snap.copy(
            chunks = snap.chunks.filterNot { it.knowledgeId == knowledgeId } + chunks,
            knowledge = snap.knowledge.map {
                if (it.id == knowledgeId) it.copy(
                    indexState = if (chunks.isEmpty()) IndexState.FAILED else IndexState.INDEXED,
                    indexError = if (chunks.isEmpty()) "No text extracted" else null,
                    updatedAt = System.currentTimeMillis()
                ) else it
            }
        )
    }

    suspend fun markKnowledgeFailed(id: String, error: String) = mutateUnit { snap ->
        snap.copy(
            knowledge = snap.knowledge.map {
                if (it.id == id) it.copy(
                    indexState = IndexState.FAILED,
                    indexError = error,
                    updatedAt = System.currentTimeMillis()
                ) else it
            },
            chunks = snap.chunks.filterNot { it.knowledgeId == id }
        )
    }

    // ── Settings / personality ─────────────────────────────────────────────

    suspend fun savePersonality(p: PersonalitySettings) = mutateUnit { it.copy(personality = p) }

    suspend fun saveSettings(s: MemorySettings) = mutateUnit { it.copy(settings = s) }

    // ── Search ─────────────────────────────────────────────────────────────

    data class SearchHit(val type: String, val id: String, val title: String, val snippet: String)

    fun search(query: String): List<SearchHit> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val snap = current()
        val hits = mutableListOf<SearchHit>()
        snap.instructions.filter { normalize(it.title).contains(q) || normalize(it.text).contains(q) }
            .forEach { hits += SearchHit("instruction", it.id, it.title, it.text.take(120)) }
        snap.memories.filter { normalize(it.text).contains(q) }
            .forEach { hits += SearchHit("memory", it.id, it.category.name, it.text.take(120)) }
        snap.knowledge.filter {
            normalize(it.title).contains(q) || normalize(it.source).contains(q) ||
                it.tags.any { t -> normalize(t).contains(q) }
        }.forEach { hits += SearchHit("knowledge", it.id, it.title, it.source.take(120)) }
        snap.chunks.filter { normalize(it.text).contains(q) }
            .take(20)
            .forEach { hits += SearchHit("chunk", it.id, it.sourceLabel, it.text.take(120)) }
        return hits
    }

    // ── Export / import ────────────────────────────────────────────────────

    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        AppJson.encodeToString(TeachSnapshot.serializer(), current())
    }

    suspend fun importJson(json: String, merge: Boolean): Result<Unit> = runCatching {
        val incoming = AppJson.decodeFromString(TeachSnapshot.serializer(), json)
        require(incoming.version <= 1) { "Unsupported backup version" }
        mutex.withLock {
            val merged = if (merge) {
                val cur = _snapshot.value
                TeachSnapshot(
                    instructions = (cur.instructions + incoming.instructions).distinctBy { it.id },
                    memories = (cur.memories + incoming.memories).distinctBy { it.id },
                    knowledge = (cur.knowledge + incoming.knowledge).distinctBy { it.id },
                    chunks = (cur.chunks + incoming.chunks).distinctBy { it.id },
                    personality = incoming.personality,
                    settings = incoming.settings,
                    version = 1
                )
            } else incoming.copy(version = 1)
            persist(merged)
            _snapshot.value = merged
        }
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private suspend fun <T> mutate(block: (TeachSnapshot) -> Pair<TeachSnapshot, T>): Result<T> =
        runCatching {
            mutex.withLock {
                val (next, value) = block(_snapshot.value)
                persist(next)
                _snapshot.value = next
                value
            }
        }

    private suspend fun mutateUnit(block: (TeachSnapshot) -> TeachSnapshot) {
        mutex.withLock {
            val next = block(_snapshot.value)
            persist(next)
            _snapshot.value = next
        }
    }

    private fun loadSync(): TeachSnapshot {
        return try {
            if (!dbFile.exists()) TeachSnapshot()
            else AppJson.decodeFromString(TeachSnapshot.serializer(), dbFile.readText())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load teach snapshot", e)
            TeachSnapshot()
        }
    }

    private fun persist(snap: TeachSnapshot) {
        val tmp = File(dir, "teach_snapshot.json.tmp")
        tmp.writeText(AppJson.encodeToString(TeachSnapshot.serializer(), snap))
        if (!tmp.renameTo(dbFile)) {
            dbFile.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("[\\p{Punct}]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun tokenOverlap(a: String, b: String): Double {
        val ta = a.split(' ').filter { it.length > 2 }.toSet()
        val tb = b.split(' ').filter { it.length > 2 }.toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        return ta.intersect(tb).size.toDouble() / ta.union(tb).size.toDouble()
    }

    companion object {
        private const val TAG = "MemoryStore"
    }
}
