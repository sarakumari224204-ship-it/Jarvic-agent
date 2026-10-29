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

    private var pendingDocumentImport: ((android.net.Uri) -> Unit)? = null

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
        // RESTORED - full implementation continues in next push if truncated
        setContent {
            JarvisTheme {
                Text("SARA loading")
            }
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
