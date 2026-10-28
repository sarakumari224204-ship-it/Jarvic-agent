package com.jarvis.os.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

        Spacer(Modifier = Modifier.height(12.dp))
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
                    color = if (selected) Color.Black else JarvisCyan,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .background(if (selected) JarvisCyan else Color.Transparent, RoundedCornerShape(6.dp))
                        .border(1.dp, JarvisCyan.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                        .clickable { tab = t }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }

        Spacer(Modifier = Modifier.height(16.dp))

        when (tab) {
            TeachTab.TEACH -> TeachInputSection(
                onProposeInstruction = { title, text ->
                    onSaveInstruction(
                        SaraInstruction(
                            title = title,
                            text = text,
                            order = snapshot.instructions.size
                        )
                    )
                },
                onProposeMemory = { text ->
                    onSaveMemory(SaraMemory(text = text, category = MemoryCategory.PREFERENCE))
                },
                onProposeKnowledge = { title, text ->
                    onSaveKnowledgeNote(title, text)
                }
            )
            TeachTab.INSTRUCTIONS -> InstructionsSection(
                items = snapshot.instructions.sortedBy { it.order },
                onSave = onSaveInstruction,
                onDelete = onDeleteInstruction
            )
            TeachTab.MEMORY -> MemorySection(
                items = snapshot.memories,
                memoryEnabled = snapshot.settings.memoryEnabled,
                onToggleEnabled = onToggleMemoryEnabled,
                onSave = onSaveMemory,
                onDelete = onDeleteMemory,
                onClear = onClearMemories
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
                Spacer(Modifier = Modifier.height(8.dp))
                searchHits.forEach { hit ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .border(1.dp, JarvisMuted.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                            .padding(8.dp)
                    ) {
                        Text(
                            "${hit.type.uppercase()} · ${hit.title}",
                            color = JarvisCyan,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                        Text(hit.snippet, color = JarvisMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                if (searchHits.isEmpty() && query.isNotBlank()) {
                    Text("No results", color = JarvisMuted, fontFamily = FontFamily.Monospace)
                }
            }
            TeachTab.SETTINGS -> SettingsSection(
                personality = snapshot.personality,
                memoryEnabled = snapshot.settings.memoryEnabled,
                onToggleMemory = onToggleMemoryEnabled,
                onSavePersonality = onSavePersonality,
                onExport = onExport
            )
        }
    }
}

@Composable
private fun TeachInputSection(
    onProposeInstruction: (title: String, text: String) -> Unit,
    onProposeMemory: (text: String) -> Unit,
    onProposeKnowledge: (title: String, text: String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Pair<String, String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Text("Teach Sara something", color = JarvisCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    Spacer(Modifier = Modifier.height(8.dp))
    TeachField("Example: Reply in Hinglish and keep answers short.", text) { text = it }
    Spacer(Modifier = Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallBtn("As Instruction") {
            error = SensitiveContentFilter.blockReason(text)
            if (error == null && text.isNotBlank()) {
                preview = "instruction" to text
            }
        }
        SmallBtn("As Memory") {
            error = SensitiveContentFilter.blockReason(text)
            if (error == null && text.isNotBlank()) preview = "memory" to text
        }
        SmallBtn("As Knowledge") {
            if (text.isNotBlank()) preview = "knowledge" to text
        }
    }
    error?.let {
        Text(it, color = Color(0xFFFF5252), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
    preview?.let { (kind, body) ->
        Spacer(Modifier = Modifier.height(12.dp))
        Text("Preview ($kind)", color = JarvisMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Text(body, color = JarvisOnSurface, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        Row {
            TextButton(onClick = { preview = null }) {
                Text("CANCEL", color = JarvisMuted, fontFamily = FontFamily.Monospace)
            }
            Button(
                onClick = {
                    when (kind) {
                        "instruction" -> onProposeInstruction("Custom rule", body)
                        "memory" -> onProposeMemory(body)
                        "knowledge" -> onProposeKnowledge("Note", body)
                    }
                    text = ""
                    preview = null
                },
                colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = Color.Black)
            ) {
                Text("SAVE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
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

    TeachField("Title", title) { title = it }
    Spacer(Modifier = Modifier.height(6.dp))
    TeachField("Instruction text", body) { body = it }
    Spacer(Modifier = Modifier.height(8.dp))
    SmallBtn("Add instruction") {
        if (title.isNotBlank() && body.isNotBlank()) {
            onSave(SaraInstruction(title = title, text = body, order = items.size))
            title = ""
            body = ""
        }
    }
    Spacer(Modifier = Modifier.height(12.dp))
    items.forEach { item ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .border(1.dp, JarvisCyan.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                .padding(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(item.title, color = JarvisCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text(
                    if (item.enabled) "ON" else "OFF",
                    color = if (item.enabled) JarvisCyan else JarvisMuted,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clickable { onSave(item.copy(enabled = !item.enabled)) }
                )
            }
            Text(item.text, color = JarvisOnSurface, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            TextButton(onClick = { onDelete(item.id) }) {
                Text("DELETE", color = Color(0xFFFF5252), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun MemorySection(
    items: List<SaraMemory>,
    memoryEnabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onSave: (SaraMemory) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Memory enabled", color = JarvisOnSurface, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
        Switch(
            checked = memoryEnabled,
            onCheckedChange = onToggleEnabled,
            colors = SwitchDefaults.colors(checkedTrackColor = JarvisCyan)
        )
    }
    var text by remember { mutableStateOf("") }
    TeachField("New memory", text) { text = it }
    SmallBtn("Add memory") {
        if (text.isNotBlank()) {
            onSave(SaraMemory(text = text))
            text = ""
        }
    }
    TextButton(onClick = onClear) {
        Text("CLEAR ALL MEMORIES", color = Color(0xFFFF5252), fontFamily = FontFamily.Monospace)
    }
    items.forEach { m ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .border(1.dp, JarvisMuted.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                .padding(8.dp)
        ) {
            Text(
                (if (m.pinned) "📌 " else "") + m.text,
                color = JarvisOnSurface,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
            Row {
                TextButton(onClick = { onSave(m.copy(pinned = !m.pinned)) }) {
                    Text(if (m.pinned) "UNPIN" else "PIN", color = JarvisCyan, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
                TextButton(onClick = { onSave(m.copy(enabled = !m.enabled)) }) {
                    Text(if (m.enabled) "DISABLE" else "ENABLE", color = JarvisCyan, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
                TextButton(onClick = { onDelete(m.id) }) {
                    Text("DELETE", color = Color(0xFFFF5252), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun KnowledgeSection(
    items: List<KnowledgeItem>,
    onAddNote: (String, String) -> Unit,
    onAddUrl: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onReindex: (String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    TeachField("Title", title) { title = it }
    TeachField("Note text", body) { body = it }
    SmallBtn("Add text note") {
        if (title.isNotBlank() && body.isNotBlank()) {
            onAddNote(title, body)
            title = ""
            body = ""
        }
    }
    Spacer(Modifier = Modifier.height(8.dp))
    TeachField("Public URL", url) { url = it }
    SmallBtn("Add URL") {
        if (url.isNotBlank()) {
            onAddUrl(title.ifBlank { "Web page" }, url)
            url = ""
        }
    }
    Spacer(Modifier = Modifier.height(12.dp))
    items.forEach { k ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .border(1.dp, JarvisMuted.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                .padding(8.dp)
        ) {
            Text("${k.title} · ${k.type}", color = JarvisCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text(
                "Index: ${k.indexState}" + (k.indexError?.let { " — $it" } ?: ""),
                color = JarvisMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(k.source.take(80), color = JarvisMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Row {
                TextButton(onClick = { onReindex(k.id) }) {
                    Text("RE-INDEX", color = JarvisCyan, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
                TextButton(onClick = { onDelete(k.id) }) {
                    Text("DELETE", color = Color(0xFFFF5252), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    personality: PersonalitySettings,
    memoryEnabled: Boolean,
    onToggleMemory: (Boolean) -> Unit,
    onSavePersonality: (PersonalitySettings) -> Unit,
    onExport: () -> Unit
) {
    var name by remember { mutableStateOf(personality.name) }
    var style by remember { mutableStateOf(personality.style) }
    var language by remember { mutableStateOf(personality.language) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Use memory in answers", color = JarvisOnSurface, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
        Switch(checked = memoryEnabled, onCheckedChange = onToggleMemory, colors = SwitchDefaults.colors(checkedTrackColor = JarvisCyan))
    }
    TeachField("Name", name) { name = it }
    TeachField("Style", style) { style = it }
    TeachField("Language", language) { language = it }
    Spacer(Modifier = Modifier.height(8.dp))
    SmallBtn("Save personality") {
        onSavePersonality(personality.copy(name = name, style = style, language = language))
    }
    Spacer(Modifier = Modifier.height(8.dp))
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
