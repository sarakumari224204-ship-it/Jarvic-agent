package com.jarvis.os.accessibility

import kotlinx.serialization.Serializable

/** Result of any agent action. [message] is fed back to the LLM. */
data class ActionResult(val success: Boolean, val message: String) {
    companion object {
        fun ok(message: String = "OK") = ActionResult(true, message)
        fun fail(message: String) = ActionResult(false, message)
    }
}

@Serializable
data class BoundsRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

@Serializable
data class ScreenNode(
    val id: Int,
    val className: String,
    val text: String? = null,
    val contentDescription: String? = null,
    val hint: String? = null,
    val viewId: String? = null,
    val bounds: BoundsRect,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val scrollable: Boolean = false,
    val editable: Boolean = false,
    val focused: Boolean = false,
    val checked: Boolean? = null,
    val enabled: Boolean = true,
    val passwordField: Boolean = false
) {
    val role: String get() = className.substringAfterLast('.')

    /** Compact single-line form for LLM prompts (center coordinates only, to save tokens). */
    fun toPromptLine(): String = buildString {
        append('[').append(id).append("] ").append(role)
        text?.let { append(" text=\"").append(it.replace('\n', ' ')).append('"') }
        contentDescription?.let { append(" desc=\"").append(it.replace('\n', ' ')).append('"') }
        hint?.let { append(" hint=\"").append(it.replace('\n', ' ')).append('"') }
        viewId?.let { append(" id=").append(it) }
        val flags = buildList {
            if (clickable) add("clickable")
            if (longClickable) add("longClickable")
            if (scrollable) add("scrollable")
            if (editable) add("editable")
            if (focused) add("focused")
            if (checked != null) add(if (checked) "checked" else "unchecked")
            if (!enabled) add("disabled")
            if (passwordField) add("password")
        }
        if (flags.isNotEmpty()) append(" {").append(flags.joinToString(",")).append('}')
        append(" @(").append(bounds.centerX).append(',').append(bounds.centerY).append(')')
    }
}

@Serializable
data class ScreenSnapshot(
    val packageName: String?,
    val screenWidth: Int,
    val screenHeight: Int,
    val nodes: List<ScreenNode>,
    val truncated: Boolean = false,
    val blocked: Boolean = false
) {
    /** Case-insensitive match against text, content description, hint and view id. */
    fun find(query: String, clickableOnly: Boolean = false): List<ScreenNode> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return nodes.filter { n ->
            (!clickableOnly || n.clickable) &&
                listOf(n.text, n.contentDescription, n.hint, n.viewId)
                    .any { it?.contains(q, ignoreCase = true) == true }
        }
    }

    fun toPromptString(): String = buildString {
        append("App: ").append(packageName ?: "unknown")
            .append(" | Screen: ").append(screenWidth).append('x').append(screenHeight).append('\n')
        if (blocked) {
            append("(This app is on the blocklist. JARVIS may not read or operate it.)")
            return@buildString
        }
        if (nodes.isEmpty()) append("(no visible content)\n")
        nodes.forEach { append(it.toPromptLine()).append('\n') }
        if (truncated) append("(screen truncated: scroll or refine to see more)")
    }
}

enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

enum class GlobalCommand { HOME, BACK, RECENTS, NOTIFICATIONS, QUICK_SETTINGS }
