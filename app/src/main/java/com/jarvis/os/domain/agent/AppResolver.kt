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

    /**
     * Common spoken names → canonical English tokens that usually appear in
     * launcher labels or package names. Not package guesses for blind launch.
     */
    private val ALIASES: Map<String, List<String>> = mapOf(
        // YouTube
        "youtube" to listOf("youtube"),
        "yt" to listOf("youtube"),
        "यूट्यूब" to listOf("youtube"),
        "यू ट्यूब" to listOf("youtube"),
        // WhatsApp
        "whatsapp" to listOf("whatsapp"),
        "whats app" to listOf("whatsapp"),
        "वाट्सएप" to listOf("whatsapp"),
        "व्हाट्सऐप" to listOf("whatsapp"),
        "व्हाट्सएप" to listOf("whatsapp"),
        // Instagram
        "instagram" to listOf("instagram"),
        "insta" to listOf("instagram"),
        "इंस्टाग्राम" to listOf("instagram"),
        "इंस्टा" to listOf("instagram"),
        // Chrome
        "chrome" to listOf("chrome"),
        "google chrome" to listOf("chrome"),
        "क्रोम" to listOf("chrome"),
        // Gmail
        "gmail" to listOf("gmail"),
        "google mail" to listOf("gmail"),
        "जीमेल" to listOf("gmail"),
        // Telegram
        "telegram" to listOf("telegram"),
        "टेलीग्राम" to listOf("telegram"),
        // Facebook
        "facebook" to listOf("facebook"),
        "fb" to listOf("facebook"),
        "फेसबुक" to listOf("facebook"),
        // Spotify
        "spotify" to listOf("spotify"),
        "स्पॉटिफाई" to listOf("spotify"),
        // Settings
        "settings" to listOf("settings"),
        "setting" to listOf("settings"),
        "सेटिंग्स" to listOf("settings"),
        "सेटिंग" to listOf("settings"),
        // Maps
        "maps" to listOf("maps", "map"),
        "google maps" to listOf("maps"),
        "मैप्स" to listOf("maps"),
        // Phone / dialer
        "phone" to listOf("phone", "dialer", "call"),
        "dialer" to listOf("phone", "dialer"),
        "फोन" to listOf("phone", "dialer"),
        // Messages
        "messages" to listOf("messages", "messaging"),
        "sms" to listOf("messages", "messaging"),
        "message" to listOf("messages"),
        "मेसेज" to listOf("messages"),
        // Camera / Gallery
        "camera" to listOf("camera"),
        "कैमरा" to listOf("camera"),
        "gallery" to listOf("gallery", "photos"),
        "photos" to listOf("photos", "gallery"),
        "गैलरी" to listOf("gallery", "photos"),
        // Netflix
        "netflix" to listOf("netflix"),
        "नेटफ्लिक्स" to listOf("netflix"),
        // Calculator
        "calculator" to listOf("calculator"),
        "calc" to listOf("calculator"),
        "कैलकुलेटर" to listOf("calculator"),
        // Contacts
        "contacts" to listOf("contacts"),
        "contact" to listOf("contacts"),
        "कॉन्टैक्ट्स" to listOf("contacts"),
        // Play Store
        "play store" to listOf("play store", "playstore"),
        "playstore" to listOf("play store", "playstore"),
        "प्ले स्टोर" to listOf("play store")
    )

    fun normalize(s: String): String =
        s.lowercase()
            .replace(Regex("[\\p{Punct}…]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Compact form: letters+digits only (keeps Devanagari). */
    fun compact(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Expand a user-spoken app name into search tokens (canonical + raw).
     */
    fun searchTokens(userQuery: String): List<String> {
        val n = normalize(userQuery)
        if (n.isEmpty()) return emptyList()
        val c = compact(userQuery)
        val fromAlias = ALIASES[n] ?: ALIASES[c] ?: emptyList()
        // Also try without spaces for alias keys
        val noSpace = n.replace(" ", "")
        val fromNoSpace = ALIASES[noSpace] ?: emptyList()
        return (listOf(n, c) + fromAlias + fromNoSpace)
            .map { compact(it) }
            .filter { it.length >= 2 }
            .distinct()
    }

    /**
     * Rank launchable apps for [userQuery]. Higher score = better match.
     * Returns null if nothing scores above 0.
     */
    fun resolve(userQuery: String, apps: List<Launchable>): Launchable? {
        val qRaw = userQuery.trim()
        if (qRaw.isEmpty() || apps.isEmpty()) return null

        // Exact package name
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

    /**
     * Scoring:
     * 100 — compact label equals token
     * 90  — compact label starts with token
     * 80  — package name contains token (e.g. youtube)
     * 70  — compact label contains token
     * Prefer shorter labels when scores tie (avoid "YouTube Music" beating "YouTube").
     */
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
        // Prefer exact short launcher names over "X Music" / "X Studio"
        if (score >= 70 && labelC.length > 24) score -= 5
        return score
    }

    /**
     * Pull app name from natural-language open commands.
     * Supports: "YouTube kholo", "open Chrome", "यूट्यूब खोलो", "Instagram chalao"
     */
    fun extractOpenTarget(objective: String): String? {
        val raw = objective.trim()
        if (raw.isEmpty()) return null
        val t = raw.lowercase()

        val patterns = listOf(
            Regex("""(?:open|launch|start)\\s+(.+)""", RegexOption.IGNORE_CASE),
            Regex("""(.+?)\\s*(?:kholo|khol\\s*do|khol\\s*dena|khol\\s*diya|open\\s*karo|start\\s*karo|chalao|chala\\s*do)""", RegexOption.IGNORE_CASE),
            Regex("""(.+?)\\s*(?:खोलो|खोल\\s*दो|चलाओ)""")
        )
        for (p in patterns) {
            val m = p.find(raw) ?: p.find(t) ?: continue
            var name = m.groupValues[1].trim()
            name = name.removePrefix("the ").removePrefix("app ").trim()
            name = name.removeSuffix(" app").removeSuffix(" application").trim()
            // Drop trailing filler
            name = name.replace(Regex("""\\s+(please|pls|karo|do|dena)$""", RegexOption.IGNORE_CASE), "").trim()
            if (name.length in 2..40) {
                val lower = name.lowercase()
                if (lower.contains("wifi") || lower.contains("wi-fi")) continue
                return name
            }
        }
        // Bare known alias
        val n = normalize(raw)
        val c = compact(raw)
        if (ALIASES.containsKey(n) || ALIASES.containsKey(c)) return raw.trim()
        return null
    }
}
