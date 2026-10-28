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
import com.jarvis.os.data.memory.MemoryStore
import com.jarvis.os.domain.ai.AIMode
import com.jarvis.os.domain.memory.KnowledgeItem
import com.jarvis.os.domain.memory.KnowledgeType
import com.jarvis.os.domain.memory.MemorySettings
import com.jarvis.os.domain.model.ApiSettings
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = (application as JarvisApp).container

        setContent {
            JarvisTheme {
                val settings by container.keyStore.settings.collectAsState(initial = ApiSettings())
                val aiMode by container.keyStore.aiMode.collectAsState(initial = AIMode.AUTO)
                val installed by container.offlineModelManager.installedModelIds.collectAsState(initial = emptySet())
                val selectedId by container.offlineModelManager.selectedModelId.collectAsState(initial = null)
                val downloadProgress by container.offlineModelManager.progress.collectAsState()
                val teachSnap by container.memoryStore.snapshot.collectAsState()
                var showTeach by remember { mutableStateOf(false) }
                var searchHits by remember { mutableStateOf<List<MemoryStore.SearchHit>>(emptyList()) }

                if (showTeach) {
                    TeachSaraScreen(
                        snapshot = teachSnap,
                        searchHits = searchHits,
                        onSearch = { q -> searchHits = container.memoryStore.search(q) },
                        onSaveInstruction = { ins ->
                            lifecycleScope.launch { container.memoryStore.upsertInstruction(ins) }
                        },
                        onDeleteInstruction = { id ->
                            lifecycleScope.launch { container.memoryStore.deleteInstruction(id) }
                        },
                        onSaveMemory = { mem ->
                            lifecycleScope.launch {
                                val dup = container.memoryStore.findDuplicateMemory(mem.text)
                                if (dup != null && dup.id != mem.id) {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Similar memory already exists.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                val conflict = container.memoryStore.findConflictingMemory(mem.text)
                                if (conflict != null && conflict.id != mem.id) {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Possible conflict with existing memory.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                                container.memoryStore.upsertMemory(mem).onFailure {
                                    Toast.makeText(
                                        this@MainActivity,
                                        it.message ?: "Save failed",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
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
                                    title = title,
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
                                    title = title,
                                    type = KnowledgeType.URL,
                                    source = url,
                                    contentRef = url
                                )
                                container.memoryStore.upsertKnowledge(item)
                                container.knowledgeIndexer.index(item).onFailure {
                                    Toast.makeText(
                                        this@MainActivity,
                                        it.message ?: "Could not read URL",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
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
                                    MemorySettings(memoryEnabled = enabled)
                                )
                            }
                        },
                        onSavePersonality = { p ->
                            lifecycleScope.launch { container.memoryStore.savePersonality(p) }
                        },
                        onExport = {
                            lifecycleScope.launch {
                                val json = container.memoryStore.exportJson()
                                val cm = getSystemService(ClipboardManager::class.java)
                                cm.setPrimaryClip(ClipData.newPlainText("sara_teach_backup", json))
                                Toast.makeText(
                                    this@MainActivity,
                                    "Backup JSON copied to clipboard",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onClose = { showTeach = false }
                    )
                } else {
                    JarvisHudScreen(
                        settings = settings,
                        aiMode = aiMode,
                        installedModelIds = installed,
                        selectedModelId = selectedId,
                        downloadProgress = downloadProgress,
                        freeSpaceLabel = {
                            val free = container.offlineModelManager.freeSpaceBytes()
                            val gb = free / (1024.0 * 1024.0 * 1024.0)
                            "%.1f GB free".format(gb)
                        },
                        onSaveGemini = { key, model ->
                            lifecycleScope.launch { container.keyStore.saveGemini(key, model) }
                        },
                        onSaveEleven = { key, voice, model ->
                            lifecycleScope.launch { container.keyStore.saveElevenLabs(key, voice, model) }
                        },
                        onSaveVoiceMode = { mode ->
                            lifecycleScope.launch { container.keyStore.saveVoiceMode(mode) }
                        },
                        onSaveAiMode = { mode ->
                            lifecycleScope.launch { container.keyStore.saveAiMode(mode) }
                        },
                        onDownloadModel = { id ->
                            lifecycleScope.launch { container.offlineModelManager.download(id) }
                        },
                        onCancelDownload = { container.offlineModelManager.cancelDownload() },
                        onSelectModel = { id ->
                            lifecycleScope.launch { container.offlineModelManager.selectModel(id) }
                        },
                        onDeleteModel = { id ->
                            lifecycleScope.launch {
                                container.offlineAIEngine.unload()
                                container.offlineModelManager.deleteModel(id)
                            }
                        },
                        onOpenTeachSara = { showTeach = true },
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
                        onRequestPermissions = { requestNeededPermissions() },
                        onStartListening = { maybeStartService() },
                        onStopListening = { JarvisForegroundService.stop(this@MainActivity) },
                        isAccessibilityEnabled = { JarvisAccessibilityService.isEnabled(this@MainActivity) }
                    )
                }
            }
        }

        requestNeededPermissions()
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
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
