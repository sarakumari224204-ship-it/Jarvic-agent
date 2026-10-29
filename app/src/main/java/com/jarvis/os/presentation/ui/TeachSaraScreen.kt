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
import com.jarvis.os.domain.memory.TeachSnapshot
import java.util.UUID

private enum class TeachTab { TEACH, MEMORY, KNOWLEDGE, PERSONALITY, SETTINGS }

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
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TeachTab.entries.forEach { t ->
                val selected = tab == t
                Text(
                    text = t.name,
                    color = if (selected) Color.Black else JarvisCyan,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .background(if (selected) JarvisCyan else Color.Transparent, RoundedCornerShape(6.dp))
                        .border(1.dp, JarvisCyan.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .clickable { tab = t }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; onSearch(it) },
            label = { Text("Search", color = JarvisMuted) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = JarvisCyan,
                unfocusedBorderColor = JarvisMuted.copy(alpha = 0.4f),
                focusedTextColor = JarvisOnSurface,
                unfocusedTextColor = JarvisOnSurface
            )
        )
        if (searchHits.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            searchHits.take(10).forEach { hit ->
                Text(
                    "${hit.kind}: ${hit.preview}",
                    color = JarvisMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        when (tab) {
            TeachTab.TEACH -> TeachTabContent(onSaveInstruction, onSaveMemory, onSaveKnowledgeNote)
            TeachTab.MEMORY -> MemoryTabContent(snapshot, onDeleteMemory, onClearMemories)
            TeachTab.KNOWLEDGE -> KnowledgeTabContent(snapshot, onSaveKnowledgeNote, onSaveKnowledgeUrl, onImportDocument, onDeleteKnowledge, onReindex)
            TeachTab.PERSONALITY -> PersonalityTabContent(snapshot.personality, onSavePersonality)
            TeachTab.SETTINGS -> SettingsTabContent(snapshot.settings, onToggleMemoryEnabled, onExport)
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
    var mode by remember { mutableStateOf("instruction") }
    Text("Add instruction / memory / note", color = JarvisCyan, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    Spacer(modifier = Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("instruction", "memory", "note").forEach { m ->
            val sel = mode == m
            Text(
                m.uppercase(),
                color = if (sel) Color.Black else JarvisCyan,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .background(if (sel) JarvisCyan else Color.Transparent, RoundedCornerShape(4.dp))
                    .border(1.dp, JarvisCyan.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                    .clickable { mode = m }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth().height(120.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = JarvisCyan,
            unfocusedBorderColor = JarvisMuted.copy(alpha = 0.4f),
            focusedTextColor = JarvisOnSurface,
            unfocusedTextColor = JarvisOnSurface
        )
    )
    Spacer(modifier = Modifier.height(8.dp))
    Button(
        onClick = {
            val t = text.trim()
            if (t.isEmpty()) return@Button
            when (mode) {
                "instruction" -> onSaveInstruction(SaraInstruction(text = t))
                "memory" -> onSaveMemory(SaraMemory(text = t, category = MemoryCategory.OTHER))
                else -> onSaveKnowledgeNote("Note", t)
            }
            text = ""
        },
        colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = Color.Black),
        modifier = Modifier.fillMaxWidth()
    ) { Text("SAVE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
}

@Composable
private fun MemoryTabContent(
    snapshot: TeachSnapshot,
    onDeleteMemory: (String) -> Unit,
    onClearMemories: () -> Unit
) {
    Row(Modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Memories (${snapshot.memories.size})", color = JarvisCyan, fontFamily = FontFamily.Monospace)
        TextButton(onClick = onClearMemories) { Text("CLEAR ALL", color = Color(0xFFFF5252), fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
    }
    snapshot.memories.forEach { m ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).border(1.dp, JarvisMuted.copy(alpha = 0.3f), RoundedCornerShape(6.dp)).padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(m.text.take(80), color = JarvisOnSurface, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            TextButton(onClick = { onDeleteMemory(m.id) }) { Text("DEL", color = Color(0xFFFF5252), fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
        }
    }
    if (snapshot.instructions.isNotEmpty()) {
        Spacer(modifier = Modifier.height(12.dp))
        Text("Instructions (${snapshot.instructions.size})", color = JarvisCyan, fontFamily = FontFamily.Monospace)
        snapshot.instructions.forEach { i ->
            Text("• ${i.text.take(100)}", color = JarvisMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}

@Composable
private fun KnowledgeTabContent(
    snapshot: TeachSnapshot,
    onSaveKnowledgeNote: (String, String) -> Unit,
    onSaveKnowledgeUrl: (String, String) -> Unit,
    onImportDocument: () -> Unit,
    onDeleteKnowledge: (String) -> Unit,
    onReindex: (String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    Text("Knowledge (${snapshot.knowledge.size})", color = JarvisCyan, fontFamily = FontFamily.Monospace)
    Spacer(modifier = Modifier.height(8.dp))
    Button(onClick = onImportDocument, colors = ButtonDefaults.buttonColors(containerColor = JarvisSurface, contentColor = JarvisCyan), modifier = Modifier.fillMaxWidth()) {
        Text("IMPORT PDF / TXT", fontFamily = FontFamily.Monospace)
    }
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = JarvisCyan, unfocusedBorderColor = JarvisMuted.copy(alpha = 0.4f), focusedTextColor = JarvisOnSurface, unfocusedTextColor = JarvisOnSurface))
    Spacer(modifier = Modifier.height(4.dp))
    OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("Note text") }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = JarvisCyan, unfocusedBorderColor = JarvisMuted.copy(alpha = 0.4f), focusedTextColor = JarvisOnSurface, unfocusedTextColor = JarvisOnSurface))
    Spacer(modifier = Modifier.height(4.dp))
    Button(onClick = { onSaveKnowledgeNote(title, body); title = ""; body = "" }, colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = Color.Black), modifier = Modifier.fillMaxWidth()) {
        Text("SAVE NOTE", fontFamily = FontFamily.Monospace)
    }
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL") }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = JarvisCyan, unfocusedBorderColor = JarvisMuted.copy(alpha = 0.4f), focusedTextColor = JarvisOnSurface, unfocusedTextColor = JarvisOnSurface))
    Spacer(modifier = Modifier.height(4.dp))
    Button(onClick = { onSaveKnowledgeUrl(title.ifBlank { url.take(40) }, url); url = "" }, colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = Color.Black), modifier = Modifier.fillMaxWidth()) {
        Text("SAVE URL", fontFamily = FontFamily.Monospace)
    }
    Spacer(modifier = Modifier.height(12.dp))
    snapshot.knowledge.forEach { k ->
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).border(1.dp, JarvisMuted.copy(alpha = 0.3f), RoundedCornerShape(6.dp)).padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text(k.title, color = JarvisOnSurface, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Text("${k.type} · ${k.indexState}", color = JarvisMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            TextButton(onClick = { onReindex(k.id) }) { Text("REINDEX", color = JarvisCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
            TextButton(onClick = { onDeleteKnowledge(k.id) }) { Text("DEL", color = Color(0xFFFF5252), fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
        }
    }
}

@Composable
private fun PersonalityTabContent(personality: PersonalitySettings, onSave: (PersonalitySettings) -> Unit) {
    var name by remember { mutableStateOf(personality.name) }
    var style by remember { mutableStateOf(personality.style) }
    var language by remember { mutableStateOf(personality.language) }
    Text("Personality", color = JarvisCyan, fontFamily = FontFamily.Monospace)
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = JarvisCyan, unfocusedBorderColor = JarvisMuted.copy(alpha = 0.4f), focusedTextColor = JarvisOnSurface, unfocusedTextColor = JarvisOnSurface))
    Spacer(modifier = Modifier.height(4.dp))
    OutlinedTextField(value = style, onValueChange = { style = it }, label = { Text("Style") }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = JarvisCyan, unfocusedBorderColor = JarvisMuted.copy(alpha = 0.4f), focusedTextColor = JarvisOnSurface, unfocusedTextColor = JarvisOnSurface))
    Spacer(modifier = Modifier.height(4.dp))
    OutlinedTextField(value = language, onValueChange = { language = it }, label = { Text("Language") }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = JarvisCyan, unfocusedBorderColor = JarvisMuted.copy(alpha = 0.4f), focusedTextColor = JarvisOnSurface, unfocusedTextColor = JarvisOnSurface))
    Spacer(modifier = Modifier.height(8.dp))
    Button(onClick = { onSave(personality.copy(name = name, style = style, language = language)) }, colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = Color.Black), modifier = Modifier.fillMaxWidth()) {
        Text("SAVE PERSONALITY", fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SettingsTabContent(settings: MemorySettings, onToggle: (Boolean) -> Unit, onExport: () -> Unit) {
    Text("Settings", color = JarvisCyan, fontFamily = FontFamily.Monospace)
    Spacer(modifier = Modifier.height(8.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Memory enabled", color = JarvisOnSurface, fontFamily = FontFamily.Monospace)
        Switch(checked = settings.enabled, onCheckedChange = onToggle)
    }
    Spacer(modifier = Modifier.height(12.dp))
    Button(onClick = onExport, colors = ButtonDefaults.buttonColors(containerColor = JarvisSurface, contentColor = JarvisCyan), modifier = Modifier.fillMaxWidth()) {
        Text("EXPORT JSON TO CLIPBOARD", fontFamily = FontFamily.Monospace)
    }
}
