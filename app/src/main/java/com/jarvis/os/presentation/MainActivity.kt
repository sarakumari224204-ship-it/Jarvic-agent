package com.jarvis.os.presentation

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jarvis.os.JarvisApp
import com.jarvis.os.accessibility.JarvisAccessibilityService
import com.jarvis.os.data.memory.DocumentImport
import com.jarvis.os.data.offline.ModelDownloadProgress
import com.jarvis.os.domain.ai.AIMode
import com.jarvis.os.domain.memory.KnowledgeItem
import com.jarvis.os.domain.memory.KnowledgeType
import com.jarvis.os.domain.memory.MemorySettings
import com.jarvis.os.domain.memory.PersonalitySettings
import com.jarvis.os.domain.memory.SaraInstruction
import com.jarvis.os.domain.memory.SaraMemory
import com.jarvis.os.domain.memory.TeachSnapshot
import com.jarvis.os.domain.model.ApiSettings
import com.jarvis.os.domain.model.VoiceMode
import com.jarvis.os.presentation.ui.JarvisHudScreen
import com.jarvis.os.presentation.ui.JarvisTheme
import com.jarvis.os.presentation.ui.TeachSaraScreen
import com.jarvis.os.service.JarvisForegroundService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true) {
            maybeStartService()
        }
    }

    private val documentPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            Toast.makeText(this, "Import cancel ho gaya.", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        }
        val app = application as JarvisApp
        val container = app.container
        lifecycleScope.launch {
            val result = DocumentImport.importUri(this@MainActivity, uri)
            result.fold(
                onSuccess = { spec ->
                    val item = KnowledgeItem(
                        title = spec.displayName,
                        type = spec.type,
                        source = spec.localPath,
                        contentRef = spec.localPath
                    )
                    container.memoryStore.upsertKnowledge(item)
                    container.knowledgeIndexer.index(item).fold(
                        onSuccess = { n ->
                            Toast.makeText(this@MainActivity, "Imported ${spec.displayName} ($n chunks)", Toast.LENGTH_SHORT).show()
                        },
                        onFailure = { e ->
                            Toast.makeText(this@MainActivity, "Index: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    )
                },
                onFailure = { e ->
                    Toast.makeText(this@MainActivity, "Import fail: ${e.message}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as JarvisApp
        val container = app.container

        setContent {
            val apiSettings by container.keyStore.settings.collectAsState(initial = ApiSettings())
            val aiMode by container.keyStore.aiMode.collectAsState(initial = AIMode.AUTO)
            val installedModelIds by container.offlineModelManager.installedModelIds.collectAsState(initial = emptySet())
            val selectedModelId by container.offlineModelManager.selectedModelId.collectAsState(initial = null)
            val downloadProgress by container.offlineModelManager.progress.collectAsState(initial = ModelDownloadProgress())
            val teachSnapshot by container.memoryStore.snapshot.collectAsState(initial = TeachSnapshot())
            var showTeach by remember { mutableStateOf(false) }
            var searchHits by remember { mutableStateOf(emptyList<com.jarvis.os.data.memory.MemoryStore.SearchHit>()) }

            JarvisTheme {
                if (showTeach) {
                    TeachSaraScreen(
                        snapshot = teachSnapshot,
                        searchHits = searchHits,
                        onSearch = { q -> searchHits = container.memoryStore.search(q) },
                        onSaveInstruction = { item ->
                            lifecycleScope.launch { container.memoryStore.upsertInstruction(item) }
                        },
                        onDeleteInstruction = { id ->
                            lifecycleScope.launch { container.memoryStore.deleteInstruction(id) }
                        },
                        onSaveMemory = { item ->
                            lifecycleScope.launch { container.memoryStore.upsertMemory(item) }
                        },
                        onDeleteMemory = { id ->
                            lifecycleScope.launch { container.memoryStore.deleteMemory(id) }
                        },
                        onClearMemories = {
                            lifecycleScope.launch { container.memoryStore.clearMemories() }
                        },
                        onSaveKnowledgeNote = { title, text ->
                            lifecycleScope.launch {
                                val item = KnowledgeItem(
                                    title = title.ifBlank { "Note" },
                                    type = KnowledgeType.TEXT,
                                    source = "note",
                                    contentRef = text
                                )
                                container.memoryStore.upsertKnowledge(item)
                                container.knowledgeIndexer.index(item)
                            }
                        },
                        onSaveKnowledgeUrl = { title, url ->
                            lifecycleScope.launch {
                                val item = KnowledgeItem(
                                    title = title.ifBlank { url.take(40) },
                                    type = KnowledgeType.URL,
                                    source = url,
                                    contentRef = url
                                )
                                container.memoryStore.upsertKnowledge(item)
                                container.knowledgeIndexer.index(item).onFailure {
                                    Toast.makeText(this@MainActivity, "URL index: ${it.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onImportDocument = {
                            documentPicker.launch(arrayOf("application/pdf", "text/plain", "text/*"))
                        },
                        onDeleteKnowledge = { id ->
                            lifecycleScope.launch { container.memoryStore.deleteKnowledge(id) }
                        },
                        onReindex = { id ->
                            lifecycleScope.launch {
                                val item = container.memoryStore.current().knowledge.find { it.id == id }
                                if (item != null) container.knowledgeIndexer.index(item)
                            }
                        },
                        onToggleMemoryEnabled = { enabled ->
                            lifecycleScope.launch {
                                container.memoryStore.saveSettings(
                                    container.memoryStore.current().settings.copy(memoryEnabled = enabled)
                                )
                            }
                        },
                        onSavePersonality = { p ->
                            lifecycleScope.launch { container.memoryStore.savePersonality(p) }
                        },
                        onExport = {
                            lifecycleScope.launch {
                                val json = container.memoryStore.exportJson()
                                val clip = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                                clip.setPrimaryClip(ClipData.newPlainText("sara-memory", json))
                                Toast.makeText(this@MainActivity, "Exported to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onClose = { showTeach = false }
                    )
                } else {
                    JarvisHudScreen(
                        settings = apiSettings,
                        aiMode = aiMode,
                        installedModelIds = installedModelIds,
                        selectedModelId = selectedModelId,
                        downloadProgress = downloadProgress,
                        freeSpaceLabel = {
                            try {
                                val bytes = container.offlineModelManager.freeSpaceBytes()
                                "${bytes / (1024 * 1024)} MB free"
                            } catch (_: Exception) { "" }
                        },
                        onSaveGemini = { key, model ->
                            lifecycleScope.launch { container.keyStore.saveGemini(key, model) }
                        },
                        onSaveEleven = { key, voiceId, model ->
                            lifecycleScope.launch { container.keyStore.saveElevenLabs(key, voiceId, model) }
                        },
                        onSaveVoiceMode = { mode ->
                            lifecycleScope.launch { container.keyStore.saveVoiceMode(mode) }
                        },
                        onSaveAiMode = { mode ->
                            lifecycleScope.launch { container.keyStore.saveAiMode(mode) }
                        },
                        onOpenTeachSara = { showTeach = true },
                        onDownloadModel = { id ->
                            lifecycleScope.launch { container.offlineModelManager.download(id) }
                        },
                        onCancelDownload = { container.offlineModelManager.cancelDownload() },
                        onSelectModel = { id ->
                            lifecycleScope.launch { container.offlineModelManager.selectModel(id) }
                        },
                        onDeleteModel = { id ->
                            lifecycleScope.launch { container.offlineModelManager.deleteModel(id) }
                        },
                        onOpenAccessibilitySettings = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onOpenOverlaySettings = {
                            startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")
                                )
                            )
                        },
                        onRequestPermissions = { ensurePermissions() },
                        onStartListening = { maybeStartService() },
                        onStopListening = { JarvisForegroundService.stop(this@MainActivity) },
                        isAccessibilityEnabled = { JarvisAccessibilityService.isEnabled(this@MainActivity) }
                    )
                }
            }
        }

        ensurePermissions()
    }

    private fun ensurePermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            maybeStartService()
        }
    }

    private fun maybeStartService() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            JarvisForegroundService.start(this)
        }
    }
}
