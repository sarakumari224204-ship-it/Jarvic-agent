package com.jarvis.os.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSnapshotTest {

    @Test
    fun rootUnavailablePromptDoesNotClaimDisabled() {
        val s = ScreenSnapshot(
            packageName = null,
            width = 1080,
            height = 1920,
            nodes = emptyList(),
            rootUnavailable = true
        )
        val p = s.toPrompt()
        assertTrue(p.contains("SCREEN_LOADING"))
        assertTrue(p.contains("wait"))
        assertFalse(p.startsWith("[BLOCKED]"))
        assertTrue(p.contains("Do NOT claim accessibility is disabled"))
    }

    @Test
    fun blockedPromptIsPrivacyNotDisabled() {
        val s = ScreenSnapshot(
            packageName = "com.jarvis.os",
            width = 1080,
            height = 1920,
            nodes = emptyList(),
            blocked = true
        )
        val p = s.toPrompt()
        assertTrue(p.contains("BLOCKED"))
        assertFalse(p.contains("SCREEN_LOADING"))
    }

    @Test
    fun normalPromptListsNodes() {
        val node = ScreenNode(
            id = 1,
            className = "android.widget.TextView",
            text = "Believer",
            bounds = BoundsRect(0, 0, 100, 50),
            clickable = true
        )
        val s = ScreenSnapshot("com.google.android.youtube", 1080, 1920, listOf(node))
        val p = s.toPrompt()
        assertTrue(p.contains("youtube"))
        assertTrue(p.contains("Believer"))
        assertTrue(p.contains("[1]"))
    }
}
