package com.musicplayer.presentation.browse.playlists

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import com.musicplayer.presentation.theme.AppIcons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import coil.request.ImageRequest
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

    fun reorderSongs(playlistId: Long, orderedSongIds: List<Long>) {
        viewModelScope.launch { repository.reorderPlaylistSongs(playlistId, orderedSongIds) }
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
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
    val isUserPlaylist = playlistId > 0
    val title = when (playlistId) {
        -1L -> "Recently Added"
        -2L -> "Most Played"
        -3L -> "Favorites"
        else -> "Playlist"
    }

    // Reorderable local copy — kept in sync with the DB-backed flow, except
    // mid-drag where it temporarily leads the flow until the drop is persisted.
    var orderedSongs by remember(playlistId) { mutableStateOf(songs) }
    LaunchedEffect(songs) { orderedSongs = songs }

    var songForPlaylist by remember { mutableStateOf<Song?>(null) }
    var songForAction by remember { mutableStateOf<Song?>(null) }

    songForPlaylist?.let { song ->
        AddToPlaylistSheet(songId = song.id, onDismiss = { songForPlaylist = null })
    }

    songForAction?.let { song ->
        ModalBottomSheet(onDismissRequest = { songForAction = null }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                ListItem(
                    headlineContent = { Text("Add to Another Playlist") },
                    leadingContent = { Icon(AppIcons.PlaylistAdd, null) },
                    modifier = Modifier.clickable {
                        songForPlaylist = song
                        songForAction = null
                    }
                )
                ListItem(
                    headlineContent = { Text("Remove from This Playlist") },
                    leadingContent = {
                        Icon(AppIcons.Delete, null, tint = MaterialTheme.colorScheme.error)
                    },
                    modifier = Modifier.clickable {
                        viewModel.removeSong(playlistId, song.id)
                        songForAction = null
                    }
                )
            }
        }
    }

    val listState = rememberLazyListState()
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, "Back") }
                },
                actions = {
                    if (orderedSongs.isNotEmpty()) {
                        IconButton(onClick = { playerViewModel.playSongs(orderedSongs.shuffled(), 0) }) {
                            Icon(AppIcons.Shuffle, "Shuffle play")
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding)) {
            if (orderedSongs.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { playerViewModel.playSongs(orderedSongs, 0) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(AppIcons.PlayArrow, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Play All")
                        }
                    }
                }
            }

            itemsIndexed(orderedSongs, key = { _, s -> s.id }) { index, song ->
                if (isUserPlaylist) {
                    ReorderableSongRow(
                        song = song,
                        isDragging = draggingIndex == index,
                        dragOffsetY = if (draggingIndex == index) dragOffset else 0f,
                        onClick = { playerViewModel.playSongs(orderedSongs, index) },
                        onLongClick = { songForAction = song },
                        onDragStart = { draggingIndex = index; dragOffset = 0f },
                        onDrag = { deltaY ->
                            val current = draggingIndex
                            val itemHeight = listState.layoutInfo.visibleItemsInfo
                                .find { it.key == song.id }?.size?.toFloat()
                            if (current != null && itemHeight != null) {
                                dragOffset += deltaY
                                val moveBy = (dragOffset / itemHeight).toInt()
                                if (moveBy != 0) {
                                    val target = (current + moveBy).coerceIn(0, orderedSongs.lastIndex)
                                    if (target != current) {
                                        orderedSongs = orderedSongs.toMutableList().apply {
                                            add(target, removeAt(current))
                                        }
                                        dragOffset -= moveBy * itemHeight
                                        draggingIndex = target
                                    }
                                }
                            }
                        },
                        onDragEnd = {
                            draggingIndex = null
                            dragOffset = 0f
                            viewModel.reorderSongs(playlistId, orderedSongs.map { it.id })
                        },
                        modifier = Modifier.animateItem()
                    )
                } else {
                    SongListItem(
                        song = song,
                        onClick = { playerViewModel.playSongs(orderedSongs, index) },
                        onLongClick = { songForPlaylist = song }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReorderableSongRow(
    song: Song,
    isDragging: Boolean,
    dragOffsetY: Float,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .graphicsLayer { translationY = dragOffsetY }
            .zIndex(if (isDragging) 1f else 0f)
    ) {
        ListItem(
            headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(
                    "${song.artist} • ${song.album}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            leadingContent = {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(song.artworkUri)
                        .size(160)
                        .crossfade(false)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.small),
                    contentScale = ContentScale.Crop
                )
            },
            trailingContent = {
                Icon(
                    AppIcons.DragHandle,
                    contentDescription = "Drag to reorder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.pointerInput(song.id) {
                        detectDragGestures(
                            onDragStart = { onDragStart() },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDrag(dragAmount.y)
                            }
                        )
                    }
                )
            },
            tonalElevation = if (isDragging) 4.dp else 0.dp,
            modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
        )
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}
