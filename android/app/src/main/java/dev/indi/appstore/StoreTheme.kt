package dev.indi.appstore

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

internal val StoreLightColors = lightColorScheme(
    primary = Color(0xFF0959E8), onPrimary = Color.White,
    primaryContainer = Color(0xFFE7F0FF), onPrimaryContainer = Color(0xFF123875),
    secondary = Color(0xFF425E91), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE9FF), onSecondaryContainer = Color(0xFF123875),
    background = Color(0xFFF6F9FF), onBackground = Color(0xFF14213D),
    surface = Color.White, onSurface = Color(0xFF14213D),
    surfaceVariant = Color(0xFFEDF3FD), onSurfaceVariant = Color(0xFF536580),
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFEDF3FD),
    outline = Color(0xFF71839F), outlineVariant = Color(0xFFD8E3F4),
    surfaceTint = Color(0xFF0959E8)
)

internal val StoreDarkColors = darkColorScheme(
    primary = Color(0xFF7DF4D0), onPrimary = Color(0xFF00382B),
    primaryContainer = Color(0xFF173E35), onPrimaryContainer = Color(0xFFB5FCE4),
    secondary = Color(0xFF9DCDBF), onSecondary = Color(0xFF10382D),
    secondaryContainer = Color(0xFF254A40), onSecondaryContainer = Color(0xFFB5FCE4),
    background = Color(0xFF101719), onBackground = Color(0xFFEDF4F3),
    surface = Color(0xFF192326), onSurface = Color(0xFFEDF4F3),
    surfaceVariant = Color(0xFF243236), onSurfaceVariant = Color(0xFFAFBFBE),
    surfaceContainer = Color(0xFF192326), surfaceContainerHigh = Color(0xFF243236),
    outline = Color(0xFF839B96), outlineVariant = Color(0xFF344746),
    surfaceTint = Color(0xFF7DF4D0)
)
