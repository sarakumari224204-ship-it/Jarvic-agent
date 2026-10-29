package com.jarvis.os.data.memory

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.jarvis.os.domain.memory.KnowledgeType
import java.io.File
import java.io.FileOutputStream

/**
 * SAF import helpers: copy picked URI into app storage with size limits.
 */
object DocumentImport {

    const val MAX_BYTES = 8_000_000L

    data class ImportSpec(
        val displayName: String,
        val type: KnowledgeType,
        val localPath: String
    )

    data class Validation(
        val ok: Boolean,
        val type: KnowledgeType? = null,
        val error: String? = null
    )

    fun classifyNameAndMime(name: String, mime: String?): KnowledgeType? {
        val n = name.lowercase()
        val m = mime?.lowercase().orEmpty()
        return when {
            n.endsWith(".pdf") || m.contains("pdf") -> KnowledgeType.FILE_PDF
            n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".text") ||
                m.startsWith("text/") -> KnowledgeType.FILE_TXT
            else -> null
        }
    }

    fun validateImportBytes(name: String, mime: String?, size: Long, head: ByteArray): Validation {
        if (size <= 0L) return Validation(false, error = "File empty hai.")
        if (size > MAX_BYTES) return Validation(false, error = "File bahut badi hai (max 8MB).")
        val type = classifyNameAndMime(name, mime)
            ?: return Validation(false, error = "Sirf PDF ya TXT support hai.")
        return Validation(true, type = type)
    }

    fun queryDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) {
                val n = c.getString(idx)
                if (!n.isNullOrBlank()) return n
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "document"
    }

    fun querySize(context: Context, uri: Uri): Long {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst()) {
                val s = c.getLong(idx)
                if (s > 0) return s
            }
        }
        return -1L
    }

    fun importUri(context: Context, uri: Uri): Result<ImportSpec> = runCatching {
        val name = queryDisplayName(context, uri)
        val mime = context.contentResolver.getType(uri)
        val dir = File(context.filesDir, "knowledge").apply { mkdirs() }
        val safeName = name.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(80)
        val outFile = File(dir, "${System.currentTimeMillis()}_$safeName")

        var total = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(outFile).use { output ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_BYTES) {
                        outFile.delete()
                        throw IllegalStateException("File bahut badi hai (max 8MB).")
                    }
                    output.write(buf, 0, n)
                }
            }
        } ?: throw IllegalStateException("File open nahi ho payi.")

        val headBytes = outFile.inputStream().use { it.readBytes().take(64).toByteArray() }
        val validation = validateImportBytes(name, mime, total, headBytes)
        if (!validation.ok || validation.type == null) {
            outFile.delete()
            throw IllegalStateException(validation.error ?: "Import fail")
        }
        ImportSpec(displayName = name, type = validation.type, localPath = outFile.absolutePath)
    }
}
