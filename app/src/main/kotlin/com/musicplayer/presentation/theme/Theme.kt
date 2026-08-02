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
    content: @Composable () -> Unit
) {
    val baseScheme = colorSchemeFor(appTheme)
    // When a custom background image is active, punch a transparent hole in
    // `background` so every screen's default Scaffold container reveals the
    // image; surfaces (app bars, nav bar, cards) stay opaque for legibility.
    val colorScheme = if (backgroundPath != null) {
        baseScheme.copy(background = Color.Transparent)
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
