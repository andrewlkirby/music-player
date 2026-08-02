package com.musicplayer.data.repository

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.musicplayer.presentation.settings.settingsDataStore
import com.musicplayer.presentation.theme.AppTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class ThemeUiState(
    val theme: AppTheme = AppTheme.DARK,
    val backgroundPath: String? = null,
    val surfaceOpacity: Float = 1f
)

private val KEY_THEME = stringPreferencesKey("theme")
private fun backgroundKey(theme: AppTheme) = stringPreferencesKey("bg_path_${theme.name}")
private fun opacityKey(theme: AppTheme) = floatPreferencesKey("surface_opacity_${theme.name}")

@Singleton
class ThemeRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    val state: Flow<ThemeUiState> = context.settingsDataStore.data.map { prefs ->
        val theme = prefs[KEY_THEME]?.let { name ->
            AppTheme.entries.find { it.name == name }
        } ?: AppTheme.DARK
        ThemeUiState(
            theme = theme,
            backgroundPath = prefs[backgroundKey(theme)],
            surfaceOpacity = prefs[opacityKey(theme)] ?: 1f
        )
    }

    suspend fun setTheme(theme: AppTheme) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_THEME] = theme.name
        }
    }

    suspend fun setSurfaceOpacity(theme: AppTheme, opacity: Float) {
        context.settingsDataStore.edit { prefs ->
            prefs[opacityKey(theme)] = opacity.coerceIn(0.2f, 1f)
        }
    }

    /**
     * Copies [uri]'s content into app-internal storage rather than persisting the
     * raw picker URI — the Photo Picker's read grant is not guaranteed to survive
     * process death/reboot, but a local file always will.
     */
    suspend fun setBackgroundImage(theme: AppTheme, uri: Uri?) {
        withContext(Dispatchers.IO) {
            val backgroundsDir = File(context.filesDir, "backgrounds").apply { mkdirs() }
            val file = File(backgroundsDir, "${theme.name}.jpg")
            if (uri != null) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                context.settingsDataStore.edit { prefs ->
                    prefs[backgroundKey(theme)] = file.absolutePath
                }
            } else {
                file.delete()
                context.settingsDataStore.edit { prefs ->
                    prefs.remove(backgroundKey(theme))
                }
            }
        }
    }
}
