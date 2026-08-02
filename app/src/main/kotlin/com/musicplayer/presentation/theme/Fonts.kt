package com.musicplayer.presentation.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.musicplayer.R

private val SpaceGroteskFamily = FontFamily(Font(R.font.space_grotesk_regular))
private val InterFamily = FontFamily(Font(R.font.inter_regular))
private val PoppinsFamily = FontFamily(
    Font(R.font.poppins_regular),
    Font(R.font.poppins_bold, FontWeight.Bold)
)
private val RokkittFamily = FontFamily(Font(R.font.rokkitt_regular))
private val MarcellusFamily = FontFamily(Font(R.font.marcellus_regular))
private val CormorantGaramondFamily = FontFamily(Font(R.font.cormorant_garamond_regular))
private val CinzelFamily = FontFamily(Font(R.font.cinzel_regular))
private val EbGaramondFamily = FontFamily(Font(R.font.eb_garamond_regular))
private val MaShanZhengFamily = FontFamily(Font(R.font.ma_shan_zheng_regular))
private val NotoSerifFamily = FontFamily(Font(R.font.noto_serif_regular))
private val OrbitronFamily = FontFamily(Font(R.font.orbitron_regular))
private val RajdhaniFamily = FontFamily(
    Font(R.font.rajdhani_regular),
    Font(R.font.rajdhani_bold, FontWeight.Bold)
)

/** (display family, body family) for the given theme. */
fun fontsFor(theme: AppTheme): Pair<FontFamily, FontFamily> = when (theme) {
    AppTheme.DARK -> SpaceGroteskFamily to InterFamily
    AppTheme.LIGHT -> PoppinsFamily to InterFamily
    AppTheme.EGYPT -> RokkittFamily to InterFamily
    AppTheme.GREECE -> MarcellusFamily to CormorantGaramondFamily
    AppTheme.ROME -> CinzelFamily to EbGaramondFamily
    AppTheme.CHINA -> MaShanZhengFamily to NotoSerifFamily
    AppTheme.SPACE_GOTH -> OrbitronFamily to RajdhaniFamily
}

/**
 * Builds a [Typography] for [theme]: display/headline/titleLarge styles use the
 * theme's decorative display font, everything else (titleMedium and smaller,
 * body*, label*) uses the theme's readable body font.
 */
fun typographyFor(theme: AppTheme): Typography {
    val (display, body) = fontsFor(theme)
    val base = Typography()
    return base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = display),
        displayMedium = base.displayMedium.copy(fontFamily = display),
        displaySmall = base.displaySmall.copy(fontFamily = display),
        headlineLarge = base.headlineLarge.copy(fontFamily = display),
        headlineMedium = base.headlineMedium.copy(fontFamily = display),
        headlineSmall = base.headlineSmall.copy(fontFamily = display),
        titleLarge = base.titleLarge.copy(fontFamily = display),
        titleMedium = base.titleMedium.copy(fontFamily = body),
        titleSmall = base.titleSmall.copy(fontFamily = body),
        bodyLarge = base.bodyLarge.copy(fontFamily = body),
        bodyMedium = base.bodyMedium.copy(fontFamily = body),
        bodySmall = base.bodySmall.copy(fontFamily = body),
        labelLarge = base.labelLarge.copy(fontFamily = body),
        labelMedium = base.labelMedium.copy(fontFamily = body),
        labelSmall = base.labelSmall.copy(fontFamily = body)
    )
}
