package com.example.timetableapp.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// true black/grey/white dark scheme; only course cards keep the palette
val MonoBackground = Color(0xFF000000)
val MonoSurface = Color(0xFF111414)
val MonoCard = Color(0xFF1B1E1E)
val MonoEmerald = Color(0xFFFFFFFF)
val MonoLime = Color(0xFFB9BDBD)
val MonoTeal = Color(0xFFD7DBDB)
val MonoText = Color(0xFFF2F4F4)
val MonoMuted = Color(0xFF9AA3A3)

private val MonoScheme = darkColorScheme(
    primary = MonoEmerald,
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF2A2E2E),
    onPrimaryContainer = Color(0xFFE2E6E6),
    secondary = MonoTeal,
    onSecondary = Color(0xFF1B1E1E),
    secondaryContainer = Color(0xFF303434),
    onSecondaryContainer = Color(0xFFE2E6E6),
    tertiary = MonoLime,
    background = MonoBackground,
    onBackground = MonoText,
    surface = MonoSurface,
    onSurface = MonoText,
    surfaceVariant = MonoCard,
    onSurfaceVariant = MonoMuted,
    outline = Color(0xFF3A3F3F)
)

@Composable
fun TimetableTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = MonoBackground.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }
    MaterialTheme(colorScheme = MonoScheme, content = content)
}
