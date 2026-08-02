package com.musicplayer.presentation.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

enum class AppTheme(val displayName: String, val isDark: Boolean) {
    DARK("Dark", true),
    LIGHT("Light", false),
    EGYPT("Ancient Egypt", true),
    GREECE("Ancient Greece", false),
    ROME("Ancient Rome", false),
    CHINA("Ancient China", true),
    SPACE_GOTH("Space Goth", true)
}

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

// Gold + lapis lazuli on sandstone/desert-night surfaces.
private val EgyptColorScheme = darkColorScheme(
    primary = Color(0xFFD4AF37),
    onPrimary = Color(0xFF3A2E00),
    primaryContainer = Color(0xFF5C4900),
    onPrimaryContainer = Color(0xFFFFE08A),
    secondary = Color(0xFF6FA8DC),
    onSecondary = Color(0xFF00304D),
    secondaryContainer = Color(0xFF0B4A75),
    onSecondaryContainer = Color(0xFFCDE6FF),
    tertiary = Color(0xFFC9A876),
    onTertiary = Color(0xFF3E2E0F),
    tertiaryContainer = Color(0xFF56421C),
    onTertiaryContainer = Color(0xFFEBD9B4),
    background = Color(0xFF1A140C),
    onBackground = Color(0xFFEDE0C8),
    surface = Color(0xFF241C10),
    onSurface = Color(0xFFEDE0C8),
    surfaceVariant = Color(0xFF352A18),
    onSurfaceVariant = Color(0xFFD8C7A3),
    outline = Color(0xFF9C8A62)
)

// Aegean blue + terracotta on white marble.
private val GreeceColorScheme = lightColorScheme(
    primary = Color(0xFF0F52BA),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E2FF),
    onPrimaryContainer = Color(0xFF001943),
    secondary = Color(0xFFC1642D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBC7),
    onSecondaryContainer = Color(0xFF3B1300),
    tertiary = Color(0xFF6E7B58),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD9E4C0),
    onTertiaryContainer = Color(0xFF212B0F),
    background = Color(0xFFFBFAF6),
    onBackground = Color(0xFF1C1B18),
    surface = Color(0xFFF3F1EA),
    onSurface = Color(0xFF1C1B18),
    surfaceVariant = Color(0xFFE6E1D3),
    onSurfaceVariant = Color(0xFF48453A),
    outline = Color(0xFF79766A)
)

// Imperial purple + deep red on cream marble.
private val RomeColorScheme = lightColorScheme(
    primary = Color(0xFF7B241C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD4),
    onPrimaryContainer = Color(0xFF410E08),
    secondary = Color(0xFF6B3FA0),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEADDFF),
    onSecondaryContainer = Color(0xFF250A47),
    tertiary = Color(0xFFB8860B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE49A),
    onTertiaryContainer = Color(0xFF3E2E00),
    background = Color(0xFFFBF4EC),
    onBackground = Color(0xFF211A15),
    surface = Color(0xFFF2E9DE),
    onSurface = Color(0xFF211A15),
    surfaceVariant = Color(0xFFE5D7C6),
    onSurfaceVariant = Color(0xFF4C4338),
    outline = Color(0xFF7E7266)
)

// Lacquer red + gold on near-black, jade accent.
private val ChinaColorScheme = darkColorScheme(
    primary = Color(0xFFE0304A),
    onPrimary = Color(0xFF3F0011),
    primaryContainer = Color(0xFF5F0F22),
    onPrimaryContainer = Color(0xFFFFD9DD),
    secondary = Color(0xFFE3C36B),
    onSecondary = Color(0xFF3B2E00),
    secondaryContainer = Color(0xFF554300),
    onSecondaryContainer = Color(0xFFFFE9A8),
    tertiary = Color(0xFF5FB88C),
    onTertiary = Color(0xFF00391F),
    tertiaryContainer = Color(0xFF00522F),
    onTertiaryContainer = Color(0xFF8DF3C1),
    background = Color(0xFF120D0D),
    onBackground = Color(0xFFEAE0DD),
    surface = Color(0xFF1B1414),
    onSurface = Color(0xFFEAE0DD),
    surfaceVariant = Color(0xFF2B1E1E),
    onSurfaceVariant = Color(0xFFD6C3C1),
    outline = Color(0xFF9A8583)
)

// Void black, neon violet/magenta primary, electric cyan accent, chrome surfaces.
private val SpaceGothColorScheme = darkColorScheme(
    primary = Color(0xFFC77DFF),
    onPrimary = Color(0xFF32004D),
    primaryContainer = Color(0xFF4A0070),
    onPrimaryContainer = Color(0xFFEBCEFF),
    secondary = Color(0xFFFF4FCB),
    onSecondary = Color(0xFF4C0035),
    secondaryContainer = Color(0xFF6C0050),
    onSecondaryContainer = Color(0xFFFFD8F0),
    tertiary = Color(0xFF4DE8F0),
    onTertiary = Color(0xFF00363A),
    tertiaryContainer = Color(0xFF004F54),
    onTertiaryContainer = Color(0xFFA6F5FA),
    background = Color(0xFF08070B),
    onBackground = Color(0xFFE5E1E9),
    surface = Color(0xFF121016),
    onSurface = Color(0xFFE5E1E9),
    surfaceVariant = Color(0xFF201C26),
    onSurfaceVariant = Color(0xFFC9C3D1),
    outline = Color(0xFF8A8393)
)

fun colorSchemeFor(theme: AppTheme): ColorScheme = when (theme) {
    AppTheme.DARK -> DarkColorScheme
    AppTheme.LIGHT -> LightColorScheme
    AppTheme.EGYPT -> EgyptColorScheme
    AppTheme.GREECE -> GreeceColorScheme
    AppTheme.ROME -> RomeColorScheme
    AppTheme.CHINA -> ChinaColorScheme
    AppTheme.SPACE_GOTH -> SpaceGothColorScheme
}

enum class IconStyle { FILLED, OUTLINED, ROUNDED, SHARP }

fun iconStyleFor(theme: AppTheme): IconStyle = when (theme) {
    AppTheme.DARK -> IconStyle.FILLED
    AppTheme.LIGHT -> IconStyle.OUTLINED
    AppTheme.EGYPT -> IconStyle.SHARP
    AppTheme.GREECE -> IconStyle.OUTLINED
    AppTheme.ROME -> IconStyle.FILLED
    AppTheme.CHINA -> IconStyle.ROUNDED
    AppTheme.SPACE_GOTH -> IconStyle.SHARP
}
