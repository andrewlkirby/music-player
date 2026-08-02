package com.musicplayer.presentation.browse.folders

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import com.musicplayer.presentation.theme.AppIcons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicplayer.data.repository.MusicRepository
import com.musicplayer.domain.model.Song
import com.musicplayer.presentation.PlayerViewModel
import com.musicplayer.presentation.browse.playlists.AddToPlaylistSheet
import com.musicplayer.presentation.browse.songs.SongListItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FolderItem(
    val name: String,
    val fullPath: String,
    val isDirectory: Boolean,
    val songCount: Int = 0
)

enum class FolderSongSort {
    TrackNumber, Name
}

data class FoldersUiState(
    val currentPath: String = "",
    val breadcrumbs: List<String> = emptyList(),
    val items: List<FolderItem> = emptyList(),
    val songs: List<Song> = emptyList(),
    val songSort: FolderSongSort = FolderSongSort.TrackNumber,
    val isLoading: Boolean = true
)

@HiltViewModel
class FoldersViewModel @Inject constructor(
    private val repository: MusicRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FoldersUiState())
    val uiState: StateFlow<FoldersUiState> = _uiState.asStateFlow()

    private var allPaths: List<String> = emptyList()
    private var subDirs: List<FolderItem> = emptyList()
    private var unsortedDirectSongs: List<Song> = emptyList()

    init {
        viewModelScope.launch {
            allPaths = repository.getAllFolderPaths()
            navigateTo("")
        }
    }

    fun navigateTo(path: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            // Build breadcrumbs from path
            val breadcrumbs = if (path.isEmpty()) listOf()
            else {
                val parts = path.trimEnd('/').split("/").filter { it.isNotEmpty() }
                parts.mapIndexed { i, _ ->
                    "/" + parts.take(i + 1).joinToString("/")
                }
            }

            // Find immediate child folder names in a single pass over allPaths
            val prefix = if (path.isEmpty()) "/" else path.trimEnd('/')
            val prefixWithSlash = if (prefix == "/") "/" else "$prefix/"
            val childNames = allPaths
                .asSequence()
                .filter { it.startsWith(prefixWithSlash) }
                .map { it.removePrefix(prefixWithSlash).substringBefore("/") }
                .filter { it.isNotEmpty() }
                .toSet()

            // Songs directly in this folder (single-shot read; avoids leaking a live collector)
            val songsFlow = if (path.isEmpty()) {
                repository.getAllSongs()
            } else {
                repository.getSongsByFolder(path)
            }
            val songs = songsFlow.first()

            val directSongs = songs.filter { song ->
                val songDir = song.path.substringBeforeLast("/")
                songDir == path.trimEnd('/')
            }

            // Per-child song counts derived from the same recursive result set above,
            // instead of one extra DB round-trip per subfolder (was the slow part for
            // folders with many children).
            val childSongCounts = songs
                .asSequence()
                .filter { it.path.startsWith(prefixWithSlash) }
                .mapNotNull { song ->
                    val rest = song.path.removePrefix(prefixWithSlash)
                    val slashIndex = rest.indexOf('/')
                    if (slashIndex < 0) null else rest.substring(0, slashIndex)
                }
                .groupingBy { it }
                .eachCount()

            subDirs = childNames.map { name ->
                val dirPath = (prefixWithSlash + name).replace("//", "/")
                FolderItem(
                    name = name,
                    fullPath = dirPath,
                    isDirectory = true,
                    songCount = childSongCounts[name] ?: 0
                )
            }.sortedBy { it.name.lowercase() }
            unsortedDirectSongs = directSongs

            _uiState.update {
                it.copy(
                    currentPath = path,
                    breadcrumbs = breadcrumbs,
                    isLoading = false
                )
            }
            applySort()
        }
    }

    fun navigateUp() {
        val current = _uiState.value.currentPath
        if (current.isEmpty()) return
        val parent = current.substringBeforeLast("/").let {
            if (it == current) "" else it
        }
        navigateTo(parent)
    }

    fun setSongSort(order: FolderSongSort) {
        _uiState.update { it.copy(songSort = order) }
        applySort()
    }

    private fun applySort() {
        val order = _uiState.value.songSort
        val sortedSongs = when (order) {
            FolderSongSort.TrackNumber -> unsortedDirectSongs.sortedWith(
                compareBy(
                    { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE },
                    { it.title.lowercase() }
                )
            )
            FolderSongSort.Name -> unsortedDirectSongs.sortedBy { it.title.lowercase() }
        }

        val folderItems = subDirs + sortedSongs.map { song ->
            FolderItem(
                name = song.path.substringAfterLast("/"),
                fullPath = song.path,
                isDirectory = false
            )
        }

        _uiState.update { it.copy(items = folderItems, songs = sortedSongs) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(
    playerViewModel: PlayerViewModel,
    viewModel: FoldersViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var showSortMenu by remember { mutableStateOf(false) }
    var songForPlaylist by remember { mutableStateOf<Song?>(null) }

    BackHandler(enabled = state.currentPath.isNotEmpty()) {
        viewModel.navigateUp()
    }

    songForPlaylist?.let { song ->
        AddToPlaylistSheet(songId = song.id, onDismiss = { songForPlaylist = null })
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Folders") },
                navigationIcon = {
                    if (state.currentPath.isNotEmpty()) {
                        IconButton(onClick = { viewModel.navigateUp() }) {
                            Icon(AppIcons.ArrowBack, "Up")
                        }
                    }
                },
                actions = {
                    if (state.songs.isNotEmpty()) {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(AppIcons.Sort, "Sort")
                        }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            DropdownMenuItem(text = { Text("Track Number") }, onClick = {
                                viewModel.setSongSort(FolderSongSort.TrackNumber); showSortMenu = false
                            })
                            DropdownMenuItem(text = { Text("Name (A-Z)") }, onClick = {
                                viewModel.setSongSort(FolderSongSort.Name); showSortMenu = false
                            })
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // Breadcrumb navigation
            if (state.breadcrumbs.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Root
                    TextButton(onClick = { viewModel.navigateTo("") }) {
                        Icon(AppIcons.Home, null, modifier = Modifier.size(16.dp))
                    }
                    state.breadcrumbs.forEachIndexed { index, crumb ->
                        Icon(
                            AppIcons.ChevronRight,
                            null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { viewModel.navigateTo(crumb) }) {
                            Text(
                                crumb.split("/").last(),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (index == state.breadcrumbs.lastIndex)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                HorizontalDivider()
            }

            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            AppIcons.FolderOpen,
                            null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("No music files found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                // Play all button when there are songs
                if (state.songs.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { playerViewModel.playSongs(state.songs, 0) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(AppIcons.PlayArrow, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Play Folder")
                        }
                    }
                }

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(state.items, key = { _, item -> item.fullPath }) { _, item ->
                        if (item.isDirectory) {
                            // Folder row
                            ListItem(
                                headlineContent = {
                                    Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = {
                                    Text(
                                        "${item.songCount} tracks",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        AppIcons.Folder,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(40.dp)
                                    )
                                },
                                trailingContent = {
                                    Icon(AppIcons.ChevronRight, null)
                                },
                                modifier = Modifier.clickable { viewModel.navigateTo(item.fullPath) }
                            )
                        } else {
                            // Song row — find matching song from state
                            val song = state.songs.find { it.path == item.fullPath }
                            if (song != null) {
                                val songIndex = state.songs.indexOf(song)
                                SongListItem(
                                    song = song,
                                    onClick = { playerViewModel.playSongs(state.songs, songIndex) },
                                    showTrackNumber = true,
                                    onLongClick = { songForPlaylist = song }
                                )
                            }
                        }
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
            }
        }
    }
}
