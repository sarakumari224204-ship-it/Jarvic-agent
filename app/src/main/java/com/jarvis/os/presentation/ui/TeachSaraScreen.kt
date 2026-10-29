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
            TeachTab.TEACH -> TeachInputSection(
                onSaveInstruction = onSaveInstruction,
                onSaveMemory = onSaveMemory,
                onSaveKnowledgeNote = onSaveKnowledgeNote
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
            TeachTab.SEARCH -> {
                TeachField("Search instructions, memory, knowledge", query) {
                    query = it
                    onSearch(it)
                }
                Spacer(modifier = Modifier.height(8.dp))
                searchHits.forEach { hit ->
                    Text(
                        "${hit.type}: ${hit.title}\n${hit.snippet}",
                        color = JarvisOnSurface,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                if (searchHits.isEmpty() && query.isNotBlank()) {
                    Text("No matches", color = JarvisMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
            TeachTab.SETTINGS -> SettingsSection(
                personality = snapshot.personality,
                onSavePersonality = onSavePersonality,
                onExport = onExport
            )
        }
    }
}

@Composable
private fun TeachInputSection(
    onSaveInstruction: (SaraInstruction) -> Unit,
    onSaveMemory: (SaraMemory) -> Unit,
    onSaveKnowledgeNote: (title: String, text: String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    Text("Teach Sara something new", color = JarvisCyan, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    Spacer(modifier = Modifier.height(8.dp))
    TeachField("Example: Reply in Hinglish and keep answers short.", text) { text = it }
    Spacer(modifier = Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallBtn("As Instruction") {
            if (text.isNotBlank()) {
                onSaveInstruction(
                    SaraInstruction(
                        id = java.util.UUID.randomUUID().toString(),
                        title = text.take(40),
                        text = text,
                        order = 0,
                        enabled = true,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                text = ""
            }
        }
        SmallBtn("As Memory") {
            if (text.isNotBlank()) {
                onSaveMemory(
                    SaraMemory(
                        id = java.util.UUID.randomUUID().toString(),
                        text = text,
                        category = MemoryCategory.GENERAL,
                        pinned = false,
                        enabled = true,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                text = ""
            }
        }
        SmallBtn("As Knowledge") {
            if (text.isNotBlank()) {
                onSaveKnowledgeNote("Note", text)
                text = ""
            }
        }
    }
}

@Composable
private fun InstructionsSection(
    items: List<SaraInstruction>,
    onSave: (SaraInstruction) -> Unit,
    onDelete: (String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    Text("Rules / Instructions", color = JarvisCyan, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    Spacer(modifier = Modifier.height(8.dp))
    TeachField("Title", title) { title = it }
    TeachField("Instruction text", body) { body = it }
    SmallBtn("Add instruction") {
        if (title.isNotBlank() && body.isNotBlank()) {
            onSave(
                SaraInstruction(
                    id = java.util.UUID.randomUUID().toString(),
                    title = title,
                    text = body,
                    order = items.size,
                    enabled = true,
                    updatedAt = System.currentTimeMillis()
                )
            )
            title = ""
            body = ""
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
    items.forEach { ins ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .border(1.dp, JarvisMuted.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(ins.title, color = JarvisCyan, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Text(ins.text, color = JarvisOnSurface, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
            TextButton(onClick = { onDelete(ins.id) }) {
                Text("DEL", color = Color(0xFFFF6B6B), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun MemorySection(
    items: List<SaraMemory>,
    memoryEnabled: Boolean,
    onSave: (SaraMemory) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    var text by remember { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Memory", color = JarvisCyan, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (memoryEnabled) "ON" else "OFF", color = JarvisMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Switch(checked = memoryEnabled, onCheckedChange = onToggle)
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    TeachField("New memory", text) { text = it }
    SmallBtn("Add memory") {
        if (text.isNotBlank()) {
            onSave(
                SaraMemory(
                    id = java.util.UUID.randomUUID().toString(),
                    text = text,
                    category = MemoryCategory.GENERAL,
                    pinned = false,
                    enabled = true,
                    updatedAt = System.currentTimeMillis()
                )
            )
            text = ""
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    SmallBtn("Delete All Memory") { showClearConfirm = true }
    if (showClearConfirm) {
        Dialog(onDismissRequest = { showClearConfirm = false }) {
            Column(
                modifier = Modifier
                    .background(JarvisSurface, RoundedCornerShape(8.dp))
                    .padding(16.dp)
            ) {
                Text("Delete ALL memories?", color = JarvisOnSurface, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallBtn("Confirm") { onClear(); showClearConfirm = false }
                    SmallBtn("Cancel") { showClearConfirm = false }
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
    items.forEach { m ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .border(1.dp, JarvisMuted.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                (if (m.pinned) "📌 " else "") + m.text,
                color = JarvisOnSurface,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f)
            )
            Row {
                TextButton(onClick = { onSave(m.copy(pinned = !m.pinned)) }) {
                    Text(if (m.pinned) "UNPIN" else "PIN", color = JarvisCyan, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
                TextButton(onClick = { onDelete(m.id) }) {
                    Text("DEL", color = Color(0xFFFF6B6B), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun KnowledgeSection(
    items: List<KnowledgeItem>,
    onAddNote: (title: String, text: String) -> Unit,
    onAddUrl: (title: String, url: String) -> Unit,
    onDelete: (String) -> Unit,
    onReindex: (String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    Text("Knowledge", color = JarvisCyan, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    Spacer(modifier = Modifier.height(8.dp))
    TeachField("Title", title) { title = it }
    TeachField("Note text", body) { body = it }
    SmallBtn("Add note") {
        if (title.isNotBlank() && body.isNotBlank()) {
            onAddNote(title, body)
            title = ""
            body = ""
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    TeachField("URL", url) { url = it }
    SmallBtn("Add URL") {
        if (url.isNotBlank()) {
            onAddUrl(title.ifBlank { url }, url)
            url = ""
            title = ""
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
    items.forEach { k ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .border(1.dp, JarvisMuted.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(k.title, color = JarvisCyan, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Text("${k.type} · ${k.indexState}", color = JarvisMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
            Row {
                TextButton(onClick = { onReindex(k.id) }) {
                    Text("REIDX", color = JarvisCyan, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
                TextButton(onClick = { onDelete(k.id) }) {
                    Text("DEL", color = Color(0xFFFF6B6B), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    personality: PersonalitySettings,
    onSavePersonality: (PersonalitySettings) -> Unit,
    onExport: () -> Unit
) {
    var name by remember { mutableStateOf(personality.name) }
    var style by remember { mutableStateOf(personality.style) }
    Text("Settings", color = JarvisCyan, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    Spacer(modifier = Modifier.height(8.dp))
    TeachField("Name", name) { name = it }
    TeachField("Style", style) { style = it }
    SmallBtn("Save personality") {
        onSavePersonality(personality.copy(name = name, style = style))
    }
    Spacer(modifier = Modifier.height(12.dp))
    SmallBtn("Export backup JSON") { onExport() }
}

@Composable
private fun SmallBtn(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = JarvisSurface, contentColor = JarvisCyan),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

@Composable
private fun TeachField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = JarvisCyan,
            unfocusedBorderColor = JarvisMuted.copy(alpha = 0.4f),
            focusedLabelColor = JarvisCyan,
            unfocusedLabelColor = JarvisMuted,
            cursorColor = JarvisCyan,
            focusedTextColor = JarvisOnSurface,
            unfocusedTextColor = JarvisOnSurface
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    )
}
