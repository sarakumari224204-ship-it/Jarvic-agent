package com.jarvis.os.presentation.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.jarvis.os.domain.agent.AgentState
import com.jarvis.os.domain.model.ApiDefaults
import com.jarvis.os.domain.model.ApiSettings
import com.jarvis.os.runtime.JarvisRuntime
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun JarvisHudScreen(
    settings: ApiSettings,
    onSaveGemini: (key: String, model: String) -> Unit,
    onSaveEleven: (key: String, voiceId: String, model: String) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onRequestPermissions: () -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    isAccessibilityEnabled: () -> Boolean
) {
    var showSetup by remember { mutableStateOf(!settings.hasGemini) }
    var showSettings by remember { mutableStateOf(false) }

    val agentStatus by JarvisRuntime.agentStatus.collectAsState()
    val isListening by JarvisRuntime.isListening.collectAsState()
    val partial by JarvisRuntime.partialSpeech.collectAsState()
    val serviceRunning by JarvisRuntime.serviceRunning.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JarvisBackground)
    ) {
        PulsingArcVisualizer(
            modifier = Modifier
                .size(280.dp)
                .align(Alignment.Center),
            active = isListening || agentStatus.state == AgentState.THINKING || agentStatus.state == AgentState.ACTING
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = "JARVIS OS",
                color = JarvisCyan,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 4.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            val statusLabel = when {
                !settings.hasGemini -> "SETUP REQUIRED"
                !serviceRunning -> "STANDBY"
                agentStatus.state == AgentState.THINKING -> "THINKING"
                agentStatus.state == AgentState.ACTING -> "ACTING"
                agentStatus.state == AgentState.SPEAKING -> "SPEAKING"
                agentStatus.state == AgentState.WAITING_USER -> "AWAITING REPLY"
                agentStatus.state == AgentState.FINISHED -> "DONE"
                agentStatus.state == AgentState.FAILED -> "FAILED"
                isListening -> "LISTENING"
                else -> "ONLINE"
            }

            Text(
                text = statusLabel,
                color = when (agentStatus.state) {
                    AgentState.FAILED -> Color(0xFFFF5252)
                    AgentState.FINISHED -> JarvisCyan
                    else -> if (settings.hasGemini) JarvisCyan else JarvisMuted
                },
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Live thought / speech / partial
            val liveText = when {
                partial.isNotBlank() -> "\"$partial\""
                !agentStatus.speech.isNullOrBlank() -> agentStatus.speech
                !agentStatus.lastThought.isNullOrBlank() -> agentStatus.lastThought
                agentStatus.objective != null -> agentStatus.objective
                else -> null
            }

            if (liveText != null) {
                Text(
                    text = liveText,
                    color = JarvisOnSurface.copy(alpha = 0.85f),
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }

            if (agentStatus.step > 0 && agentStatus.state != AgentState.IDLE) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "STEP ${agentStatus.step}/${agentStatus.maxSteps}",
                    color = JarvisMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                StatusChip("GEMINI", settings.hasGemini)
                StatusChip("VOICE", settings.hasElevenLabs)
                StatusChip("A11Y", isAccessibilityEnabled())
                StatusChip("MIC", serviceRunning)
            }

            HudButton(
                text = if (settings.hasGemini) "SETTINGS" else "CONFIGURE API KEYS",
                onClick = { if (settings.hasGemini) showSettings = true else showSetup = true }
            )

            Spacer(modifier = Modifier.height(12.dp))

            HudButton(
                text = "ENABLE ACCESSIBILITY",
                onClick = onOpenAccessibilitySettings
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (serviceRunning) {
                HudButton(
                    text = "STOP LISTENING",
                    onClick = onStopListening
                )
            } else {
                HudButton(
                    text = "START LISTENING",
                    onClick = {
                        onRequestPermissions()
                        onStartListening()
                    }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showSetup || showSettings) {
        SetupDialog(
            initial = settings,
            onDismiss = {
                showSetup = false
                showSettings = false
            },
            onSaveGemini = { key, model ->
                onSaveGemini(key, model)
                showSetup = false
                showSettings = false
            },
            onSaveEleven = onSaveEleven
        )
    }
}

@Composable
private fun StatusChip(label: String, ok: Boolean) {
    Text(
        text = "$label ${if (ok) "●" else "○"}",
        color = if (ok) JarvisCyan else JarvisMuted,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .border(1.dp, if (ok) JarvisCyan.copy(alpha = 0.5f) else JarvisMuted.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun HudButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = JarvisSurface,
            contentColor = JarvisCyan
        ),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .border(1.dp, JarvisCyan.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            letterSpacing = 1.sp
        )
    }
}

@Composable
private fun PulsingArcVisualizer(modifier: Modifier = Modifier, active: Boolean = true) {
    val transition = rememberInfiniteTransition(label = "arc")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (active) 4000 else 12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (active) 800 else 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val alphaMul = if (active) 1f else 0.45f

    Canvas(modifier = modifier) {
        val cx = size.width / 2
        val cy = size.height / 2
        val baseRadius = size.minDimension / 2.2f

        drawCircle(
            color = JarvisCyan.copy(alpha = 0.08f * pulse * alphaMul),
            radius = baseRadius,
            center = Offset(cx, cy),
            style = Stroke(width = 2f)
        )

        drawCircle(
            color = JarvisCyan.copy(alpha = 0.18f * pulse * alphaMul),
            radius = baseRadius * 0.72f,
            center = Offset(cx, cy),
            style = Stroke(width = 1.5f)
        )

        val sweep = 70f
        for (i in 0 until 3) {
            val start = rotation + i * 120f
            drawArc(
                color = JarvisCyan.copy(alpha = 0.55f * alphaMul),
                startAngle = start,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(cx - baseRadius * 0.9f, cy - baseRadius * 0.9f),
                size = androidx.compose.ui.geometry.Size(baseRadius * 1.8f, baseRadius * 1.8f),
                style = Stroke(width = 3f, cap = StrokeCap.Round)
            )
        }

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    JarvisCyan.copy(alpha = 0.35f * pulse * alphaMul),
                    JarvisCyan.copy(alpha = 0f)
                ),
                center = Offset(cx, cy),
                radius = baseRadius * 0.35f
            ),
            radius = baseRadius * 0.35f,
            center = Offset(cx, cy)
        )

        for (i in 0 until 6) {
            val angle = Math.toRadians((rotation * 1.5f + i * 60f).toDouble())
            val r = baseRadius * 0.55f
            val x = cx + (r * cos(angle)).toFloat()
            val y = cy + (r * sin(angle)).toFloat()
            drawCircle(
                color = JarvisCyan.copy(alpha = 0.8f * alphaMul),
                radius = 3f,
                center = Offset(x, y)
            )
        }
    }
}

@Composable
private fun SetupDialog(
    initial: ApiSettings,
    onDismiss: () -> Unit,
    onSaveGemini: (String, String) -> Unit,
    onSaveEleven: (String, String, String) -> Unit
) {
    var geminiKey by remember { mutableStateOf(initial.geminiKey.orEmpty()) }
    var geminiModel by remember { mutableStateOf(initial.geminiModel) }
    var elevenKey by remember { mutableStateOf(initial.elevenLabsKey.orEmpty()) }
    var elevenVoice by remember { mutableStateOf(initial.elevenLabsVoiceId.orEmpty()) }
    var elevenModel by remember { mutableStateOf(initial.elevenLabsModel) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(JarvisSurface, RoundedCornerShape(12.dp))
                .border(1.dp, JarvisCyan.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "SYSTEM CONFIG",
                color = JarvisCyan,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(16.dp))

            HudField("Gemini API Key", geminiKey) { geminiKey = it }
            Spacer(modifier = Modifier.height(8.dp))
            HudField("Gemini Model", geminiModel) { geminiModel = it }

            Spacer(modifier = Modifier.height(16.dp))
            Text("ElevenLabs (optional)", color = JarvisMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.height(8.dp))
            HudField("ElevenLabs API Key", elevenKey) { elevenKey = it }
            Spacer(modifier = Modifier.height(8.dp))
            HudField("Voice ID", elevenVoice) { elevenVoice = it }
            Spacer(modifier = Modifier.height(8.dp))
            HudField("ElevenLabs Model", elevenModel) { elevenModel = it }

            Spacer(modifier = Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("CANCEL", color = JarvisMuted, fontFamily = FontFamily.Monospace)
                }
                Button(
                    onClick = {
                        if (geminiKey.isNotBlank()) {
                            onSaveGemini(geminiKey, geminiModel.ifBlank { ApiDefaults.GEMINI_MODEL })
                        }
                        if (elevenKey.isNotBlank() && elevenVoice.isNotBlank()) {
                            onSaveEleven(
                                elevenKey,
                                elevenVoice,
                                elevenModel.ifBlank { ApiDefaults.ELEVENLABS_MODEL }
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = Color.Black)
                ) {
                    Text("SAVE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun HudField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = JarvisCyan,
            unfocusedBorderColor = JarvisMuted.copy(alpha = 0.4f),
            focusedLabelColor = JarvisCyan,
            unfocusedLabelColor = JarvisMuted,
            cursorColor = JarvisCyan,
            focusedTextColor = JarvisOnSurface,
            unfocusedTextColor = JarvisOnSurface
        ),
        modifier = Modifier.fillMaxWidth()
    )
}
