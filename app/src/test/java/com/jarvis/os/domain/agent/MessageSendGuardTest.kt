package com.jarvis.os.domain.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSendGuardTest {

    @Test
    fun messagingObjectiveDetected() {
        assertTrue(MessageSendGuard.isMessagingObjective("WhatsApp par message karo"))
        assertTrue(MessageSendGuard.isMessagingObjective("Rahul ko msg bhejo"))
        assertFalse(MessageSendGuard.isMessagingObjective("YouTube kholo"))
    }

    @Test
    fun unconfirmedSendBlockedAfterType() {
        assertTrue(
            MessageSendGuard.blockUnconfirmedSend(
                objective = "WhatsApp par hi bhejo",
                typedOutbound = true,
                userReplies = emptyList(),
                alreadyAskedConfirm = false
            )
        )
    }

    @Test
    fun confirmedSendAllowed() {
        assertFalse(
            MessageSendGuard.blockUnconfirmedSend(
                objective = "WhatsApp par message karo",
                typedOutbound = true,
                userReplies = listOf("haan bhej do"),
                alreadyAskedConfirm = true
            )
        )
    }

    @Test
    fun denialRecognized() {
        assertTrue(MessageSendGuard.isUserDenial("nahi"))
        assertTrue(MessageSendGuard.isUserDenial("cancel"))
        assertFalse(MessageSendGuard.isUserConfirmation("nahi"))
    }

    @Test
    fun nonMessagingPressEnterNotBlocked() {
        assertFalse(
            MessageSendGuard.blockUnconfirmedSend(
                objective = "YouTube par search karo",
                typedOutbound = true,
                userReplies = emptyList(),
                alreadyAskedConfirm = false
            )
        )
    }

    @Test
    fun noTypeMeansNoBlock() {
        assertFalse(
            MessageSendGuard.blockUnconfirmedSend(
                objective = "WhatsApp kholo",
                typedOutbound = false,
                userReplies = emptyList(),
                alreadyAskedConfirm = false
            )
        )
    }
}
