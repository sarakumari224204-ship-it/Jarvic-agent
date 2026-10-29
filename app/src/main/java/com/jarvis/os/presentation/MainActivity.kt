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
import com.jarvis.os.domain.model.ApiSettings
import com.jarvis.os.domain.model.VoiceMode
import com.jarvis.os.presentation.ui.JarvisHudScreen
import com.jarvis.os.presentation.ui.JarvisTheme
import com.jarvis.os.presentation.ui.TeachSaraScreen
import com.jarvis.os.service.JarvisForegroundService
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true) {
            maybeStartService()
        }
    }

    private var pendingDocumentImport: ((Uri) -> Unit)? = null

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
        pendingDocumentImport?.invoke(uri)
        pendingDocumentImport = null
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
            val downloadProgress by container.offlineModelManager.progress.collectAsState()
            val teachSnapshot by container.memoryStore.snapshot.collectAsState()
            var showTeach by remember { mutableStateOf(false) }

            JarvisTheme {
                if (showTeach) {
                    TeachSaraScreen(
                        snapshot = teachSnapshot,
                        searchHits = emptyList(),
                        onSearch = { q -> container.memoryStore.search(q) },
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
                        onUpdateSettings = { settings ->
                            lifecycleScope.launch { container.memoryStore.updateSettings(settings) }
                        },
                        onUpdatePersonality = { p ->
                            lifecycleScope.launch { container.memoryStore.updatePersonality(p) }
                        },
                        onImportDocument = { onUri ->
                            pendingDocumentImport = onUri
                            documentPicker.launch(arrayOf("application/pdf", "text/plain", "text/*"))
                        },
                        onImportUri = { uri, onDone ->
                            lifecycleScope.launch {
                                val result = DocumentImport.import(
                                    this@MainActivity,
                                    uri,
                                    container.knowledgeIndexer,
                                    container.memoryStore
                                )
                                onDone(result)
                            }
                        },
                        onBack = { showTeach = false }
                    )
                } else {
                    JarvisHudScreen(
                        agentStatus = container.runtime?.let { null } ?: run {
                            val status by com.jarvis.os.runtime.JarvisRuntime.agentStatus.collectAsState()
                            status
                        },
                        isListening = run {
                            val v by com.jarvis.os.runtime.JarvisRuntime.isListening.collectAsState()
                            v
                        },
                        partialSpeech = run {
                            val v by com.jarvis.os.runtime.JarvisRuntime.partialSpeech.collectAsState()
                            v
                        },
                        serviceRunning = run {
                            val v by com.jarvis.os.runtime.JarvisRuntime.serviceRunning.collectAsState()
                            v
                        },
                        apiSettings = apiSettings,
                        aiMode = aiMode,
                        installedModelIds = installedModelIds,
                        selectedModelId = selectedModelId,
                        downloadProgress = downloadProgress,
                        accessibilityEnabled = JarvisAccessibilityService.isEnabled(this@MainActivity),
                        onStartService = { maybeStartService() },
                        onStopService = {
                            stopService(Intent(this@MainActivity, JarvisForegroundService::class.java))
                            com.jarvis.os.runtime.JarvisRuntime.setServiceRunning(false)
                        },
                        onOpenAccessibilitySettings = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onSaveApiSettings = { s ->
                            lifecycleScope.launch { container.keyStore.save(s) }
                        },
                        onSetAiMode = { mode ->
                            lifecycleScope.launch { container.keyStore.setAiMode(mode) }
                        },
                        onSelectModel = { id ->
                            lifecycleScope.launch { container.offlineModelManager.selectModel(id) }
                        },
                        onDownloadModel = { id ->
                            lifecycleScope.launch { container.offlineModelManager.downloadModel(id) }
                        },
                        onOpenTeachSara = { showTeach = true },
                        onCopyLogs = {
                            val clip = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                            clip.setPrimaryClip(ClipData.newPlainText("sara", "Sara AI logs"))
                            Toast.makeText(this@MainActivity, "Copied", Toast.LENGTH_SHORT).show()
                        }
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
            val intent = Intent(this, JarvisForegroundService::class.java)
            startForegroundService(intent)
        }
    }
}
