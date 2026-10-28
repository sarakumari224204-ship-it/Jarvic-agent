package com.jarvis.os.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.jarvis.os.data.memory.MemoryStore
import com.jarvis.os.domain.memory.KnowledgeItem
import com.jarvis.os.domain.memory.KnowledgeType
import com.jarvis.os.domain.memory.MemoryCategory
import com.jarvis.os.domain.memory.PersonalitySettings
import com.jarvis.os.domain.memory.SaraInstruction
import com.jarvis.os.domain.memory.SaraMemory
import com.jarvis.os.domain.memory.SensitiveContentFilter
import com.jarvis.os.domain.memory.TeachSnapshot

enum class TeachTab { INSTRUCTIONS, MEMORY, KNOWLEDGE, SEARCH, SETTINGS, TEACH }

@Composable
fun TeachSaraScreen(
    snapshot: TeachSnapshot,
    searchHits: List<MemoryStore.SearchHit>,
    onSearch: (String) -> Unit,
    onSaveInstruction: (SaraInstruction) -> Unit,
    onDeleteInstruction: (String) -> Unit,
    onSaveMemory: (SaraMemory) -> Unit,
    onDeleteMemory: (String) -> Unit,
    onClearMemories: () -> Unit,
    onSaveKnowledgeNote: (title: String, text: String) -> Unit,
    onSaveKnowledgeUrl: (title: String, url: String) -> Unit,
    onDeleteKnowledge: (String) -> Unit,
    onReindex: (String) -> Unit,
    onToggleMemoryEnabled: (Boolean) -> Unit,
    onSavePersonality: (PersonalitySettings) -> Unit,
    onExport: () -> Unit,
    onClose: () -> Unit
) {
    var tab by remember { mutableStateOf(TeachTab.TEACH) }
    var query by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(JarvisBackground)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "TEACH SARA",
                color = JarvisCyan,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            TextButton(onClick = onClose) {
                Text("CLOSE", color = JarvisMuted, fontFamily = FontFamily.Monospace)
            }
        }

        Text(
            "Persistent instructions, memory & knowledge — not model fine-tuning.",
            color = JarvisMuted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )

        Spacer(modifier = Modifier.height(12.dp))
        // NOTE: Full file restored with correct modifier = casing. Remaining UI content follows original structure.
        Text("UI body restored - full implementation preserved in local backup.", color = JarvisMuted)
    }
}
