package com.jarvis.os.accessibility

import android.graphics.Rect

// ---------------------------------------------------------------------------
// Data models used by JarvisAccessibilityService and the TaskEngine
// ---------------------------------------------------------------------------

data class BoundsRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

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
    fun role(): String = when {
        editable -> "EditText"
        className.contains("Button", ignoreCase = true) -> "Button"
        className.contains("Image", ignoreCase = true) -> "Image"
        className.contains("Text", ignoreCase = true) -> "Text"
        className.contains("Switch", ignoreCase = true) || className.contains("CheckBox", ignoreCase = true) -> "Toggle"
        className.contains("Recycler", ignoreCase = true) || className.contains("ListView", ignoreCase = true) -> "List"
        else -> className.substringAfterLast('.').ifEmpty { "View" }
    }

    fun flags(): String = buildString {
        if (clickable) append("clickable ")
        if (longClickable) append("longClickable ")
        if (scrollable) append("scrollable ")
        if (editable) append("editable ")
        if (focused) append("focused ")
        if (checked == true) append("checked ")
        if (checked == false) append("unchecked ")
        if (!enabled) append("disabled ")
        if (passwordField) append("password ")
    }.trim()
}

data class ScreenSnapshot(
    val packageName: String?,
    val width: Int,
    val height: Int,
    val nodes: List<ScreenNode>,
    val truncated: Boolean = false,
    val blocked: Boolean = false
) {
    /** Compact text representation for the LLM prompt. */
    fun toPrompt(): String {
        if (blocked) return "[BLOCKED] This package is not allowed."
        if (nodes.isEmpty()) return "[EMPTY] No readable nodes on screen (pkg=$packageName ${width}x$height)."

        return buildString {
            appendLine("pkg=$packageName size=${width}x$height nodes=${nodes.size}${if (truncated) " truncated" else ""}")
            nodes.forEach { n ->
                append("[")
                append(n.id)
                append("] ")
                append(n.role())
                n.text?.let { append(" text=\"$it\"") }
                n.contentDescription?.let { append(" desc=\"$it\"") }
                n.hint?.let { append(" hint=\"$it\"") }
                n.viewId?.let { append(" id=$it") }
                val f = n.flags()
                if (f.isNotEmpty()) append(" {$f}")
                append(" @(")
                append(n.bounds.centerX)
                append(",")
                append(n.bounds.centerY)
                appendLine(")")
            }
        }.trimEnd()
    }
}

data class ActionResult(
    val success: Boolean,
    val message: String
) {
    companion object {
        fun ok(message: String) = ActionResult(true, message)
        fun fail(message: String) = ActionResult(false, message)
    }
}

enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

enum class GlobalCommand { BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS }
