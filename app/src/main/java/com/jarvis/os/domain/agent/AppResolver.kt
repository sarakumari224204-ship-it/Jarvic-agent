package com.jarvis.os.domain.agent

/**
 * Maps natural-language app names (Hindi / English / Hinglish) to search tokens
 * and ranks PackageManager launchable labels/packages. Pure JVM — unit-testable.
 *
 * extractOpenTarget is intentionally conservative: only explicit open/start/chalao
 * phrasing becomes a launch target; "YouTube par search" does not.
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
        "स्पॉटिफाई" to listOf("spotify"),
        "settings" to listOf("settings"),
        "setting" to listOf("settings"),
        "सेटिंग्स" to listOf("settings"),
        "सेटिंग" to listOf("settings"),
        "maps" to listOf("maps", "map"),
        "google maps" to listOf("maps"),
        "मैप्स" to listOf("maps"),
        "phone" to listOf("phone", "dialer"),
        "dialer" to listOf("phone", "dialer"),
        "फोन" to listOf("phone", "dialer"),
        "messages" to listOf("messages"),
        "message" to listOf("messages"),
        "sms" to listOf("messages"),
        "मेसेज" to listOf("messages"),
        "camera" to listOf("camera"),
        "कैमरा" to listOf("camera"),
        "gallery" to listOf("gallery", "photos"),
        "photos" to listOf("photos", "gallery"),
        "गैलरी" to listOf("gallery", "photos"),
        "netflix" to listOf("netflix"),
        "नेटफ्लिक्स" to listOf("netflix"),
        "calculator" to listOf("calculator"),
        "कैलकुलेटर" to listOf("calculator"),
        "contacts" to listOf("contacts"),
        "कॉन्टैक्ट्स" to listOf("contacts")
    )

    private val OPEN_SUFFIXES = listOf(
        " open karo", " start karo", " khol dena", " khol do", " chala do",
        " kholo", " chalao", " open", " launch", " start",
        " खोल दो", " खोलो", " चला दो", " चलाओ"
    )

    private val OPEN_PREFIXES = listOf("open ", "launch ", "start ")

    private val LEADING_FILLERS = listOf(
        "please", "plz", "pls", "zara", "jara", "jari", "thoda", "thodi",
        "जरा", "ज़रा", "थोड़ा", "प्लीज", "प्लीज़"
    )

    private val TRAILING_FILLERS = listOf("please", "plz", "pls", "प्लीज", "प्लीज़")

    private val NON_LAUNCH_MARKERS = listOf(
        "search", "message", "bhejo", "bhej", "send", "call karo", "call kar",
        "video", "batao", "bata", "baare", "baare mein", "ke bare",
        "par search", "mein search", "me search", "par message", "par call",
        "scroll", "type", "likho", "write",
        "सर्च", "मैसेज", "भेजो", "बताओ", "वीडियो"
    )

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

        var work = normalize(raw)
        if (work.isEmpty()) return null

        if (isNonLaunchIntent(work)) return null

        work = stripLeadingFillers(work)
        work = stripTrailingFillers(work)
        if (work.isEmpty()) return null

        for (suffix in OPEN_SUFFIXES) {
            val s = suffix.trim().lowercase()
            if (work == s) return null
            if (work.endsWith(" $s")) {
                var name = work.removeSuffix(" $s").trim()
                name = cleanAppToken(name)
                if (isValidAppName(name)) return preserveDisplayName(raw, name)
            }
            if (work.endsWith(s) && work.length > s.length + 1) {
                val ch = work[work.length - s.length - 1]
                if (ch.isWhitespace()) {
                    var name = work.dropLast(s.length).trim()
                    name = cleanAppToken(name)
                    if (isValidAppName(name)) return preserveDisplayName(raw, name)
                }
            }
        }

        for (prefix in OPEN_PREFIXES) {
            if (work.startsWith(prefix)) {
                var name = work.removePrefix(prefix).trim()
                name = stripTrailingFillers(name)
                for (suffix in OPEN_SUFFIXES) {
                    val s = suffix.trim().lowercase()
                    if (name.endsWith(" $s")) {
                        name = name.removeSuffix(" $s").trim()
                    }
                }
                name = cleanAppToken(name)
                if (isValidAppName(name)) return preserveDisplayName(raw, name)
            }
        }

        val n = normalize(work)
        val c = compact(work)
        if (ALIASES.containsKey(n) || ALIASES.containsKey(c)) return work.trim()

        return null
    }

    private fun cleanAppToken(name: String): String {
        var n = stripObjectMarker(name)
        n = stripTrailingFillers(n)
        n = stripLeadingFillers(n)
        n = stripObjectMarker(n)
        return n.trim()
    }

    private fun isNonLaunchIntent(normalized: String): Boolean {
        val t = normalized.lowercase()
        return NON_LAUNCH_MARKERS.any { t.contains(it) }
    }

    private fun stripLeadingFillers(s: String): String {
        var work = s.trim()
        var guard = 0
        while (guard++ < 8) {
            val lower = work.lowercase()
            var stripped = false
            for (f in LEADING_FILLERS) {
                val fl = f.lowercase()
                if (lower.startsWith("$fl ")) {
                    work = work.substring(fl.length).trim()
                    stripped = true
                    break
                }
            }
            if (!stripped) break
        }
        return work.trim()
    }

    private fun stripTrailingFillers(s: String): String {
        var work = s.trim()
        var guard = 0
        while (guard++ < 8) {
            val lower = work.lowercase()
            var stripped = false
            for (f in TRAILING_FILLERS) {
                val fl = f.lowercase()
                if (lower == fl) {
                    work = ""
                    stripped = true
                    break
                }
                if (lower.endsWith(" $fl")) {
                    work = work.dropLast(fl.length).trim()
                    stripped = true
                    break
                }
            }
            if (!stripped) break
        }
        return work.trim()
    }

    private fun stripObjectMarker(s: String): String {
        var work = s.trim()
        val lower = work.lowercase()
        if (lower.endsWith(" ko")) {
            return work.dropLast(3).trim()
        }
        if (work.endsWith(" को")) {
            return work.removeSuffix(" को").trim()
        }
        return work
    }

    private fun isValidAppName(name: String): Boolean {
        if (name.length !in 2..40) return false
        val lower = name.lowercase()
        if (lower.contains("wifi") || lower.contains("wi-fi")) return false
        if (LEADING_FILLERS.any { lower == it.lowercase() }) return false
        return true
    }

    private fun preserveDisplayName(original: String, cleanedNormalized: String): String {
        val origLower = original.lowercase()
        val idx = origLower.indexOf(cleanedNormalized.lowercase())
        if (idx >= 0) {
            return original.substring(idx, idx + cleanedNormalized.length).trim()
        }
        return cleanedNormalized
    }
}
