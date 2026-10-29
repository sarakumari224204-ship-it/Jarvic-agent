package com.jarvis.os.data.memory

import com.jarvis.os.domain.memory.KnowledgeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentImportTest {

    @Test
    fun classifiesPdfAndTxt() {
        assertEquals(KnowledgeType.FILE_PDF, DocumentImport.classifyNameAndMime("a.pdf", "application/pdf"))
        assertEquals(KnowledgeType.FILE_TXT, DocumentImport.classifyNameAndMime("notes.txt", "text/plain"))
        assertEquals(null, DocumentImport.classifyNameAndMime("pic.png", "image/png"))
    }

    @Test
    fun rejectsOversized() {
        val v = DocumentImport.validateImportBytes(
            "big.pdf",
            "application/pdf",
            DocumentImport.MAX_BYTES + 1,
            "%PDF-1.4".toByteArray()
        )
        assertFalse(v.ok)
        assertTrue(v.error!!.contains("badi"))
    }

    @Test
    fun acceptsReasonableTxt() {
        val v = DocumentImport.validateImportBytes(
            "note.txt",
            "text/plain",
            100,
            "hello".toByteArray()
        )
        assertTrue(v.ok)
        assertEquals(KnowledgeType.FILE_TXT, v.type)
    }

    @Test
    fun rejectsEmpty() {
        val v = DocumentImport.validateImportBytes("a.txt", "text/plain", 0, byteArrayOf())
        assertFalse(v.ok)
    }
}
