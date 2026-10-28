package com.jarvis.os.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jarvis.os.JarvisApp
import com.jarvis.os.accessibility.JarvisAccessibilityService
import com.jarvis.os.domain.ai.AIMode
import com.jarvis.os.domain.model.ApiSettings
import com.jarvis.os.domain.model.VoiceMode
import com.jarvis.os.presentation.ui.JarvisHudScreen
import com.jarvis.os.presentation.ui.JarvisTheme
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
