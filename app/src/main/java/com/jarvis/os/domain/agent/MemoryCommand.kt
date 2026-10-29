package com.jarvis.os.domain.agent

import com.jarvis.os.data.memory.MemoryStore
import com.jarvis.os.domain.memory.MemoryCategory
import com.jarvis.os.domain.memory.SaraMemory
import com.jarvis.os.domain.memory.SensitiveContentFilter

/**
 * Offline memory teach / recall / forget for common Hindi-Hinglish phrases.
 * Pure parse is unit-testable; execute needs [MemoryStore].
 */
object MemoryCommand {

    enum class Op { SAVE, FORGET, LIST, SEARCH }

    data class Parsed(
        val op: Op,
        val payload: String
    )

    private val SAVE_PREFIXES = listOf(
        "yaad rakh", "yaad rakho", "yaad rakhna", "yaad kar lo", "yaad kar",
        "remember that", "remember", "note that", "note karo",
        "sikhao", "sikha do", "teach sara",
        "याद रख", "याद रखो"
    )

    private val FORGET_PREFIXES = listOf(
        "bhool jao", "bhool ja", "forget", "delete memory", "yaad mitao",
        "भूल जा", "भूल जाओ"
    )

    private val LIST_PHRASES = listOf(
        "kya yaad hai", "yaad dikhao", "list memories", "meri memory",
        "what do you remember", "show memories", "yaadein batao"
    )

    fun parse(objective: String): Parsed? {
        val raw = objective.trim()
        if (raw.isEmpty()) return null
        val lower = raw.lowercase().trim()

        for (p in LIST_PHRASES) {
            if (lower == p || lower.startsWith("$p ") || lower.endsWith(" $p")) {
                return Parsed(Op.LIST, "")
            }
        }

        for (p in FORGET_PREFIXES) {
            if (lower.startsWith(p)) {
                val payload = raw.substring(p.length).trim().trimStart(':', '-', ',', '.')
                if (payload.equals("sab", ignoreCase = true) || payload.equals("all", ignoreCase = true)) {
                    return Parsed(Op.FORGET, "*")
                }
                if (payload.isNotBlank()) return Parsed(Op.FORGET, payload)
                return Parsed(Op.LIST, "")
            }
        }

        for (p in SAVE_PREFIXES) {
            if (lower.startsWith(p) || lower.contains(" $p ")) {
                val idx = lower.indexOf(p)
                val after = raw.substring(idx + p.length).trim().trimStart(':', '-', ',', '.')
                if (after.isBlank() && lower.endsWith(p)) {
                    val before = raw.substring(0, idx).trim()
                    if (before.isNotBlank()) return Parsed(Op.SAVE, before)
                }
                if (after.isNotBlank()) return Parsed(Op.SAVE, after)
            }
        }

        for (p in listOf("yaad rakh", "yaad rakho", "remember")) {
            if (lower.endsWith(p) && lower.length > p.length + 2) {
                val payload = raw.dropLast(p.length).trim().trimEnd(',', '.', ':')
                if (payload.isNotBlank()) return Parsed(Op.SAVE, payload)
            }
        }

        return null
    }

    suspend fun execute(store: MemoryStore, parsed: Parsed): String {
        return when (parsed.op) {
            Op.SAVE -> {
                val text = parsed.payload.trim()
                if (text.length < 2) return "Kya yaad rakhun? Thoda clear bolo."
                SensitiveContentFilter.blockReason(text)?.let { return it }
                val item = SaraMemory(
                    text = text,
                    category = guessCategory(text),
                    source = "voice"
                )
                val result = store.upsertMemory(item)
                if (result.isSuccess) "Theek hai, yaad rakh liya."
                else "Yaad save nahi ho payi."
            }
            Op.FORGET -> {
                if (parsed.payload == "*") {
                    store.clearMemories()
                    return "Saari memories clear kar di."
                }
                val q = parsed.payload.lowercase()
                val hits = store.current().memories.filter {
                    it.text.lowercase().contains(q) || q.contains(it.text.lowercase().take(20))
                }
                if (hits.isEmpty()) return "Isse milti hui koi memory nahi mili."
                hits.forEach { store.deleteMemory(it.id) }
                "Haan, ${hits.size} memory hata di."
            }
            Op.LIST -> {
                val mems = store.current().memories.filter { it.enabled }.take(8)
                if (mems.isEmpty()) return "Abhi koi long-term memory save nahi hai."
                buildString {
                    append("Mere paas yeh yaad hai: ")
                    append(mems.joinToString("; ") { it.text.take(80) })
                }
            }
            Op.SEARCH -> {
                val hits = store.search(parsed.payload).take(5)
                if (hits.isEmpty()) return "Is query se kuch nahi mila."
                hits.joinToString("; ") { "${it.type}: ${it.snippet.take(60)}" }
            }
        }
    }

    private fun guessCategory(text: String): MemoryCategory {
        val t = text.lowercase()
        return when {
            t.contains("naam") || t.contains("name") || t.contains("birthday") -> MemoryCategory.PERSONAL
            t.contains("project") || t.contains("office") || t.contains("work") -> MemoryCategory.WORK
            t.contains("pasand") || t.contains("prefer") || t.contains("like") -> MemoryCategory.PREFERENCE
            else -> MemoryCategory.OTHER
        }
    }
}
