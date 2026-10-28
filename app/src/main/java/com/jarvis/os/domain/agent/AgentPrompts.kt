package com.jarvis.os.domain.agent

import com.jarvis.os.domain.model.ChatMessage

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

Rules:
- Prefer node_id over coordinates.
- Use open_app if the target app is not already open.
- Never type the same message more than once. After a successful type_text for messaging, press_enter once then finish.
- Do not keep sending messages in a loop.
- Do not ask_user unless you truly need missing info.
- finish only when the user objective is done; speech = one short sentence in the user's language.
- If the user only wants a spoken answer (no phone UI action), reply with finish and the answer — do not open apps.
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
        val compactScreen = screen.lineSequence().take(80).joinToString("\n")
        appendLine("SCREEN:")
        appendLine(compactScreen)
        append("One JSON action (no loops):")
    }

    fun repairTurn(error: String): String =
        "Invalid JSON ($error). Reply with one valid JSON action only."
}
