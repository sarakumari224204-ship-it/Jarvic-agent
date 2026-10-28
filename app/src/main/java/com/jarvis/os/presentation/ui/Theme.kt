package com.jarvis.os.presentation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val JarvisBackground = Color(0xFF0B0E14)
val JarvisCyan = Color(0xFF00E5FF)
val JarvisCyanDim = Color(0xFF00A8B8)
val JarvisSurface = Color(0xFF12161F)
val JarvisOnSurface = Color(0xFFE6EDF3)
val JarvisMuted = Color(0xFF8B9BB4)

private val JarvisDarkColors = darkColorScheme(
    primary = JarvisCyan,
    onPrimary = Color.Black,
    secondary = JarvisCyanDim,
    background = JarvisBackground,
    surface = JarvisSurface,
    onSurface = JarvisOnSurface,
    onBackground = JarvisOnSurface
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JarvisDarkColors,
        content = content
    )
}
