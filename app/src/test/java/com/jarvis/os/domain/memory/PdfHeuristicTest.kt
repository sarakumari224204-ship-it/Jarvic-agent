package com.jarvis.os.domain.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfHeuristicTest {

    private fun looksLikeScanned(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return true
        val head = bytes.copyOfRange(0, minOf(5, bytes.size)).toString(Charsets.ISO_8859_1)
        if (!head.startsWith("%PDF")) return false
        val sample = bytes.toString(Charsets.ISO_8859_1)
        val textOps = Regex("\\((?:\\\\.|[^\\)]){3,120}\\)").findAll(sample).count()
        val hasImage = sample.contains("/Image") || sample.contains("/XObject")
        return textOps < 3 && hasImage
    }

    @Test
    fun imageOnlyPdfFlagged() {
        val fake = (
            "%PDF-1.4\n" +
                "1 0 obj<< /Type /XObject /Subtype /Image >>endobj\n"
            ).toByteArray(Charsets.ISO_8859_1)
        assertTrue(looksLikeScanned(fake))
    }

    @Test
    fun textPdfNotFlaggedAsScanned() {
        val fake = (
            "%PDF-1.4\n" +
                "(Hello World Document Text Content Here) Tj\n" +
                "(More letters for the extractor) Tj\n" +
                "(Third text operator present) Tj\n" +
                "(Fourth block of letters) Tj\n"
            ).toByteArray(Charsets.ISO_8859_1)
        assertFalse(looksLikeScanned(fake))
    }

    @Test
    fun tinyBufferTreatedAsScanned() {
        assertTrue(looksLikeScanned(byteArrayOf(1, 2, 3)))
    }
}
