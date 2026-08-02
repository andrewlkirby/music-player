package com.musicplayer.presentation.settings

import android.app.DownloadManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import coil.compose.AsyncImage
import com.musicplayer.BuildConfig
import com.musicplayer.data.repository.ThemeRepository
import com.musicplayer.data.repository.ThemeUiState
import com.musicplayer.presentation.theme.AppIcons
import com.musicplayer.presentation.theme.AppTheme
import com.musicplayer.presentation.theme.colorSchemeFor
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

sealed class ScanStatus {
    data object Idle : ScanStatus()
    data class Scanning(val foldersScanned: Int, val songsFound: Int) : ScanStatus()
    data class Saving(val songsSaved: Int, val songsTotal: Int) : ScanStatus()
}

// DataStore for persisting picked folder URIs
val Context.settingsDataStore by preferencesDataStore("settings")
val KEY_WATCHED_URIS = stringSetPreferencesKey("watched_folder_uris")

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val themeRepository: ThemeRepository
) : ViewModel() {

    val themeState: StateFlow<ThemeUiState> = themeRepository.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeUiState())

    fun setTheme(theme: AppTheme) {
        viewModelScope.launch { themeRepository.setTheme(theme) }
    }

    fun setBackgroundImage(theme: AppTheme, uri: Uri?) {
        viewModelScope.launch { themeRepository.setBackgroundImage(theme, uri) }
    }

    fun setSurfaceOpacity(theme: AppTheme, opacity: Float) {
        viewModelScope.launch { themeRepository.setSurfaceOpacity(theme, opacity) }
    }

    fun setBackgroundPosition(theme: AppTheme, biasX: Float, biasY: Float) {
        viewModelScope.launch { themeRepository.setBackgroundPosition(theme, biasX, biasY) }
    }

    val watchedFolders: StateFlow<List<Uri>> = context.settingsDataStore.data
        .map { prefs ->
            prefs[KEY_WATCHED_URIS]
                ?.map { Uri.parse(it) }
                ?: emptyList()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val scanStatus: StateFlow<ScanStatus> = WorkManager.getInstance(context)
        .getWorkInfosByTagFlow(SdCardScanWorker.TAG)
        .map { infos -> aggregateScanStatus(infos) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ScanStatus.Idle)

    private fun aggregateScanStatus(infos: List<WorkInfo>): ScanStatus {
        val active = infos.filter { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        if (active.isEmpty()) return ScanStatus.Idle

        var foldersScanned = 0
        var songsFound = 0
        var songsSaved = 0
        var songsTotal = 0
        var anyScanning = false

        active.forEach { info ->
            if (info.progress.getString(SdCardScanWorker.KEY_PHASE) == SdCardScanWorker.PHASE_SAVING) {
                songsSaved += info.progress.getInt(SdCardScanWorker.KEY_SONGS_SAVED, 0)
                songsTotal += info.progress.getInt(SdCardScanWorker.KEY_SONGS_TOTAL, 0)
            } else {
                anyScanning = true
                foldersScanned += info.progress.getInt(SdCardScanWorker.KEY_FOLDERS_SCANNED, 0)
                songsFound += info.progress.getInt(SdCardScanWorker.KEY_SONGS_FOUND, 0)
            }
        }

        return if (anyScanning) {
            ScanStatus.Scanning(foldersScanned, songsFound)
        } else {
            ScanStatus.Saving(songsSaved, songsTotal)
        }
    }

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
    val scanStatus by viewModel.scanStatus.collectAsState()
    val themeState by viewModel.themeState.collectAsState()
    var showConfirmRemove by remember { mutableStateOf<Uri?>(null) }
    var showPositionPicker by remember { mutableStateOf(false) }

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
        }
    }

    // Background image picker for the currently selected theme
    val backgroundImagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.setBackgroundImage(themeState.theme, uri)
            showPositionPicker = true
        }
    }

    if (showPositionPicker && themeState.backgroundPath != null) {
        BackgroundPositionPickerDialog(
            imagePath = themeState.backgroundPath!!,
            initialBiasX = themeState.backgroundBiasX,
            initialBiasY = themeState.backgroundBiasY,
            onDismiss = { showPositionPicker = false },
            onSave = { biasX, biasY ->
                viewModel.setBackgroundPosition(themeState.theme, biasX, biasY)
                showPositionPicker = false
            }
        )
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
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AppIcons.ArrowBack, "Back")
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
                            Icon(AppIcons.CreateNewFolder, null)
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
                                AppIcons.FolderOff,
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
                                AppIcons.Folder,
                                null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = { showConfirmRemove = uri }) {
                                Icon(
                                    AppIcons.RemoveCircleOutline,
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
                        onClick = { viewModel.rescanAll() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Icon(AppIcons.Refresh, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Rescan All Folders")
                    }
                }
            }

            // ── Scan status ─────────────────────────────────────────────
            when (val status = scanStatus) {
                is ScanStatus.Idle -> {}
                is ScanStatus.Scanning -> {
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
                                        "Scanning folders…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        "Found ${status.songsFound} songs in ${status.foldersScanned} folders so far",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    }
                }
                is ScanStatus.Saving -> {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "Saving songs to your library…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(Modifier.height(8.dp))
                                if (status.songsTotal > 0) {
                                    LinearProgressIndicator(
                                        progress = { status.songsSaved.toFloat() / status.songsTotal },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "${status.songsSaved} of ${status.songsTotal} songs",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
            }

            // ── Section: Theme ──────────────────────────────────────────
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    "Theme",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppTheme.entries.chunked(2).forEach { rowThemes ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowThemes.forEach { theme ->
                                ThemeSwatchCard(
                                    theme = theme,
                                    selected = theme == themeState.theme,
                                    onClick = { viewModel.setTheme(theme) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (rowThemes.size < 2) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surface),
                                contentAlignment = Alignment.Center
                            ) {
                                if (themeState.backgroundPath != null) {
                                    AsyncImage(
                                        model = themeState.backgroundPath,
                                        contentDescription = "Background image preview",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(
                                        AppIcons.Image,
                                        null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Background image",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    "For ${themeState.theme.displayName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (themeState.backgroundPath != null) {
                                IconButton(onClick = { viewModel.setBackgroundImage(themeState.theme, null) }) {
                                    Icon(
                                        AppIcons.Close,
                                        "Remove background image",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            if (themeState.backgroundPath != null) {
                                TextButton(onClick = { showPositionPicker = true }) {
                                    Text("Position")
                                }
                            }
                            TextButton(
                                onClick = {
                                    backgroundImagePickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                            ) {
                                Text(if (themeState.backgroundPath != null) "Change" else "Choose")
                            }
                        }
                    }
                }
            }

            if (themeState.backgroundPath != null) {
                item {
                    var sliderValue by remember(themeState.theme) { mutableStateOf(themeState.surfaceOpacity) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "Menu transparency",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "Lower this to see more of the background image through app bars, the nav bar, and menus",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = sliderValue,
                            onValueChange = { sliderValue = it },
                            onValueChangeFinished = {
                                viewModel.setSurfaceOpacity(themeState.theme, sliderValue)
                            },
                            valueRange = 0.2f..1f
                        )
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
                        Icon(AppIcons.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
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
                                Icon(AppIcons.SystemUpdate, null)
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
                                    AppIcons.CheckCircle,
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
                                Icon(AppIcons.Download, null)
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
                                    AppIcons.ErrorOutline,
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

@Composable
private fun ThemeSwatchCard(
    theme: AppTheme,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = colorSchemeFor(theme)
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(scheme.primary, scheme.secondary, scheme.tertiary).forEach { color ->
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    theme.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                if (selected) {
                    Icon(
                        AppIcons.CheckCircle,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
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
