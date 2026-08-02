package com.musicplayer.presentation.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Suppress("DEPRECATION") // statusBarColor has no replacement pre-API 35; still required for minSdk 26 support
@Composable
fun MusicPlayerTheme(
    appTheme: AppTheme = AppTheme.DARK,
    backgroundPath: String? = null,
    surfaceOpacity: Float = 1f,
    content: @Composable () -> Unit
) {
    val baseScheme = colorSchemeFor(appTheme)
    // When a custom background image is active, punch a transparent hole in
    // `background` so every screen's default Scaffold container reveals the
    // image, and fade every surface-family color (app bars, nav bar, cards,
    // dropdowns) by surfaceOpacity so the image shows through them too.
    val colorScheme = if (backgroundPath != null) {
        baseScheme.copy(
            background = Color.Transparent,
            surface = baseScheme.surface.copy(alpha = surfaceOpacity),
            surfaceVariant = baseScheme.surfaceVariant.copy(alpha = surfaceOpacity),
            surfaceContainer = baseScheme.surfaceContainer.copy(alpha = surfaceOpacity),
            surfaceContainerHigh = baseScheme.surfaceContainerHigh.copy(alpha = surfaceOpacity),
            surfaceContainerHighest = baseScheme.surfaceContainerHighest.copy(alpha = surfaceOpacity),
            surfaceContainerLow = baseScheme.surfaceContainerLow.copy(alpha = surfaceOpacity),
            surfaceContainerLowest = baseScheme.surfaceContainerLowest.copy(alpha = surfaceOpacity),
            surfaceBright = baseScheme.surfaceBright.copy(alpha = surfaceOpacity),
            surfaceDim = baseScheme.surfaceDim.copy(alpha = surfaceOpacity)
        )
    } else {
        baseScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !appTheme.isDark
        }
    }

    CompositionLocalProvider(LocalIconStyle provides iconStyleFor(appTheme)) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typographyFor(appTheme),
            content = content
        )
    }
}
