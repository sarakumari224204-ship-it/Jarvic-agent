package com.jarvis.os.domain.agent

/**
 * Lightweight offline intent routing for voice commands.
 * Deterministic — unit-testable. Does not call the network.
 */
object IntentClassifier {

    enum class Kind {
        DEVICE_ACTION,
        MEDIA_PLAY,
        MEMORY_OP,
        DOCUMENT_QA,
        CONVERSATION,
        AMBIGUOUS
    }

    data class Classification(
        val kind: Kind,
        val confidence: Float,
        val reason: String
    )

    private val DOC_HINTS = listOf(
        "document", "pdf", "file mein", "file me", "knowledge", "notes mein",
        "is document", "imported", "dokumen", "डॉक्यूमेंट", "पीडीएफ"
    )

    private val MEMORY_HINTS = listOf(
        "yaad rakh", "yaad rakho", "yaad rakhna", "yaad kar", "remember",
        "bhool jao", "bhool ja", "forget", "yaad hai", "kya yaad", "meri memory",
        "teach", "sikhao", "sikha do", "yaad dikhao", "list memory",
        "याद रख", "भूल जा", "सिख"
    )

    private val DEVICE_HINTS = listOf(
        "kholo", "khol", "open", "launch", "start", "band karo", "close",
        "click", "tap", "scroll", "swipe", "type", "likho", "bhejo", "send",
        "call", "dial", "phone", "home", "back", "settings", "wifi",
        "brightness", "volume", "screenshot", "खोलो", "भेजो", "कॉल", "चलाओ", "लगाओ",
        "message", "msg", "whatsapp par"
    )

    fun classify(raw: String): Classification {
        val text = raw.trim()
        if (text.isEmpty()) return Classification(Kind.CONVERSATION, 0.2f, "empty")

        if (MediaPlayIntent.isMediaCommand(text)) {
            return Classification(Kind.MEDIA_PLAY, 0.95f, "media_play_intent")
        }

        val lower = text.lowercase()
        val memoryScore = MEMORY_HINTS.count { lower.contains(it) }
        if (memoryScore > 0) {
            return Classification(Kind.MEMORY_OP, 0.85f + 0.05f * memoryScore.coerceAtMost(2), "memory_markers")
        }

        val docScore = DOC_HINTS.count { lower.contains(it) }
        if (docScore > 0 && (lower.contains("kya") || lower.contains("what") || lower.contains("batao") || lower.contains("tell"))) {
            return Classification(Kind.DOCUMENT_QA, 0.8f, "document_qa")
        }

        if (AppResolver.extractOpenTarget(text) != null) {
            return Classification(Kind.DEVICE_ACTION, 0.9f, "open_app")
        }

        val deviceScore = DEVICE_HINTS.count { lower.contains(it) }
        if (deviceScore > 0) {
            return Classification(Kind.DEVICE_ACTION, 0.75f + 0.05f * deviceScore.coerceAtMost(3), "device_markers")
        }

        if (text.length < 120 && (lower.startsWith("kya") || lower.startsWith("what") ||
                lower.startsWith("kaise") || lower.startsWith("how") || lower.endsWith("?"))
        ) {
            return Classification(Kind.CONVERSATION, 0.7f, "question")
        }

        return Classification(Kind.CONVERSATION, 0.55f, "default_chat")
    }

    fun isDeviceLike(raw: String): Boolean {
        val k = classify(raw).kind
        return k == Kind.DEVICE_ACTION || k == Kind.MEDIA_PLAY
    }
}
