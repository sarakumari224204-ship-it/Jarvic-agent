package com.jarvis.os.domain.agent

/**
 * Application-level guard: do not finalize messaging sends without explicit confirmation.
 * Pure JVM — unit-testable. Does not depend on Accessibility.
 */
object MessageSendGuard {

    private val MESSAGING_MARKERS = listOf(
        "whatsapp", "telegram", "message", "msg", "bhejo", "bhej do", "send",
        "मैसेज", "भेजो", "व्हाट्सऐप", "टेलीग्राम"
    )

    private val CONFIRM_YES = listOf(
        "haan", "han", "yes", "ok", "okay", "send", "bhejo", "bhej do", "confirm",
        "haaji", "ha", "yep", "sure", "kar do", "karo",
        "हाँ", "हां", "भेजो", "ठीक"
    )

    private val CONFIRM_NO = listOf(
        "nahi", "na", "no", "cancel", "mat", "don't", "dont",
        "नहीं", "मत", "रद्द"
    )

    fun isMessagingObjective(objective: String): Boolean {
        val t = objective.lowercase()
        return MESSAGING_MARKERS.any { t.contains(it) }
    }

    fun isUserConfirmation(reply: String): Boolean {
        val t = reply.lowercase().trim()
        if (t.isEmpty()) return false
        if (CONFIRM_NO.any { t == it || t.startsWith("$it ") }) return false
        return CONFIRM_YES.any { t == it || t.startsWith("$it ") || t.contains(it) }
    }

    fun isUserDenial(reply: String): Boolean {
        val t = reply.lowercase().trim()
        return CONFIRM_NO.any { t == it || t.startsWith("$it ") }
    }

    fun blockUnconfirmedSend(
        objective: String,
        typedOutbound: Boolean,
        userReplies: List<String>,
        alreadyAskedConfirm: Boolean
    ): Boolean {
        if (!isMessagingObjective(objective)) return false
        if (!typedOutbound) return false
        if (userReplies.any { isUserConfirmation(it) }) return false
        return true
    }

    fun confirmationPrompt(lastTyped: String?): String {
        val snippet = lastTyped?.take(40)?.let { " \"$it\"" } ?: ""
        return "Message$snippet bhejun? Haan ya nahi bolo."
    }
}
