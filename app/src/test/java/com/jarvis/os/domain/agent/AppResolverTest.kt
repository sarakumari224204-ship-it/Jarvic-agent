package com.jarvis.os.domain.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppResolverTest {

    private val sampleApps = listOf(
        AppResolver.Launchable("WhatsApp", "com.whatsapp", "com.whatsapp.Home"),
        AppResolver.Launchable("YouTube", "com.google.android.youtube", "com.google.android.youtube.Home"),
        AppResolver.Launchable("YouTube Music", "com.google.android.apps.youtube.music", "com.google.android.apps.youtube.music.Home"),
        AppResolver.Launchable("Chrome", "com.android.chrome", "com.google.android.apps.chrome.Main"),
        AppResolver.Launchable("Instagram", "com.instagram.android", "com.instagram.android.activity.Main"),
        AppResolver.Launchable("Gmail", "com.google.android.gm", "com.google.android.gm.ConversationList"),
        AppResolver.Launchable("Settings", "com.android.settings", "com.android.settings.Settings"),
        AppResolver.Launchable("Telegram", "org.telegram.messenger", "org.telegram.ui.Launch"),
        AppResolver.Launchable("Spotify", "com.spotify.music", "com.spotify.music.Main"),
        AppResolver.Launchable("Facebook", "com.facebook.katana", "com.facebook.katana.Login")
    )

    @Test
    fun resolvesEnglishYouTubeNotMusic() {
        val hit = AppResolver.resolve("YouTube", sampleApps)
        assertNotNull(hit)
        assertEquals("YouTube", hit!!.label)
        assertEquals("com.google.android.youtube", hit.packageName)
    }

    @Test
    fun resolvesHindiYouTube() {
        val hit = AppResolver.resolve("यूट्यूब", sampleApps)
        assertNotNull(hit)
        assertEquals("com.google.android.youtube", hit!!.packageName)
    }

    @Test
    fun resolvesWhatsApp() {
        val hit = AppResolver.resolve("WhatsApp", sampleApps)
        assertNotNull(hit)
        assertEquals("com.whatsapp", hit!!.packageName)
    }

    @Test
    fun resolvesHindiWhatsApp() {
        val hit = AppResolver.resolve("व्हाट्सऐप", sampleApps)
        assertNotNull(hit)
        assertEquals("com.whatsapp", hit!!.packageName)
    }

    @Test
    fun resolvesChromeInstagramGmail() {
        assertEquals("com.android.chrome", AppResolver.resolve("Chrome", sampleApps)!!.packageName)
        assertEquals("com.instagram.android", AppResolver.resolve("Instagram", sampleApps)!!.packageName)
        assertEquals("com.instagram.android", AppResolver.resolve("insta", sampleApps)!!.packageName)
        assertEquals("com.google.android.gm", AppResolver.resolve("Gmail", sampleApps)!!.packageName)
        assertEquals("com.android.settings", AppResolver.resolve("Settings", sampleApps)!!.packageName)
    }

    @Test
    fun nonexistentReturnsNull() {
        assertNull(AppResolver.resolve("FlappyBirdProXYZ", sampleApps))
    }

    @Test
    fun extractOpenTargetEnglish() {
        assertEquals("YouTube", AppResolver.extractOpenTarget("YouTube kholo"))
        assertEquals("Chrome", AppResolver.extractOpenTarget("Chrome open karo"))
        assertEquals("Instagram", AppResolver.extractOpenTarget("open Instagram"))
        assertEquals("WhatsApp", AppResolver.extractOpenTarget("WhatsApp kholo"))
    }

    @Test
    fun extractOpenTargetHindi() {
        assertEquals("यूट्यूब", AppResolver.extractOpenTarget("यूट्यूब खोलो"))
        assertNotNull(AppResolver.extractOpenTarget("Instagram chalao"))
    }

    @Test
    fun extractOpenTargetHinglishChalao() {
        assertEquals("Spotify", AppResolver.extractOpenTarget("Spotify chalao"))
    }

    @Test
    fun packageNameExactMatch() {
        val hit = AppResolver.resolve("com.whatsapp", sampleApps)
        assertNotNull(hit)
        assertEquals("com.whatsapp", hit!!.packageName)
    }

    @Test
    fun searchTokensIncludeAliases() {
        val tokens = AppResolver.searchTokens("यूट्यूब")
        assertTrue(tokens.any { it.contains("youtube") })
    }
}
