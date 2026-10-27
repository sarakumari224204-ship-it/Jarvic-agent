package com.jarvis.os.domain.model

import com.jarvis.os.accessibility.GlobalCommand
import com.jarvis.os.accessibility.ScrollDirection
import com.jarvis.os.data.remote.AppJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Validated, typed action that the TaskEngine (Phase 4) executes. */
sealed interface AgentAction {
    data class OpenApp(val app: String) : AgentAction
    data class Click(val nodeId: Int) : AgentAction
    data class LongClick(val nodeId: Int) : AgentAction
    data class TypeText(val text: String, val nodeId: Int?, val append: Boolean) : AgentAction
    data class PressEnter(val nodeId: Int?) : AgentAction
    data class Scroll(val direction: ScrollDirection) : AgentAction
    data class Tap(val x: Int, val y: Int) : AgentAction
    data class Swipe(val x: Int, val y: Int, val x2: Int, val y2: Int) : AgentAction
    data class Global(val command: GlobalCommand) : AgentAction
    data class Dial(val number: String, val placeCall: Boolean) : AgentAction
    data object Wait : AgentAction
    data class AskUser(val speech: String) : AgentAction
    data class Finish(val speech: String) : AgentAction
    data class Fail(val speech: String) : AgentAction
}

data class AgentStep(val thought: String, val action: AgentAction)

/** Raw JSON shape returned by the model. Flat on purpose: easier for LLMs to get right. */
@Serializable
data class AgentDecision(
    val thought: String = "",
    val action: String,
    @SerialName("node_id") val nodeId: Int? = null,
    val x: Int? = null,
    val y: Int? = null,
    val x2: Int? = null,
    val y2: Int? = null,
    val text: String? = null,
    val append: Boolean? = null,
    val direction: String? = null,
    val app: String? = null,
    val number: String? = null,
    @SerialName("place_call") val placeCall: Boolean? = null,
    val speech: String? = null
)

object AgentDecisionParser {

    /** Parses and validates a model reply. Failure carries a message suitable for a retry prompt. */
    fun parse(raw: String): Result<AgentStep> = runCatching {
        val decision = AppJson.decodeFromString(AgentDecision.serializer(), extractJson(raw))
        AgentStep(decision.thought, decision.toAction())
    }

    private fun extractJson(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start in 0 until end) { "No JSON object found in the model reply." }
        return raw.substring(start, end + 1)
    }

    private fun AgentDecision.toAction(): AgentAction = when (action.trim().lowercase()) {
        "open_app" -> AgentAction.OpenApp(requireText(app, "app"))
        "click" -> AgentAction.Click(requireNotNull(nodeId) { "click needs node_id" })
        "long_click" -> AgentAction.LongClick(requireNotNull(nodeId) { "long_click needs node_id" })
        "type_text" -> AgentAction.TypeText(
            text = requireNotNull(text) { "type_text needs text" },
            nodeId = nodeId,
            append = append ?: false
        )
        "press_enter" -> AgentAction.PressEnter(nodeId)
        "scroll" -> AgentAction.Scroll(parseDirection(direction))
        "tap" -> AgentAction.Tap(
            requireNotNull(x) { "tap needs x" },
            requireNotNull(y) { "tap needs y" }
        )
        "swipe" -> AgentAction.Swipe(
            requireNotNull(x) { "swipe needs x" },
            requireNotNull(y) { "swipe needs y" },
            requireNotNull(x2) { "swipe needs x2" },
            requireNotNull(y2) { "swipe needs y2" }
        )
        "back" -> AgentAction.Global(GlobalCommand.BACK)
        "home" -> AgentAction.Global(GlobalCommand.HOME)
        "recents" -> AgentAction.Global(GlobalCommand.RECENTS)
        "notifications" -> AgentAction.Global(GlobalCommand.NOTIFICATIONS)
        "quick_settings" -> AgentAction.Global(GlobalCommand.QUICK_SETTINGS)
        "dial" -> AgentAction.Dial(requireText(number, "number"), placeCall ?: false)
        "wait" -> AgentAction.Wait
        "ask_user" -> AgentAction.AskUser(requireText(speech, "speech"))
        "finish" -> AgentAction.Finish(speech.orEmpty())
        "fail" -> AgentAction.Fail(speech.orEmpty())
        else -> throw IllegalArgumentException("Unknown action \"$action\".")
    }

    private fun requireText(value: String?, name: String): String =
        requireNotNull(value?.takeIf { it.isNotBlank() }) { "Action needs a non-empty \"$name\"." }

    private fun parseDirection(value: String?): ScrollDirection {
        val v = requireText(value, "direction").uppercase()
        return ScrollDirection.entries.firstOrNull { it.name == v }
            ?: throw IllegalArgumentException("direction must be up, down, left or right.")
    }
}

/** One-line description used in the ACTION HISTORY section of the next prompt. */
fun AgentAction.describe(): String = when (this) {
    is AgentAction.OpenApp -> "open_app(\"$app\")"
    is AgentAction.Click -> "click(node $nodeId)"
    is AgentAction.LongClick -> "long_click(node $nodeId)"
    is AgentAction.TypeText -> "type_text(\"${text.take(60)}\"${nodeId?.let { ", node $it" } ?: ""})"
    is AgentAction.PressEnter -> "press_enter"
    is AgentAction.Scroll -> "scroll(${direction.name.lowercase()})"
    is AgentAction.Tap -> "tap($x, $y)"
    is AgentAction.Swipe -> "swipe($x,$y -> $x2,$y2)"
    is AgentAction.Global -> command.name.lowercase()
    is AgentAction.Dial -> "dial($number, place_call=$placeCall)"
    AgentAction.Wait -> "wait"
    is AgentAction.AskUser -> "ask_user"
    is AgentAction.Finish -> "finish"
    is AgentAction.Fail -> "fail"
}
