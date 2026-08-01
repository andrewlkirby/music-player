package com.musicplayer.presentation.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF9DC8FF),
    onPrimary = Color(0xFF00325A),
    primaryContainer = Color(0xFF004880),
    onPrimaryContainer = Color(0xFFD2E4FF),
    secondary = Color(0xFFB5C8E4),
    onSecondary = Color(0xFF1F3145),
    secondaryContainer = Color(0xFF36485C),
    onSecondaryContainer = Color(0xFFD1E4FF),
    tertiary = Color(0xFFD0BCFF),
    onTertiary = Color(0xFF381E72),
    tertiaryContainer = Color(0xFF4F378B),
    onTertiaryContainer = Color(0xFFEADDFF),
    background = Color(0xFF0F1318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF0F1318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF1E2430),
    onSurfaceVariant = Color(0xFFC3C6CF),
    outline = Color(0xFF8D9199)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF005EA8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD2E4FF),
    onPrimaryContainer = Color(0xFF001B3A),
    secondary = Color(0xFF4E6179),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1E4FF),
    onSecondaryContainer = Color(0xFF0A1E30),
    tertiary = Color(0xFF6B53AE),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEADDFF),
    onTertiaryContainer = Color(0xFF22005D),
    background = Color(0xFFF8F9FF),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8F9FF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFDEE3EC),
    onSurfaceVariant = Color(0xFF42474F),
    outline = Color(0xFF72777F)
)

@Composable
fun MusicPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
