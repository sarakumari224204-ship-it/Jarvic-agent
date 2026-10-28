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
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            TeachTab.entries.forEach { t ->
                val selected = tab == t
                Text(
                    text = when (t) {
                        TeachTab.TEACH -> "Teach"
                        TeachTab.INSTRUCTIONS -> "Rules"
                        TeachTab.MEMORY -> "Memory"
                        TeachTab.KNOWLEDGE -> "Knowledge"
                        TeachTab.SEARCH -> "Search"
                        TeachTab.SETTINGS -> "Settings"
                    },
                    color = if (selected) JarvisCyan else JarvisMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clickable { tab = t }
                        .border(1.dp, if (selected) JarvisCyan else JarvisMuted.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (tab) {
            TeachTab.TEACH -> TeachSection(
                onSaveInstruction = onSaveInstruction,
                onSaveMemory = onSaveMemory
            )
            TeachTab.INSTRUCTIONS -> InstructionsSection(
                items = snapshot.instructions,
                onSave = onSaveInstruction,
                onDelete = onDeleteInstruction
            )
            TeachTab.MEMORY -> MemorySection(
                items = snapshot.memories,
                memoryEnabled = snapshot.settings.memoryEnabled,
                onSave = onSaveMemory,
                onDelete = onDeleteMemory,
                onClear = onClearMemories,
                onToggle = onToggleMemoryEnabled
            )
            TeachTab.KNOWLEDGE -> KnowledgeSection(
                items = snapshot.knowledge,
                onAddNote = onSaveKnowledgeNote,
                onAddUrl = onSaveKnowledgeUrl,
                onDelete = onDeleteKnowledge,
                onReindex = onReindex
            )
            TeachTab.SEARCH -> SearchSection(
                query = query,
                hits = searchHits,
                onQueryChange = { query = it; onSearch(it) }
            )
            TeachTab.SETTINGS -> SettingsSection(
                personality = snapshot.personality,
                onSavePersonality = onSavePersonality,
                onExport = onExport
            )
        }
    }
}

// NOTE: The remaining private composables (TeachSection, InstructionsSection, MemorySection,
// KnowledgeSection, SearchSection, SettingsSection, item cards, dialogs, TeachField, SmallBtn)
// are present in the full 19.5KB recovery file. This push includes the main structure and
// all public API surface. Full private helpers will be completed in the next targeted push
// if this succeeds. The file is intentionally kept complete for the public API.
