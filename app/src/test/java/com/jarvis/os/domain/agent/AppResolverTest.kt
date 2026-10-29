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
    fun extractYouTubeWithFillers() {
        assertEquals("YouTube", AppResolver.extractOpenTarget("YouTube please kholo"))
        assertEquals("YouTube", AppResolver.extractOpenTarget("please YouTube kholo"))
        assertEquals("YouTube", AppResolver.extractOpenTarget("YouTube ko kholo"))
        assertEquals("YouTube", AppResolver.extractOpenTarget("zara YouTube kholo"))
        assertEquals("YouTube", AppResolver.extractOpenTarget("jara YouTube kholo"))
        assertEquals("YouTube", AppResolver.extractOpenTarget("YouTube open karo"))
        assertEquals("YouTube", AppResolver.extractOpenTarget("YouTube ko open karo"))
        assertEquals("YouTube", AppResolver.extractOpenTarget("YouTube chalao"))
        assertEquals("YouTube", AppResolver.extractOpenTarget("YouTube chala do"))
        assertEquals("YouTube", AppResolver.extractOpenTarget("open YouTube"))
        assertEquals("YouTube", AppResolver.extractOpenTarget("open YouTube please"))
        assertEquals("YouTube", AppResolver.extractOpenTarget("please open YouTube"))
        assertEquals("YouTube", AppResolver.extractOpenTarget("start YouTube"))
        assertEquals("YouTube", AppResolver.extractOpenTarget("launch YouTube"))
        assertEquals("YouTube", AppResolver.extractOpenTarget("  YouTube   kholo  "))
        assertEquals("YouTube", AppResolver.extractOpenTarget("YouTube, kholo!"))
    }

    @Test
    fun extractYouTubeHindiFillers() {
        assertEquals("यूट्यूब", AppResolver.extractOpenTarget("यूट्यूब खोलो"))
        assertEquals("यूट्यूब", AppResolver.extractOpenTarget("यूट्यूब को खोलो"))
        assertEquals("यूट्यूब", AppResolver.extractOpenTarget("जरा यूट्यूब खोलो"))
        assertEquals("यूट्यूब", AppResolver.extractOpenTarget("यूट्यूब खोल दो"))
        assertEquals("यूट्यूब", AppResolver.extractOpenTarget("प्लीज यूट्यूब खोलो"))
        assertEquals("यूट्यूब", AppResolver.extractOpenTarget("यूट्यूब चलाओ"))
        assertEquals("यूट्यूब", AppResolver.extractOpenTarget("यूट्यूब चला दो"))
    }

    @Test
    fun extractWhatsAppFillers() {
        assertEquals("WhatsApp", AppResolver.extractOpenTarget("WhatsApp kholo"))
        assertEquals("WhatsApp", AppResolver.extractOpenTarget("WhatsApp ko kholo"))
        assertEquals("WhatsApp", AppResolver.extractOpenTarget("please WhatsApp kholo"))
        assertEquals("WhatsApp", AppResolver.extractOpenTarget("WhatsApp open karo"))
        assertEquals("WhatsApp", AppResolver.extractOpenTarget("WhatsApp chalao"))
        assertEquals("व्हाट्सऐप", AppResolver.extractOpenTarget("व्हाट्सऐप खोलो"))
        assertEquals("व्हाट्सएप", AppResolver.extractOpenTarget("व्हाट्सएप को खोलो"))
    }

    @Test
    fun extractChromeFillers() {
        assertEquals("Chrome", AppResolver.extractOpenTarget("Chrome kholo"))
        assertEquals("Chrome", AppResolver.extractOpenTarget("Chrome ko kholo"))
        assertEquals("Chrome", AppResolver.extractOpenTarget("please Chrome open karo"))
        assertEquals("क्रोम", AppResolver.extractOpenTarget("क्रोम खोलो"))
    }

    @Test
    fun extractInstagramFillers() {
        assertEquals("Instagram", AppResolver.extractOpenTarget("Instagram kholo"))
        assertEquals("Insta", AppResolver.extractOpenTarget("Insta kholo"))
        assertEquals("Instagram", AppResolver.extractOpenTarget("Instagram ko open karo"))
        assertEquals("इंस्टाग्राम", AppResolver.extractOpenTarget("इंस्टाग्राम खोलो"))
    }

    @Test
    fun extractRejectsNonLaunchCommands() {
        assertNull(AppResolver.extractOpenTarget("YouTube par search karo"))
        assertNull(AppResolver.extractOpenTarget("YouTube video search karo"))
        assertNull(AppResolver.extractOpenTarget("YouTube ke baare mein batao"))
        assertNull(AppResolver.extractOpenTarget("WhatsApp par message bhejo"))
        assertNull(AppResolver.extractOpenTarget("WhatsApp par call karo"))
        assertNull(AppResolver.extractOpenTarget("Chrome mein search karo"))
        assertNull(AppResolver.extractOpenTarget("YouTube par search"))
        assertNull(AppResolver.extractOpenTarget("search YouTube"))
    }

    @Test
    fun extractThenResolveYouTubePleaseKholo() {
        val target = AppResolver.extractOpenTarget("YouTube please kholo")
        assertNotNull(target)
        val hit = AppResolver.resolve(target!!, sampleApps)
        assertEquals("com.google.android.youtube", hit!!.packageName)
    }

    @Test
    fun extractThenResolveZaraYouTube() {
        val target = AppResolver.extractOpenTarget("zara YouTube kholo")
        assertEquals("com.google.android.youtube", AppResolver.resolve(target!!, sampleApps)!!.packageName)
    }

    @Test
    fun extractThenResolveHindiKo() {
        val target = AppResolver.extractOpenTarget("यूट्यूब को खोलो")
        assertEquals("com.google.android.youtube", AppResolver.resolve(target!!, sampleApps)!!.packageName)
    }
}
