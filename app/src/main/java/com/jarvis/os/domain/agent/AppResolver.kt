package com.jarvis.os.domain.agent

/**
 * Maps natural-language app names (Hindi / English / Hinglish) to search tokens
 * and ranks PackageManager launchable labels/packages. Pure JVM — unit-testable.
 */
object AppResolver {

    data class Launchable(
        val label: String,
        val packageName: String,
        val activityName: String = ""
    )

    private val ALIASES: Map<String, List<String>> = mapOf(
        "youtube" to listOf("youtube"),
        "yt" to listOf("youtube"),
        "यूट्यूब" to listOf("youtube"),
        "whatsapp" to listOf("whatsapp"),
        "whats app" to listOf("whatsapp"),
        "वाट्सएप" to listOf("whatsapp"),
        "व्हाट्सऐप" to listOf("whatsapp"),
        "व्हाट्सएप" to listOf("whatsapp"),
        "instagram" to listOf("instagram"),
        "insta" to listOf("instagram"),
        "इंस्टाग्राम" to listOf("instagram"),
        "इंस्टा" to listOf("instagram"),
        "chrome" to listOf("chrome"),
        "google chrome" to listOf("chrome"),
        "क्रोम" to listOf("chrome"),
        "gmail" to listOf("gmail"),
        "जीमेल" to listOf("gmail"),
        "telegram" to listOf("telegram"),
        "टेलीग्राम" to listOf("telegram"),
        "facebook" to listOf("facebook"),
        "fb" to listOf("facebook"),
        "फेसबुक" to listOf("facebook"),
        "spotify" to listOf("spotify"),
        "settings" to listOf("settings"),
        "setting" to listOf("settings"),
        "सेटिंग्स" to listOf("settings"),
        "maps" to listOf("maps", "map"),
        "phone" to listOf("phone", "dialer"),
        "फोन" to listOf("phone", "dialer"),
        "messages" to listOf("messages"),
        "camera" to listOf("camera"),
        "gallery" to listOf("gallery", "photos"),
        "photos" to listOf("photos", "gallery"),
        "netflix" to listOf("netflix"),
        "calculator" to listOf("calculator"),
        "contacts" to listOf("contacts")
    )

    private val OPEN_SUFFIXES = listOf(
        " kholo", " khol do", " khol dena", " open karo", " start karo",
        " chalao", " chala do", " खोलो", " खोल दो", " चलाओ"
    )

    private val OPEN_PREFIXES = listOf("open ", "launch ", "start ")

    fun normalize(s: String): String =
        s.lowercase()
            .replace(Regex("[\\p{Punct}…]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun compact(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    fun searchTokens(userQuery: String): List<String> {
        val n = normalize(userQuery)
        if (n.isEmpty()) return emptyList()
        val c = compact(userQuery)
        val fromAlias = ALIASES[n] ?: ALIASES[c] ?: emptyList()
        val noSpace = n.replace(" ", "")
        val fromNoSpace = ALIASES[noSpace] ?: emptyList()
        return (listOf(n, c) + fromAlias + fromNoSpace)
            .map { compact(it) }
            .filter { it.length >= 2 }
            .distinct()
    }

    fun resolve(userQuery: String, apps: List<Launchable>): Launchable? {
        val qRaw = userQuery.trim()
        if (qRaw.isEmpty() || apps.isEmpty()) return null
        apps.firstOrNull { it.packageName.equals(qRaw, ignoreCase = true) }?.let { return it }
        val tokens = searchTokens(userQuery)
        if (tokens.isEmpty()) return null
        var best: Launchable? = null
        var bestScore = 0
        for (app in apps) {
            val score = scoreApp(app, tokens)
            if (score > bestScore) {
                bestScore = score
                best = app
            }
        }
        return if (bestScore >= 50) best else null
    }

    internal fun scoreApp(app: Launchable, tokens: List<String>): Int {
        val labelC = compact(app.label)
        val pkgC = compact(app.packageName)
        var score = 0
        for (t in tokens) {
            if (t.isEmpty()) continue
            when {
                labelC == t -> score = maxOf(score, 100)
                labelC.startsWith(t) && t.length >= 3 -> score = maxOf(score, 90)
                pkgC.contains(t) && t.length >= 4 -> score = maxOf(score, 80)
                labelC.contains(t) && t.length >= 3 -> score = maxOf(score, 70)
            }
        }
        if (score >= 70 && labelC.length > 24) score -= 5
        return score
    }

    fun extractOpenTarget(objective: String): String? {
        val raw = objective.trim()
        if (raw.isEmpty()) return null
        val lower = raw.lowercase()

        for (suffix in OPEN_SUFFIXES) {
            val s = suffix.lowercase()
            if (lower.endsWith(s) || raw.endsWith(suffix)) {
                val name = raw.dropLast(suffix.length).trim()
                    .removePrefix("the ").removePrefix("app ")
                    .removeSuffix(" app").trim()
                if (name.length in 2..40 && !name.lowercase().contains("wifi")) return name
            }
        }
        for (prefix in OPEN_PREFIXES) {
            if (lower.startsWith(prefix)) {
                val name = raw.substring(prefix.length).trim()
                    .removeSuffix(" app").removeSuffix(" application").trim()
                if (name.length in 2..40 && !name.lowercase().contains("wifi")) return name
            }
        }
        val n = normalize(raw)
        val c = compact(raw)
        if (ALIASES.containsKey(n) || ALIASES.containsKey(c)) return raw.trim()
        return null
    }
}
