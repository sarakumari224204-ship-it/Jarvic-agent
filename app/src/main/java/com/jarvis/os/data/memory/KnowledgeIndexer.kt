package com.jarvis.os.data.memory

import android.content.Context
import android.util.Log
import com.jarvis.os.domain.memory.KnowledgeChunk
import com.jarvis.os.domain.memory.KnowledgeItem
import com.jarvis.os.domain.memory.KnowledgeType
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.URI

/**
 * Extracts text, chunks, and indexes knowledge items locally.
 * Documents are reference-only — never written into system instruction space.
 */
class KnowledgeIndexer(
    private val context: Context,
    private val store: MemoryStore,
    private val http: HttpClient,
    private val chunkSize: Int = 900,
    private val chunkOverlap: Int = 120
) {

    suspend fun index(item: KnowledgeItem): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val text = when (item.type) {
                KnowledgeType.TEXT -> item.contentRef
                KnowledgeType.FILE_TXT -> readTxt(item.contentRef)
                KnowledgeType.FILE_PDF -> readPdfBestEffort(item.contentRef)
                KnowledgeType.URL -> fetchUrl(item.contentRef)
            }
            if (text.isNullOrBlank()) {
                store.markKnowledgeFailed(item.id, "Could not read this document.")
                return@withContext Result.failure(IllegalStateException("Could not read this document."))
            }
            val cleaned = clean(text)
            val chunks = chunk(cleaned, item)
            store.replaceChunks(item.id, chunks)
            Result.success(chunks.size)
        } catch (e: Exception) {
            Log.e(TAG, "index failed", e)
            store.markKnowledgeFailed(item.id, e.message ?: "Index failed")
            Result.failure(e)
        }
    }

    private fun readTxt(path: String): String? {
        val f = File(path)
        if (!f.exists() || !f.canRead()) return null
        return f.readText().take(MAX_DOC_CHARS)
    }

    private fun readPdfBestEffort(path: String): String? {
        val f = File(path)
        if (!f.exists()) return null
        val bytes = f.readBytes().let { if (it.size > MAX_PDF_BYTES) it.copyOf(MAX_PDF_BYTES) else it }
        if (looksLikeScannedOrImagePdf(bytes)) {
            throw IllegalStateException(
                "Yeh PDF scanned/image-based lagti hai. Text extract nahi hua; OCR abhi support nahi hai."
            )
        }
        val raw = bytes.toString(Charsets.ISO_8859_1)
        val matches = Regex("\\((?:\\\\.|[^\\)]){2,200}\\)").findAll(raw)
            .map { it.value.removePrefix("(").removeSuffix(")")
                .replace("\\n", "\n")
                .replace("\\r", "")
                .replace(Regex("\\\\([nrt\\(\\ )])"), " ")
            }
            .filter { it.any { ch -> ch.isLetter() } }
            .joinToString(" ")
        val extracted = matches.take(MAX_DOC_CHARS).ifBlank { null }
        if (extracted == null) {
            throw IllegalStateException(
                "PDF se text nahi nikal paya (encrypted, empty, ya image-only). OCR support nahi hai."
            )
        }
        return extracted
    }

    fun looksLikeScannedOrImagePdf(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return true
        val head = bytes.copyOfRange(0, minOf(5, bytes.size)).toString(Charsets.ISO_8859_1)
        if (!head.startsWith("%PDF")) return false
        val sample = bytes.toString(Charsets.ISO_8859_1)
        val textOps = Regex("\\((?:\\\\.|[^\\)]){3,120}\\)").findAll(sample).count()
        val hasImage = sample.contains("/Image") || sample.contains("/XObject")
        return textOps < 3 && hasImage
    }

    private suspend fun fetchUrl(url: String): String? {
        val uri = try {
            URI(url).toURL()
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid URL")
        }
        if (uri.protocol != "http" && uri.protocol != "https") {
            throw IllegalArgumentException("Only http/https URLs are supported")
        }
        val body = withTimeoutOrNull(12_000L) {
            val response = http.get(url)
            if (!response.status.isSuccess()) {
                throw IllegalStateException("HTTP ${response.status.value}")
            }
            response.bodyAsText()
        } ?: throw IllegalStateException("URL timeout")

        val noScript = body
            .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
        val text = noScript.replace(Regex("<[^>]+>"), " ")
            .replace(Regex("&nbsp;|&#160;"), " ")
            .replace(Regex("&amp;"), "&")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (text.length < 40) throw IllegalStateException("Empty or login-walled page")
        return text.take(MAX_DOC_CHARS)
    }

    private fun clean(text: String): String =
        text.replace(Regex("[\\r\\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .replace(Regex(" {2,}"), " ")
            .trim()

    private fun chunk(text: String, item: KnowledgeItem): List<KnowledgeChunk> {
        if (text.length <= chunkSize) {
            return listOf(
                KnowledgeChunk(
                    knowledgeId = item.id,
                    index = 0,
                    text = text,
                    sourceLabel = item.title
                )
            )
        }
        val out = mutableListOf<KnowledgeChunk>()
        var start = 0
        var i = 0
        while (start < text.length && i < MAX_CHUNKS) {
            val end = (start + chunkSize).coerceAtMost(text.length)
            val slice = text.substring(start, end).trim()
            if (slice.isNotEmpty()) {
                out += KnowledgeChunk(
                    knowledgeId = item.id,
                    index = i,
                    text = slice,
                    sourceLabel = item.title
                )
                i++
            }
            if (end >= text.length) break
            start = (end - chunkOverlap).coerceAtLeast(start + 1)
        }
        return out
    }

    companion object {
        private const val TAG = "KnowledgeIndexer"
        private const val MAX_PDF_BYTES = 8_000_000
        private const val MAX_DOC_CHARS = 200_000
        private const val MAX_CHUNKS = 80
    }
}
