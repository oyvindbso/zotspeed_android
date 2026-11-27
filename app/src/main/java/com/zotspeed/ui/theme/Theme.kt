package com.zotspeed.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.zotspeed.data.ThemeMode

val PivotRed = Color(0xFFE5484D)

private val Light = lightColorScheme(
    primary = Color(0xFF2B5D8C),
    secondary = Color(0xFF5B6770),
    background = Color(0xFFFDFDFC),
    surface = Color(0xFFFDFDFC),
    onBackground = Color(0xFF1A1C1E),
    onSurface = Color(0xFF1A1C1E),
)

private val Sepia = lightColorScheme(
    primary = Color(0xFF7A4E1F),
    secondary = Color(0xFF6E5B45),
    background = Color(0xFFF4ECD8),
    surface = Color(0xFFF4ECD8),
    surfaceVariant = Color(0xFFE8DCC0),
    surfaceContainer = Color(0xFFEDE3CA),
    surfaceContainerHigh = Color(0xFFE8DCC0),
    surfaceContainerHighest = Color(0xFFE2D5B6),
    onBackground = Color(0xFF3B2F22),
    onSurface = Color(0xFF3B2F22),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9CC7F2),
    secondary = Color(0xFFB7C3CC),
    background = Color(0xFF16181B),
    surface = Color(0xFF16181B),
    onBackground = Color(0xFFE3E3E3),
    onSurface = Color(0xFFE3E3E3),
)

/** True black: saves power on the AMOLED cover screen and looks seamless with the bezel. */
private val Black = darkColorScheme(
    primary = Color(0xFF9CC7F2),
    secondary = Color(0xFFB7C3CC),
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0C0C0C),
    surfaceContainer = Color(0xFF141414),
    surfaceContainerHigh = Color(0xFF1C1C1C),
    surfaceContainerHighest = Color(0xFF262626),
    onBackground = Color(0xFFDADADA),
    onSurface = Color(0xFFDADADA),
)

@Composable
fun schemeFor(mode: ThemeMode): ColorScheme = when (mode) {
    ThemeMode.SYSTEM -> if (isSystemInDarkTheme()) Dark else Light
    ThemeMode.LIGHT -> Light
    ThemeMode.SEPIA -> Sepia
    ThemeMode.DARK -> Dark
    ThemeMode.BLACK -> Black
}

fun isDark(mode: ThemeMode, systemDark: Boolean) = when (mode) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.DARK, ThemeMode.BLACK -> true
    else -> false
}

@Composable
fun ZotSpeedTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val scheme = schemeFor(mode)
    MaterialTheme(colorScheme = scheme) {
        // Root surface provides the content color for text and icons on every screen.
        Surface(color = scheme.background, contentColor = scheme.onBackground, content = content)
    }
}
