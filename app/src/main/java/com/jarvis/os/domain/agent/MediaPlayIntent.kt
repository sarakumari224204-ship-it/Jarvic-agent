package com.jarvis.os.domain.agent

/**
 * Detects Hindi/Hinglish/English "play song / search on YouTube" voice commands.
 * Pure JVM — unit-testable. Does not launch apps itself.
 */
object MediaPlayIntent {

    enum class Kind { PLAY, SEARCH_ONLY }

    data class Parsed(
        val kind: Kind,
        /** Free-text query (song/artist). Empty for generic "gana chalao" / "ispe song lagao". */
        val query: String,
        val mentionsYouTube: Boolean,
        /** True when user refers to current screen ("ispe", "yahan") rather than a titled track. */
        val contextual: Boolean = false
    ) {
        fun toAgentObjective(): String {
            val q = when {
                query.isNotBlank() -> query
                contextual -> "popular songs"
                else -> "songs"
            }
            return when (kind) {
                Kind.PLAY -> buildString {
                    append("Play music/video on YouTube. ")
                    if (contextual) {
                        append("User is referring to the CURRENT screen (ispe/yahan). ")
                        append("If YouTube already shows search results or a playable video list, click a good matching result. ")
                        append("Otherwise search for \"$q\". ")
                    } else {
                        append("Search for \"$q\" and play the best matching song or video. ")
                    }
                    append("Steps: (1) if search bar or Search icon is visible, open search; ")
                    append("(2) type_text the query when searching; (3) press_enter or click Search; ")
                    append("(4) click the best matching result (prefer official/topic, avoid random Shorts if possible); ")
                    append("(5) wait once if needed; (6) finish with short Hindi like \"Haan, gana chala diya.\" ")
                    append("Do not open other apps. Do not type chat messages. Do not loop. Prefer node_id.")
                }
                Kind.SEARCH_ONLY ->
                    "On YouTube only search for \"$q\" — show results, do NOT play or open a video. " +
                        "Open search if needed, type_text, press_enter, then finish with short Hindi like \"Search results aa gaye.\" " +
                        "Do not click a result to play. Do not loop."
            }
        }
    }

    private val YT_MARKERS = listOf("youtube", "yt", "यूट्यूब", "यू ट्यूब")

    private val PLAY_VERBS = listOf(
        "chalao", "chala do", "chala dena", "play karo", "play kardo", "play kar do",
        "play", "sunao", "suna do", "sunaao",
        "lagao", "laga do", "laga dena", "laga",
        "चलाओ", "चला दो", "चला देना", "सुनाओ", "सुना दो",
        "लगाओ", "लगा दो", "लगा देना"
    )

    private val SEARCH_VERBS = listOf(
        "search karo", "search kardo", "search kar do", "dhundo", "dhundho", "khojo",
        "सर्च करो", "खोजो", "ढूंढो"
    )

    private val SONG_MARKERS = listOf(
        "gana", "gaana", "song", "music", "track", "audio", "video",
        "गाना", "गाने", "सॉन्ग", "म्यूजिक", "वीडियो"
    )

    private val CONTEXT_MARKERS = listOf(
        "ispe", "is pe", "ispar", "is par", "yahan", "yaha", "yahin",
        "here", "this", "current",
        "इसपे", "इस पर", "यहाँ", "यहां"
    )

    private val KNOWN_APP_NAMES = setOf(
        "youtube", "yt", "whatsapp", "instagram", "insta", "chrome", "gmail",
        "telegram", "facebook", "fb", "spotify", "settings", "setting",
        "maps", "phone", "dialer", "messages", "camera", "gallery", "photos",
        "netflix", "calculator", "contacts",
        "यूट्यूब", "व्हाट्सऐप", "व्हाट्सएप", "वाट्सएप", "इंस्टाग्राम", "इंस्टा",
        "क्रोम", "जीमेल", "टेलीग्राम", "फेसबुक", "सेटिंग्स", "सेटिंग", "फोन"
    )

    private val MESSAGE_MARKERS = listOf(
        "message", "msg", "bhejo", "bhej", "send", "whatsapp", "telegram",
        "मैसेज", "भेजो", "व्हाट्सऐप"
    )

