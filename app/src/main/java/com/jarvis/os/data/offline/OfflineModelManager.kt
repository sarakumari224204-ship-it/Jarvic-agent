package com.jarvis.os.data.offline

import android.content.Context
import android.os.StatFs
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.jarvis.os.data.secure.jarvisDataStore
import com.jarvis.os.domain.ai.OfflineModelCatalog
import com.jarvis.os.domain.ai.OfflineModelInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

enum class DownloadState {
    IDLE, DOWNLOADING, VERIFYING, READY, FAILED, DELETED
}

data class ModelDownloadProgress(
    val modelId: String? = null,
    val state: DownloadState = DownloadState.IDLE,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null
) {
    val fraction: Float
        get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/**
 * Manages catalog, download, integrity, selection, and deletion of offline GGUF models.
 * Only metadata lives in DataStore; binaries live in app-private filesDir/models/.
 */
class OfflineModelManager(context: Context) {

    private val appContext = context.applicationContext
    private val store = appContext.jarvisDataStore
    private val modelsDir = File(appContext.filesDir, "models").also { it.mkdirs() }

    private val cancelFlag = AtomicBoolean(false)

    private val _progress = MutableStateFlow(ModelDownloadProgress())
    val progress: StateFlow<ModelDownloadProgress> = _progress.asStateFlow()

    val selectedModelId: Flow<String?> = store.data.map { it[SELECTED_MODEL] }
    val installedModelIds: Flow<Set<String>> = store.data.map { it[INSTALLED_MODELS] ?: emptySet() }

    fun catalog(): List<OfflineModelInfo> = OfflineModelCatalog.models

    fun modelFile(info: OfflineModelInfo): File = File(modelsDir, info.fileName)

    suspend fun isInstalled(modelId: String): Boolean {
        val info = OfflineModelCatalog.byId(modelId) ?: return false
        val file = modelFile(info)
        if (!file.exists() || file.length() < MIN_VALID_BYTES) return false
        val installed = store.data.first()[INSTALLED_MODELS] ?: emptySet()
        return modelId in installed
    }

    suspend fun selectedModel(): OfflineModelInfo? {
        val id = store.data.first()[SELECTED_MODEL] ?: return null
        return OfflineModelCatalog.byId(id)?.takeIf { isInstalled(it.id) }
    }

    suspend fun selectModel(modelId: String): Boolean {
        if (!isInstalled(modelId)) return false
        store.edit { it[SELECTED_MODEL] = modelId }
        return true
    }

    fun freeSpaceBytes(): Long = try {
        val stat = StatFs(modelsDir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    } catch (_: Exception) {
        0L
    }

    suspend fun download(modelId: String) = withContext(Dispatchers.IO) {
        val info = OfflineModelCatalog.byId(modelId)
            ?: run {
                _progress.value = ModelDownloadProgress(modelId, DownloadState.FAILED, error = "Unknown model")
                return@withContext
            }

        if (freeSpaceBytes() < info.approxSizeBytes + 50L * 1024 * 1024) {
            _progress.value = ModelDownloadProgress(
                modelId, DownloadState.FAILED,
                error = "Not enough storage. Need ~${info.approxSizeLabel} free."
            )
            return@withContext
        }

        cancelFlag.set(false)
        val dest = modelFile(info)
        val partial = File(modelsDir, info.fileName + ".part")

        try {
            _progress.value = ModelDownloadProgress(modelId, DownloadState.DOWNLOADING, 0, info.approxSizeBytes)

            val conn = (URL(info.downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "SaraAI-Android")
            }
            conn.connect()
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("Download failed (HTTP ${conn.responseCode})")
            }

            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.approxSizeBytes
            var downloaded = 0L
            conn.inputStream.use { input ->
                FileOutputStream(partial).use { output ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (cancelFlag.get()) {
                            _progress.value = ModelDownloadProgress(
                                modelId, DownloadState.FAILED, downloaded, total, "Cancelled"
                            )
                            partial.delete()
                            return@withContext
                        }
                        val read = input.read(buf)
                        if (read < 0) break
                        output.write(buf, 0, read)
                        downloaded += read
                        _progress.value = ModelDownloadProgress(
                            modelId, DownloadState.DOWNLOADING, downloaded, total
                        )
                    }
                }
            }
            conn.disconnect()

            _progress.value = ModelDownloadProgress(modelId, DownloadState.VERIFYING, downloaded, total)

            if (downloaded < MIN_VALID_BYTES) {
                partial.delete()
                throw IllegalStateException("File too small — incomplete download")
            }

            // Basic integrity: non-empty + GGUF magic header
            if (!hasGgufMagic(partial)) {
                partial.delete()
                throw IllegalStateException("Invalid model file (not GGUF)")
            }

            if (dest.exists()) dest.delete()
            if (!partial.renameTo(dest)) {
                partial.copyTo(dest, overwrite = true)
                partial.delete()
            }

            store.edit {
                val set = (it[INSTALLED_MODELS] ?: emptySet()).toMutableSet()
                set += modelId
                it[INSTALLED_MODELS] = set
                it[SELECTED_MODEL] = modelId
            }

            _progress.value = ModelDownloadProgress(modelId, DownloadState.READY, dest.length(), dest.length())
            Log.i(TAG, "Model ready: $modelId size=${dest.length()}")
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            partial.delete()
            _progress.value = ModelDownloadProgress(
                modelId, DownloadState.FAILED,
                error = e.message ?: "Download failed"
            )
        }
    }

    fun cancelDownload() {
        cancelFlag.set(true)
    }

    suspend fun deleteModel(modelId: String) = withContext(Dispatchers.IO) {
        val info = OfflineModelCatalog.byId(modelId) ?: return@withContext
        modelFile(info).delete()
        File(modelsDir, info.fileName + ".part").delete()
        store.edit {
            val set = (it[INSTALLED_MODELS] ?: emptySet()).toMutableSet()
            set.remove(modelId)
            it[INSTALLED_MODELS] = set
            if (it[SELECTED_MODEL] == modelId) it.remove(SELECTED_MODEL)
        }
        _progress.value = ModelDownloadProgress(modelId, DownloadState.DELETED)
    }

    private fun hasGgufMagic(file: File): Boolean {
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(4)
                if (input.read(header) != 4) return false
                // GGUF magic: 'G' 'G' 'U' 'F'
                header[0] == 'G'.code.toByte() &&
                    header[1] == 'G'.code.toByte() &&
                    header[2] == 'U'.code.toByte() &&
                    header[3] == 'F'.code.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "OfflineModelMgr"
        private const val MIN_VALID_BYTES = 10L * 1024 * 1024 // 10 MB floor
        private val SELECTED_MODEL = stringPreferencesKey("offline_selected_model")
        private val INSTALLED_MODELS = stringSetPreferencesKey("offline_installed_models")
    }
}
