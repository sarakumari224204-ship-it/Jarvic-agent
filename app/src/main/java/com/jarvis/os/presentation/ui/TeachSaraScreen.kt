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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.jarvis.os.data.memory.MemoryStore
import com.jarvis.os.domain.memory.KnowledgeItem
import com.jarvis.os.domain.memory.MemoryCategory
import com.jarvis.os.domain.memory.MemorySettings
import com.jarvis.os.domain.memory.PersonalitySettings
import com.jarvis.os.domain.memory.SaraInstruction
import com.jarvis.os.domain.memory.SaraMemory
import com.jarvis.os.domain.memory.SensitiveContentFilter
import com.jarvis.os.domain.memory.TeachSnapshot

enum class TeachTab { INSTRUCTIONS, MEMORY, KNOWLEDGE, SEARCH, SETTINGS, TEACH }

// Reuse Theme.kt colors; local aliases only where needed for private scope
private val TeachDanger = Color(0xFFFF6B6B)

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
    onImportDocument: () -> Unit,
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
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
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
                        .border(
                            1.dp,
                            if (selected) JarvisCyan else JarvisMuted.copy(alpha = 0.3f),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (tab) {
            TeachTab.TEACH -> TeachTabContent(
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
                onSave = onSaveMemory,
                onDelete = onDeleteMemory,
                onClear = onClearMemories
            )
            TeachTab.KNOWLEDGE -> KnowledgeSection(
                items = snapshot.knowledge,
                onAddNote = onSaveKnowledgeNote,
                onAddUrl = onSaveKnowledgeUrl,
                onImportDocument = onImportDocument,
                onDelete = onDeleteKnowledge,
                onReindex = onReindex
            )
            TeachTab.SEARCH -> SearchSection(
                query = query,
                onQueryChange = { query = it },
                onSearch = onSearch,
                hits = searchHits
            )
            TeachTab.SETTINGS -> SettingsSection(
                settings = snapshot.settings,
                personality = snapshot.personality,
                onToggleMemory = onToggleMemoryEnabled,
                onSavePersonality = onSavePersonality,
                onExport = onExport
            )
        }
    }
}

@Composable
private fun TeachTabContent(
    onSaveInstruction: (SaraInstruction) -> Unit,
    onSaveMemory: (SaraMemory) -> Unit,
    onSaveKnowledgeNote: (title: String, text: String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    Text("Quick teach", color = JarvisCyan, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    Spacer(modifier = Modifier.height(8.dp))
    TeachField("What should Sara remember or follow?", text) { text = it }
    Spacer(modifier = Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallBtn("As Rule") {
            if (text.isNotBlank()) {
                onSaveInstruction(
                    SaraInstruction(
                        title = text.trim().take(40),
                        text = text.trim()
                    )
                )
                text = ""
            }
        }
        SmallBtn("As Memory") {
            if (text.isNotBlank()) {
                onSaveMemory(SaraMemory(text = text.trim(), category = MemoryCategory.OTHER))
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
    var draft by remember { mutableStateOf("") }
    Text("Rules / Instructions", color = JarvisCyan, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    Spacer(modifier = Modifier.height(8.dp))
    TeachField("New rule", draft) { draft = it }
    SmallBtn("Add Rule") {
        if (draft.isNotBlank()) {
            onSave(
                SaraInstruction(
                    title = draft.trim().take(40),
                    text = draft.trim()
                )
            )
            draft = ""
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
    items.forEach { item ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .background(JarvisSurface, RoundedCornerShape(6.dp))
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                item.text.take(120),
                color = JarvisOnSurface,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { onDelete(item.id) }) {
                Text("Del", color = TeachDanger, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun MemorySection(
    items: List<SaraMemory>,
    onSave: (SaraMemory) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit
) {
    var draft by remember { mutableStateOf("") }
    Text("Long-term Memory", color = JarvisCyan, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    Spacer(modifier = Modifier.height(8.dp))
    TeachField("New memory", draft) { draft = it }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallBtn("Save") {
            if (draft.isNotBlank() && !SensitiveContentFilter.isSensitive(draft)) {
                onSave(SaraMemory(text = draft.trim(), category = MemoryCategory.OTHER))
                draft = ""
            }
        }
        SmallBtn("Clear All") { onClear() }
    }
    Spacer(modifier = Modifier.height(12.dp))
    items.forEach { item ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .background(JarvisSurface, RoundedCornerShape(6.dp))
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.text.take(120), color = JarvisOnSurface, fontSize = 12.sp)
                Text(item.category.name, color = JarvisMuted, fontSize = 10.sp)
            }
            TextButton(onClick = { onDelete(item.id) }) {
                Text("Del", color = TeachDanger, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun KnowledgeSection(
    items: List<KnowledgeItem>,
    onAddNote: (title: String, text: String) -> Unit,
    onAddUrl: (title: String, url: String) -> Unit,
    onImportDocument: () -> Unit,
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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallBtn("Add Note") {
            if (body.isNotBlank()) {
                onAddNote(title.ifBlank { "Note" }, body)
                title = ""; body = ""
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    TeachField("URL", url) { url = it }
    SmallBtn("Add URL") {
        if (url.isNotBlank()) {
            onAddUrl(title.ifBlank { url.take(40) }, url)
            url = ""
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    SmallBtn("Import PDF / TXT (SAF)") { onImportDocument() }
    Text(
        "Max 8MB. Scanned/image PDF: OCR nahi hai — fail message milega.",
        color = JarvisMuted,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace
    )
    Spacer(modifier = Modifier.height(12.dp))
    items.forEach { item ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .background(JarvisSurface, RoundedCornerShape(6.dp))
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.title, color = JarvisOnSurface, fontSize = 12.sp)
                Text("${item.type} · ${item.indexState}", color = JarvisMuted, fontSize = 10.sp)
            }
            Row {
                TextButton(onClick = { onReindex(item.id) }) {
                    Text("Re", color = JarvisCyan, fontSize = 11.sp)
                }
                TextButton(onClick = { onDelete(item.id) }) {
                    Text("Del", color = TeachDanger, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun SearchSection(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    hits: List<MemoryStore.SearchHit>
) {
    Text("Search", color = JarvisCyan, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    Spacer(modifier = Modifier.height(8.dp))
    TeachField("Query", query, onQueryChange)
    SmallBtn("Search") { onSearch(query) }
    Spacer(modifier = Modifier.height(12.dp))
    hits.forEach { hit ->
        Text(
            "${hit.type}: ${hit.snippet.take(100)}",
            color = JarvisOnSurface,
            fontSize = 12.sp,
            modifier = Modifier.padding(vertical = 4.dp)
        )
    }
}

@Composable
private fun SettingsSection(
    settings: MemorySettings,
    personality: PersonalitySettings,
    onToggleMemory: (Boolean) -> Unit,
    onSavePersonality: (PersonalitySettings) -> Unit,
    onExport: () -> Unit
) {
    Text("Settings", color = JarvisCyan, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    Spacer(modifier = Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Memory enabled",
            color = JarvisOnSurface,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = settings.memoryEnabled, onCheckedChange = onToggleMemory)
    }
    Spacer(modifier = Modifier.height(8.dp))
    var style by remember { mutableStateOf(personality.style) }
    TeachField("Personality style", style) { style = it }
    SmallBtn("Save Personality") { onSavePersonality(personality.copy(style = style)) }
    Spacer(modifier = Modifier.height(8.dp))
    SmallBtn("Export JSON") { onExport() }
}

@Composable
private fun SmallBtn(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = JarvisSurface,
            contentColor = JarvisCyan
        ),
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
