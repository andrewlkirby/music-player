package com.musicplayer.presentation.browse.playlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.musicplayer.domain.model.Playlist
import com.musicplayer.domain.model.PlaylistType
import com.musicplayer.domain.model.Song
import com.musicplayer.presentation.PlayerViewModel
import com.musicplayer.presentation.browse.songs.SongListItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val repository: MusicRepository
) : ViewModel() {

    val playlists: StateFlow<List<Playlist>> = repository.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val smartPlaylists = listOf(
        Playlist(id = -1L, name = "Recently Added", songCount = 0, type = PlaylistType.RECENTLY_ADDED),
        Playlist(id = -2L, name = "Most Played", songCount = 0, type = PlaylistType.MOST_PLAYED),
        Playlist(id = -3L, name = "Favorites", songCount = 0, type = PlaylistType.FAVORITES)
    )

    fun createPlaylist(name: String) {
        viewModelScope.launch { repository.createPlaylist(name) }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch { repository.deletePlaylist(id) }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    private val repository: MusicRepository
) : ViewModel() {

    private val _playlistId = MutableStateFlow<Long?>(null)

    val songs: StateFlow<List<Song>> = _playlistId.filterNotNull()
        .flatMapLatest { id ->
            when (id) {
                -1L -> repository.getRecentlyAdded()
                -2L -> repository.getMostPlayed()
                -3L -> repository.getFavorites()
                else -> repository.getSongsInPlaylist(id)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setPlaylistId(id: Long) { _playlistId.value = id }

    fun removeSong(playlistId: Long, songId: Long) {
        viewModelScope.launch { repository.removeSongFromPlaylist(playlistId, songId) }
    }
}

// ── Playlists list screen ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(
    onPlaylistClick: (Long) -> Unit,
    viewModel: PlaylistsViewModel = hiltViewModel()
) {
    val playlists by viewModel.playlists.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("New Playlist") },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text("Playlist name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newPlaylistName.isNotBlank()) {
                        viewModel.createPlaylist(newPlaylistName.trim())
                        newPlaylistName = ""
                        showCreateDialog = false
                    }
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false; newPlaylistName = "" }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Playlists") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(AppIcons.Add, "New playlist")
            }
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Smart playlists header
            item {
                Text(
                    "Smart Playlists",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            itemsIndexed(viewModel.smartPlaylists) { _, playlist ->
                PlaylistListItem(
                    playlist = playlist,
                    onClick = { onPlaylistClick(playlist.id) }
                )
            }

            // User playlists header
            if (playlists.isNotEmpty()) {
                item {
                    Text(
                        "My Playlists",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                itemsIndexed(playlists, key = { _, p -> p.id }) { _, playlist ->
                    PlaylistListItem(
                        playlist = playlist,
                        onClick = { onPlaylistClick(playlist.id) },
                        onDelete = { viewModel.deletePlaylist(playlist.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun PlaylistListItem(
    playlist: Playlist,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val icon = when (playlist.type) {
        PlaylistType.RECENTLY_ADDED -> AppIcons.AccessTime
        PlaylistType.MOST_PLAYED -> AppIcons.TrendingUp
        PlaylistType.FAVORITES -> AppIcons.Favorite
        PlaylistType.USER -> AppIcons.QueueMusic
    }
    ListItem(
        headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        },
        trailingContent = {
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(AppIcons.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

// ── Playlist detail ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit,
    viewModel: PlaylistDetailViewModel = hiltViewModel()
) {
    LaunchedEffect(playlistId) { viewModel.setPlaylistId(playlistId) }

    val songs by viewModel.songs.collectAsState()
    val title = when (playlistId) {
        -1L -> "Recently Added"
        -2L -> "Most Played"
        -3L -> "Favorites"
        else -> "Playlist"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, "Back") }
                },
                actions = {
                    if (songs.isNotEmpty()) {
                        IconButton(onClick = { playerViewModel.playSongs(songs.shuffled(), 0) }) {
                            Icon(AppIcons.Shuffle, "Shuffle play")
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (songs.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { playerViewModel.playSongs(songs, 0) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(AppIcons.PlayArrow, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Play All")
                        }
                    }
                }
            }

            itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
                SongListItem(
                    song = song,
                    onClick = { playerViewModel.playSongs(songs, index) }
                )
            }
        }
    }
}
