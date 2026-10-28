package com.jarvis.os.domain.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class InputGuardTest {

    @Test
    fun rejectsEmptyAndShort() {
        val g = InputGuard()
        assertEquals("empty", g.rejectReason("   "))
        assertEquals("too_short", g.rejectReason("a"))
    }

    @Test
    fun blocksImmediateDuplicateCallback() {
        val g = InputGuard(duplicateWindowMs = 2500)
        val t0 = 1_000_000L
        assertNull(g.rejectReason("Chrome kholo", t0))
        g.markAccepted("Chrome kholo", t0)
        assertEquals("repeat_window", g.rejectReason("chrome kholo", t0 + 500))
    }

    @Test
    fun allowsIntentionalRepeatAfterWindow() {
        val g = InputGuard(duplicateWindowMs = 2500)
        val t0 = 1_000_000L
        g.markAccepted("Chrome kholo", t0)
        assertNull(g.rejectReason("Chrome kholo", t0 + 3000))
    }

    @Test
    fun blocksTtsEcho() {
        val g = InputGuard()
        g.registerSpoken("Chrome khol diya.")
        assertNotNull(g.rejectReason("chrome khol diya"))
    }

    @Test
    fun doesNotBlockUnrelatedCommand() {
        val g = InputGuard()
        g.registerSpoken("Chrome khol diya.")
        assertNull(g.rejectReason("YouTube kholo"))
    }
}
