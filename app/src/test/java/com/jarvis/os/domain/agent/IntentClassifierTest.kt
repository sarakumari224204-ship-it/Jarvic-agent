package com.jarvis.os.domain.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentClassifierTest {

    @Test
    fun youtubeKholoIsDevice() {
        val c = IntentClassifier.classify("YouTube kholo")
        assertEquals(IntentClassifier.Kind.DEVICE_ACTION, c.kind)
    }

    @Test
    fun ganaChalaoIsMedia() {
        val c = IntentClassifier.classify("gana chalao")
        assertEquals(IntentClassifier.Kind.MEDIA_PLAY, c.kind)
    }

    @Test
    fun ispeSongLagaoIsMedia() {
        val c = IntentClassifier.classify("ispe achha sa song lagao")
        assertEquals(IntentClassifier.Kind.MEDIA_PLAY, c.kind)
    }

    @Test
    fun yaadRakhIsMemory() {
        val c = IntentClassifier.classify("Mera naam Rahul yaad rakh")
        assertEquals(IntentClassifier.Kind.MEMORY_OP, c.kind)
    }

    @Test
    fun kyaYaadHaiIsMemory() {
        val c = IntentClassifier.classify("kya yaad hai")
        assertEquals(IntentClassifier.Kind.MEMORY_OP, c.kind)
    }

    @Test
    fun whatIsCapitalIsConversation() {
        val c = IntentClassifier.classify("What is the capital of France?")
        assertEquals(IntentClassifier.Kind.CONVERSATION, c.kind)
    }

    @Test
    fun deviceLikeHelper() {
        assertTrue(IntentClassifier.isDeviceLike("WhatsApp kholo"))
        assertTrue(IntentClassifier.isDeviceLike("Believer chalao"))
        assertFalse(IntentClassifier.isDeviceLike("kya yaad hai"))
    }

    @Test
    fun memoryCommandParseSave() {
        val p = MemoryCommand.parse("Mera favourite colour blue yaad rakh")
        assertNotNull(p)
        assertEquals(MemoryCommand.Op.SAVE, p!!.op)
        assertTrue(p.payload.contains("blue", ignoreCase = true) || p.payload.contains("colour", ignoreCase = true))
    }

    @Test
    fun memoryCommandParseList() {
        val p = MemoryCommand.parse("kya yaad hai")
        assertNotNull(p)
        assertEquals(MemoryCommand.Op.LIST, p!!.op)
    }

    @Test
    fun memoryCommandParseForget() {
        val p = MemoryCommand.parse("bhool jao blue")
        assertNotNull(p)
        assertEquals(MemoryCommand.Op.FORGET, p!!.op)
        assertTrue(p.payload.contains("blue"))
    }

    @Test
    fun whatsappMessageIsDeviceNotMedia() {
        assertFalse(MediaPlayIntent.isMediaCommand("WhatsApp par message karo"))
        val c = IntentClassifier.classify("WhatsApp par message karo")
        assertEquals(IntentClassifier.Kind.DEVICE_ACTION, c.kind)
    }

    @Test
    fun screenParKyaHaiIsScreenRead() {
        assertTrue(IntentClassifier.isScreenReadQuery("screen par kya hai"))
        assertTrue(IntentClassifier.isScreenReadQuery("Screen pe kya dikh raha hai?"))
        assertTrue(IntentClassifier.isScreenReadQuery("what's on my screen"))
        assertEquals(IntentClassifier.Kind.SCREEN_READ, IntentClassifier.classify("screen par kya hai").kind)
    }

    @Test
    fun youtubeKholoIsNotScreenRead() {
        assertFalse(IntentClassifier.isScreenReadQuery("YouTube kholo"))
        assertFalse(IntentClassifier.isScreenReadQuery("screen par like karo"))
    }
}