    fun parse(objective: String): Parsed? {
        val raw = objective.trim()
        if (raw.isEmpty()) return null
        val lower = normalize(raw)

        if (MESSAGE_MARKERS.any { lower.contains(it) }) return null

        val mentionsYt = YT_MARKERS.any { lower.contains(it) }
        val hasSongWord = SONG_MARKERS.any { lower.contains(it) }
        val isPlay = PLAY_VERBS.any { lower.contains(it) }
        val isSearch = SEARCH_VERBS.any { lower.contains(it) }
        val contextual = CONTEXT_MARKERS.any { lower.contains(it) }

        if (isSearch && !isPlay) {
            if (mentionsYt || hasSongWord) {
                val q = extractQuery(raw, lower)
                return Parsed(Kind.SEARCH_ONLY, q, mentionsYt, contextual = false)
            }
        }

        if (isPlay) {
            val q = extractQuery(raw, lower)
            val titled = q.isNotBlank() && !isOnlyFillerQuery(q)
            if (titled && isKnownAppName(q) && !hasSongWord && !contextual) {
                return null
            }
            if (mentionsYt && !hasSongWord && !titled && !contextual && isBareAppPlay(lower)) {
                return null
            }
            if (contextual && (hasSongWord || isPlay)) {
                return Parsed(Kind.PLAY, q, mentionsYt, contextual = true)
            }
            if (mentionsYt || hasSongWord || titled) {
                return Parsed(Kind.PLAY, q, mentionsYt, contextual = contextual)
            }
        }

        if (contextual && hasSongWord) {
            val q = extractQuery(raw, lower)
            return Parsed(Kind.PLAY, q, mentionsYt, contextual = true)
        }

        return null
    }

    fun isMediaCommand(objective: String): Boolean = parse(objective) != null

    private fun isBareAppPlay(lower: String): Boolean {
        val stripped = PLAY_VERBS.fold(lower) { acc, v -> acc.replace(v, " ") }
            .let { YT_MARKERS.fold(it) { a, m -> a.replace(m, " ") } }
            .replace(Regex("\\s+"), " ")
            .trim()
        return stripped.isEmpty() || stripped in listOf("par", "pe", "on", "app", "ko")
    }

    private fun isKnownAppName(q: String): Boolean {
        val t = q.lowercase().trim()
        if (t in KNOWN_APP_NAMES) return true
        val compact = t.filter { it.isLetterOrDigit() }
        return KNOWN_APP_NAMES.any { it.filter { ch -> ch.isLetterOrDigit() } == compact }
    }

    private fun isOnlyFillerQuery(q: String): Boolean {
        val t = q.lowercase().trim()
        val fillers = SONG_MARKERS + CONTEXT_MARKERS + listOf(
            "ka", "ki", "ke", "the", "a", "an", "some", "ek", "achha", "accha",
            "acha", "sa", "si", "se", "good", "nice", "best", "koi", "any"
        )
        return t.isEmpty() || t.split(" ").all { it in fillers }
    }

    internal fun extractQuery(original: String, lower: String = normalize(original)): String {
        var work = original.trim()
        val stripWords = YT_MARKERS + CONTEXT_MARKERS + listOf(
            "par", "pe", "on", "mein", "me", "in", "ko",
            "का", "की", "के", "पर", "पे", "में",
            "achha", "accha", "acha", "sa", "si", "se", "good", "nice", "best", "ek", "koi"
        )
        for (m in stripWords) {
            work = work.replace(Regex("(?i)\\b${Regex.escape(m)}\\b"), " ")
        }
        for (m in listOf("यूट्यूब", "पर", "पे", "में", "को", "का", "की", "के", "इसपे", "यहाँ", "यहां")) {
            work = work.replace(m, " ")
        }
        val verbs = (PLAY_VERBS + SEARCH_VERBS).sortedByDescending { it.length }
        for (v in verbs) {
            work = work.replace(Regex("(?i)${Regex.escape(v)}"), " ")
        }
        for (s in SONG_MARKERS) {
            work = work.replace(Regex("(?i)\\b${Regex.escape(s)}\\b"), " ")
        }
        for (s in listOf("गाना", "गाने", "सॉन्ग", "वीडियो")) {
            work = work.replace(s, " ")
        }
        work = work.replace(Regex("(?i)\\b(ka|ki|ke|the|a|an)\\b"), " ")
        work = work.replace(Regex("\\s+"), " ").trim()
        work = work.trim(' ', ',', '.', '!', '?', '"', '\'')
        return work.trim()
    }

    private fun normalize(s: String): String =
        s.lowercase()
            .replace(Regex("[\\p{Punct}…]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
