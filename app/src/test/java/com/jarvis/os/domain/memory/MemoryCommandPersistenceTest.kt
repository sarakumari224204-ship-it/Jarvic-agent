package com.jarvis.os.domain.memory

import com.jarvis.os.domain.agent.MemoryCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCommandPersistenceTest {

    @Test
    fun saveParseTrailingYaadRakh() {
        val p = MemoryCommand.parse("Mera naam Sara yaad rakh")
        assertNotNull(p)
        assertEquals(MemoryCommand.Op.SAVE, p!!.op)
        assertTrue(p.payload.contains("Sara") || p.payload.contains("naam"))
    }

    @Test
    fun forgetAll() {
        val p = MemoryCommand.parse("bhool jao sab")
        assertNotNull(p)
        assertEquals(MemoryCommand.Op.FORGET, p!!.op)
        assertEquals("*", p.payload)
    }

    @Test
    fun listPhrase() {
        val p = MemoryCommand.parse("yaad dikhao")
        assertNotNull(p)
        assertEquals(MemoryCommand.Op.LIST, p!!.op)
    }

    @Test
    fun nonMemoryNull() {
        assertNull(MemoryCommand.parse("YouTube kholo"))
        assertNull(MemoryCommand.parse("gana chalao"))
    }

    @Test
    fun snapshotRoundTripJsonShape() {
        val snap = TeachSnapshot(
            memories = listOf(
                SaraMemory(text = "Favourite colour blue", category = MemoryCategory.PREFERENCE)
            )
        )
        val json = kotlinx.serialization.json.Json.encodeToString(TeachSnapshot.serializer(), snap)
        val back = kotlinx.serialization.json.Json.decodeFromString(TeachSnapshot.serializer(), json)
        assertEquals(1, back.memories.size)
        assertEquals("Favourite colour blue", back.memories[0].text)
    }
}
