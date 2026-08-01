package com.musicplayer.presentation.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.musicplayer.worker.SdCardScanWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// DataStore for persisting picked folder URIs
val Context.settingsDataStore by preferencesDataStore("settings")
val KEY_WATCHED_URIS = stringSetPreferencesKey("watched_folder_uris")

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    val watchedFolders: StateFlow<List<Uri>> = context.settingsDataStore.data
        .map { prefs ->
            prefs[KEY_WATCHED_URIS]
                ?.map { Uri.parse(it) }
                ?: emptyList()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addFolder(uri: Uri) {
        viewModelScope.launch {
            // Persist permission so it survives reboots
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            // Save to DataStore
            context.settingsDataStore.edit { prefs ->
                val current = prefs[KEY_WATCHED_URIS] ?: emptySet()
                prefs[KEY_WATCHED_URIS] = current + uri.toString()
            }
            // Trigger scan
            android.util.Log.d("SettingsViewModel", "Folder added, triggering scan for: $uri")
            SdCardScanWorker.enqueue(WorkManager.getInstance(context), uri)
        }
    }

    fun removeFolder(uri: Uri) {
        viewModelScope.launch {
            android.util.Log.d("SettingsViewModel", "Removing folder: $uri")
            // Release persisted permission
            try {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            context.settingsDataStore.edit { prefs ->
                val current = prefs[KEY_WATCHED_URIS] ?: emptySet()
                prefs[KEY_WATCHED_URIS] = current - uri.toString()
            }
        }
    }

    fun rescanAll() {
        viewModelScope.launch {
            android.util.Log.d("SettingsViewModel", "Rescanning all folders: ${watchedFolders.value}")
            watchedFolders.value.forEach { uri ->
                SdCardScanWorker.enqueue(WorkManager.getInstance(context), uri)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val folders by viewModel.watchedFolders.collectAsState()
    var scanning by remember { mutableStateOf(false) }
    var showConfirmRemove by remember { mutableStateOf<Uri?>(null) }

    // SAF folder picker launcher
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.addFolder(uri)
            scanning = true
        }
    }

    // Confirm-remove dialog
    showConfirmRemove?.let { uri ->
        AlertDialog(
            onDismissRequest = { showConfirmRemove = null },
            title = { Text("Remove folder?") },
            text = {
                Text(
                    "Songs from this folder will no longer appear in your library.\n\n" +
                    friendlyUri(uri)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeFolder(uri)
                    showConfirmRemove = null
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmRemove = null }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {

            // ── Section: Music folders ──────────────────────────────────
            item {
                Text(
                    "Music Folders",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Add folders from your SD card or internal storage. " +
                            "The app will scan them for music files automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { folderPickerLauncher.launch(null) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.CreateNewFolder, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Add Folder")
                        }
                    }
                }
            }

            if (folders.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.FolderOff,
                                null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "No folders added yet",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "Tap \"Add Folder\" to scan your SD card",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            } else {
                items(folders, key = { it.toString() }) { uri ->
                    ListItem(
                        headlineContent = {
                            Text(
                                friendlyUri(uri),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Default.Folder,
                                null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = { showConfirmRemove = uri }) {
                                Icon(
                                    Icons.Default.RemoveCircleOutline,
                                    "Remove",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    )
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }

                item {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            viewModel.rescanAll()
                            scanning = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Icon(Icons.Default.Refresh, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Rescan All Folders")
                    }
                }
            }

            // ── Scan status ─────────────────────────────────────────────
            if (scanning) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    "Scanning in background…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    "Your songs will appear in the library shortly.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
            }

            // ── Section: About ──────────────────────────────────────────
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    "About",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Music Player") },
                    supportingContent = { Text("Version 1.0.0") },
                    leadingContent = {
                        Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
                    }
                )
            }
        }
    }
}

/** Converts a SAF tree URI into a human-readable path like "SD Card / Music / Rock" */
private fun friendlyUri(uri: Uri): String {
    return try {
        val path = uri.lastPathSegment ?: return uri.toString()
        // SAF paths look like "primary:Music/Rock" or "1234-5678:Music"
        val parts = path.split(":")
        val volume = when (parts.firstOrNull()?.lowercase()) {
            "primary" -> "Internal Storage"
            else -> "SD Card (${parts.firstOrNull()})"
        }
        val subPath = parts.getOrNull(1)?.replace("/", " / ") ?: ""
        if (subPath.isBlank()) volume else "$volume / $subPath"
    } catch (_: Exception) {
        uri.toString()
    }
}
