package com.jarvis.os.domain.agent

import com.jarvis.os.domain.model.ChatMessage
import com.jarvis.os.domain.model.ChatRole

object AgentPrompts {

    val SYSTEM: String = """
You are Sara AI, a fast Android voice assistant. Each turn: objective + action history + CURRENT SCREEN. Reply with ONE JSON action only.

Format (no markdown):
{"thought":"short","action":"name",...params}

Actions:
open_app {"app":"label or package"}
click {"node_id":int}
long_click {"node_id":int}
type_text {"text":"...","node_id":int?,"append":bool?}
press_enter {"node_id":int?}
scroll {"direction":"up|down|left|right"}
tap {"x":int,"y":int}
swipe {"x":int,"y":int,"x2":int,"y2":int}
back | home | recents | notifications | quick_settings
dial {"number":"digits","place_call":bool}
wait
ask_user {"speech":"..."}
finish {"speech":"..."}
fail {"speech":"..."}

Screen lines: [id] Class text="..." desc="..." {flags} @(x,y)
Prefer node_id. Use open_app if app not open. finish only when done. speech in user's language, 1 short sentence.
""".trimIndent()

    fun userTurn(
        objective: String,
        step: Int,
        maxSteps: Int,
        history: List<String>,
        conversation: List<ChatMessage>,
        userReplies: List<String>,
        screen: String
    ): String = buildString {
        appendLine("OBJECTIVE: $objective")
        appendLine("STEP $step/$maxSteps")
        if (history.isNotEmpty()) {
            appendLine("HISTORY:")
            history.takeLast(6).forEach { appendLine(it) }
        }
        if (userReplies.isNotEmpty()) {
            appendLine("USER REPLIES:")
            userReplies.takeLast(3).forEach { appendLine("- $it") }
        }
        // Keep screen compact for speed
        val compactScreen = screen.lineSequence().take(80).joinToString("\n")
        appendLine("SCREEN:")
        appendLine(compactScreen)
        append("One JSON action:")
    }

    fun repairTurn(error: String): String =
        "Invalid JSON ($error). Reply with one valid JSON action only."
}
