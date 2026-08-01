package com.musicplayer.presentation.settings

import android.app.DownloadManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
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
import com.musicplayer.BuildConfig
import com.musicplayer.worker.SdCardScanWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

private const val UPDATE_REPO = "andrewlkirby/music-player"

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data object UpToDate : UpdateState()
    data class Available(val version: String, val downloadUrl: String) : UpdateState()
    data class Downloading(val progressPercent: Int) : UpdateState()
    data class ReadyToInstall(val uri: Uri) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

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

    // ── Self-update ────────────────────────────────────────────────────

    val currentVersion: String = BuildConfig.VERSION_NAME

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    fun checkForUpdate() {
        viewModelScope.launch {
            _updateState.value = UpdateState.Checking
            try {
                val (latestVersion, downloadUrl) = withContext(Dispatchers.IO) { fetchLatestRelease() }
                _updateState.value = when {
                    downloadUrl == null -> UpdateState.Error("Latest release has no APK attached")
                    isNewer(latestVersion, currentVersion) -> UpdateState.Available(latestVersion, downloadUrl)
                    else -> UpdateState.UpToDate
                }
            } catch (e: Exception) {
                _updateState.value = UpdateState.Error(e.message ?: "Update check failed")
            }
        }
    }

    fun downloadUpdate(downloadUrl: String, version: String) {
        viewModelScope.launch {
            _updateState.value = UpdateState.Downloading(0)
            try {
                val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val request = DownloadManager.Request(Uri.parse(downloadUrl))
                    .setTitle("Music Player update")
                    .setDescription("Downloading v$version")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "music-player-update.apk")
                    .setMimeType("application/vnd.android.package-archive")
                val downloadId = withContext(Dispatchers.IO) { downloadManager.enqueue(request) }

                var downloading = true
                while (downloading) {
                    val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
                    cursor.use {
                        if (it.moveToFirst()) {
                            when (it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                                DownloadManager.STATUS_SUCCESSFUL -> {
                                    downloading = false
                                    val uri = downloadManager.getUriForDownloadedFile(downloadId)
                                    _updateState.value = UpdateState.ReadyToInstall(uri)
                                }
                                DownloadManager.STATUS_FAILED -> {
                                    downloading = false
                                    _updateState.value = UpdateState.Error("Download failed")
                                }
                                else -> {
                                    val bytes = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                                    val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                                    val pct = if (total > 0) ((bytes * 100) / total).toInt() else 0
                                    _updateState.value = UpdateState.Downloading(pct)
                                }
                            }
                        }
                    }
                    if (downloading) delay(400)
                }
            } catch (e: Exception) {
                _updateState.value = UpdateState.Error(e.message ?: "Download failed")
            }
        }
    }

    fun installUpdate(uri: Uri) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settingsIntent)
            return
        }
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(installIntent)
    }

    /** Returns (versionWithoutV, apkDownloadUrlOrNull) for the latest GitHub release. */
    private fun fetchLatestRelease(): Pair<String, String?> {
        val url = URL("https://api.github.com/repos/$UPDATE_REPO/releases/latest")
        val connection = url.openConnection() as HttpURLConnection
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        try {
            if (connection.responseCode != 200) {
                throw Exception("GitHub returned HTTP ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.getString("tag_name").removePrefix("v")
            val assets = json.getJSONArray("assets")
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").endsWith(".apk")) {
                    apkUrl = asset.getString("browser_download_url")
                    break
                }
            }
            return tag to apkUrl
        } finally {
            connection.disconnect()
        }
    }

    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val l = local.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val folders by viewModel.watchedFolders.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    var scanning by remember { mutableStateOf(false) }
    var showConfirmRemove by remember { mutableStateOf<Uri?>(null) }

    // Once the APK finishes downloading, immediately hand off to the installer
    LaunchedEffect(updateState) {
        (updateState as? UpdateState.ReadyToInstall)?.let { viewModel.installUpdate(it.uri) }
    }

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
                    supportingContent = { Text("Version ${viewModel.currentVersion}") },
                    leadingContent = {
                        Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
                    }
                )
            }

            // ── Update row, driven by updateState ────────────────────────
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    when (val state = updateState) {
                        is UpdateState.Idle -> {
                            OutlinedButton(
                                onClick = { viewModel.checkForUpdate() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.SystemUpdate, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Get Update")
                            }
                        }
                        is UpdateState.Checking -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp))
                                Text("Checking for updates…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        is UpdateState.UpToDate -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("You're on the latest version", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        is UpdateState.Available -> {
                            Button(
                                onClick = { viewModel.downloadUpdate(state.downloadUrl, state.version) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Download, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Update to v${state.version}")
                            }
                        }
                        is UpdateState.Downloading -> {
                            Column {
                                Text(
                                    "Downloading update… ${state.progressPercent}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { state.progressPercent / 100f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        is UpdateState.ReadyToInstall -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("Opening installer…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        "If nothing happens, allow \"Install unknown apps\" for this app, then tap Install below.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { viewModel.installUpdate(state.uri) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Install")
                            }
                        }
                        is UpdateState.Error -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.ErrorOutline,
                                    null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(state.message, color = MaterialTheme.colorScheme.error)
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { viewModel.checkForUpdate() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Retry")
                            }
                        }
                    }
                }
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
