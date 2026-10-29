package com.jarvis.os.domain.memory

import com.jarvis.os.data.memory.KnowledgeIndexer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfHeuristicTest {

    @Test
    fun imageOnlyPdfFlagged() {
        val fake = (
            "%PDF-1.4\n" +
                "1 0 obj<< /Type /XObject /Subtype /Image >>endobj\n"
            ).toByteArray(Charsets.ISO_8859_1)
        assertTrue(KnowledgeIndexer.looksLikeScannedOrImagePdf(fake))
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
        assertFalse(KnowledgeIndexer.looksLikeScannedOrImagePdf(fake))
    }

    @Test
    fun tinyBufferTreatedAsScanned() {
        assertTrue(KnowledgeIndexer.looksLikeScannedOrImagePdf(byteArrayOf(1, 2, 3)))
    }
}
