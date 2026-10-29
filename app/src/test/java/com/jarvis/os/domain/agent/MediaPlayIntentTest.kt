package com.jarvis.os.domain.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPlayIntentTest {

    @Test
    fun ganaChalaoIsPlay() {
        val p = MediaPlayIntent.parse("gana chalao")
        assertNotNull(p)
        assertEquals(MediaPlayIntent.Kind.PLAY, p!!.kind)
    }

    @Test
    fun youtubeParGanaChalao() {
        val p = MediaPlayIntent.parse("YouTube par gana chalao")
        assertNotNull(p)
        assertEquals(MediaPlayIntent.Kind.PLAY, p!!.kind)
        assertTrue(p.mentionsYouTube)
    }

    @Test
    fun youtubePeArijit() {
        val p = MediaPlayIntent.parse("YouTube pe Arijit Singh ka gana chalao")
        assertNotNull(p)
        assertEquals(MediaPlayIntent.Kind.PLAY, p!!.kind)
        assertTrue(p.query.contains("Arijit", ignoreCase = true))
        assertTrue(p.query.contains("Singh", ignoreCase = true))
    }

    @Test
    fun believerChalao() {
        val p = MediaPlayIntent.parse("Believer chalao")
        assertNotNull(p)
        assertEquals(MediaPlayIntent.Kind.PLAY, p!!.kind)
        assertEquals("Believer", p.query)
    }

    @Test
    fun tumHiHoPlayKaro() {
        val p = MediaPlayIntent.parse("Tum Hi Ho play karo")
        assertNotNull(p)
        assertEquals(MediaPlayIntent.Kind.PLAY, p!!.kind)
        assertTrue(p.query.contains("Tum", ignoreCase = true))
        assertTrue(p.query.contains("Ho", ignoreCase = true))
    }

    @Test
    fun youtubeParSongPlayKaro() {
        val p = MediaPlayIntent.parse("YouTube par Shape of You play karo")
        assertNotNull(p)
        assertEquals(MediaPlayIntent.Kind.PLAY, p!!.kind)
        assertTrue(p.query.contains("Shape", ignoreCase = true))
    }

    @Test
    fun searchOnlyDoesNotPlay() {
        val p = MediaPlayIntent.parse("YouTube par search karo")
        assertNotNull(p)
        assertEquals(MediaPlayIntent.Kind.SEARCH_ONLY, p!!.kind)
        assertTrue(p.kind == MediaPlayIntent.Kind.SEARCH_ONLY)
        assertTrue(p.toAgentObjective().contains("do NOT play") || p.toAgentObjective().contains("NOT play"))
    }

    @Test
    fun searchWithQuery() {
        val p = MediaPlayIntent.parse("YouTube par Believer search karo")
        assertNotNull(p)
        assertEquals(MediaPlayIntent.Kind.SEARCH_ONLY, p!!.kind)
        assertTrue(p.query.contains("Believer", ignoreCase = true))
    }

    @Test
    fun bareYoutubeChalaoIsNotMedia() {
        assertNull(MediaPlayIntent.parse("YouTube chalao"))
        assertNull(MediaPlayIntent.parse("youtube open karo"))
    }

    @Test
    fun messageNotMedia() {
        assertNull(MediaPlayIntent.parse("WhatsApp par message bhejo"))
        assertNull(MediaPlayIntent.parse("gana message bhejo"))
    }

    @Test
    fun hindiGana() {
        val p = MediaPlayIntent.parse("गाना चलाओ")
        assertNotNull(p)
        assertEquals(MediaPlayIntent.Kind.PLAY, p!!.kind)
    }

    @Test
    fun extractOpenTargetDoesNotStealMedia() {
        assertNull(AppResolver.extractOpenTarget("gana chalao"))
        assertNull(AppResolver.extractOpenTarget("Believer chalao"))
        assertNull(AppResolver.extractOpenTarget("YouTube par gana chalao"))
        assertNull(AppResolver.extractOpenTarget("Tum Hi Ho play karo"))
        assertEquals("YouTube", AppResolver.extractOpenTarget("YouTube kholo"))
    }

    @Test
    fun agentObjectiveMentionsFinishSpeech() {
        val p = MediaPlayIntent.parse("Believer chalao")!!
        assertTrue(p.toAgentObjective().contains("Haan, gana chala diya"))
    }

    @Test
    fun ispeAchhaSaSongLagaoIsContextualPlay() {
        val p = MediaPlayIntent.parse("ispe achha sa song lagao")
        assertNotNull(p)
        assertEquals(MediaPlayIntent.Kind.PLAY, p!!.kind)
        assertTrue(p.contextual)
    }

    @Test
    fun lagaoVerbIsPlay() {
        val p = MediaPlayIntent.parse("Believer lagao")
        assertNotNull(p)
        assertEquals(MediaPlayIntent.Kind.PLAY, p!!.kind)
        assertTrue(p.query.contains("Believer", ignoreCase = true))
    }

    @Test
    fun knownAppLagaoStillOpenAppNotMedia() {
        assertNull(MediaPlayIntent.parse("Spotify lagao"))
        assertNull(MediaPlayIntent.parse("Instagram lagao"))
    }

    @Test
    fun yahanGanaChalaoContextual() {
        val p = MediaPlayIntent.parse("yahan koi gana chalao")
        assertNotNull(p)
        assertEquals(MediaPlayIntent.Kind.PLAY, p!!.kind)
        assertTrue(p.contextual)
    }
}
