package com.musicplayer.presentation.browse.albums

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.musicplayer.data.repository.MusicRepository
import com.musicplayer.domain.model.Album
import com.musicplayer.domain.model.Song
import com.musicplayer.domain.model.SortOrder
import com.musicplayer.presentation.PlayerViewModel
import com.musicplayer.presentation.browse.songs.SongListItem
import com.musicplayer.presentation.browse.songs.formatDuration
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── ViewModels ────────────────────────────────────────────────────────────────

@HiltViewModel
class AlbumsViewModel @Inject constructor(
    private val repository: MusicRepository
) : ViewModel() {

    private val sortOrder = MutableStateFlow<SortOrder>(SortOrder.TitleAsc)

    @OptIn(ExperimentalCoroutinesApi::class)
    val albums: StateFlow<List<Album>> = sortOrder.flatMapLatest { order ->
        repository.getAllAlbums().map { albums ->
            when (order) {
                SortOrder.TitleAsc -> albums.sortedBy { it.name.lowercase() }
                SortOrder.ArtistAsc -> albums.sortedBy { it.artist.lowercase() }
                SortOrder.YearDesc -> albums.sortedByDescending { it.year }
                SortOrder.YearAsc -> albums.sortedBy { it.year }
                else -> albums.sortedBy { it.name.lowercase() }
            }
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSortOrder(order: SortOrder) { sortOrder.value = order }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    private val repository: MusicRepository
) : ViewModel() {

    private val _albumId = MutableStateFlow<Long?>(null)

    val album: StateFlow<Album?> = _albumId
        .filterNotNull()
        .flatMapLatest { id ->
            flow { emit(repository.getAlbumById(id)) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val songs: StateFlow<List<Song>> = _albumId
        .filterNotNull()
        .flatMapLatest { repository.getSongsByAlbum(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setAlbumId(id: Long) { _albumId.value = id }
}

// ── Albums Grid ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(
    onAlbumClick: (Long) -> Unit,
    viewModel: AlbumsViewModel = hiltViewModel()
) {
    val albums by viewModel.albums.collectAsState()
    var showSortMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Albums") },
                actions = {
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, "Sort")
                    }
                    DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                        DropdownMenuItem(text = { Text("Name A-Z") }, onClick = {
                            viewModel.setSortOrder(SortOrder.TitleAsc); showSortMenu = false
                        })
                        DropdownMenuItem(text = { Text("Artist") }, onClick = {
                            viewModel.setSortOrder(SortOrder.ArtistAsc); showSortMenu = false
                        })
                        DropdownMenuItem(text = { Text("Year (Newest)") }, onClick = {
                            viewModel.setSortOrder(SortOrder.YearDesc); showSortMenu = false
                        })
                        DropdownMenuItem(text = { Text("Year (Oldest)") }, onClick = {
                            viewModel.setSortOrder(SortOrder.YearAsc); showSortMenu = false
                        })
                    }
                }
            )
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(albums, key = { it.id }) { album ->
                AlbumGridCard(album = album, onClick = { onAlbumClick(album.id) })
            }
        }
    }
}

@Composable
fun AlbumGridCard(album: Album, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium
    ) {
        Column {
            AsyncImage(
                model = album.artworkUri,
                contentDescription = album.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                contentScale = ContentScale.Crop
            )
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = album.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (album.year > 0) {
                    Text(
                        text = album.year.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ── Album Detail ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    albumId: Long,
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit,
    viewModel: AlbumDetailViewModel = hiltViewModel()
) {
    LaunchedEffect(albumId) { viewModel.setAlbumId(albumId) }

    val album by viewModel.album.collectAsState()
    val songs by viewModel.songs.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(album?.name ?: "Album") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Album header
            item {
                album?.let { alb ->
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AsyncImage(
                            model = alb.artworkUri,
                            contentDescription = alb.name,
                            modifier = Modifier
                                .size(200.dp)
                                .clip(MaterialTheme.shapes.large),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(alb.name, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            alb.artist,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (alb.year > 0) {
                            Text(
                                "${alb.year} • ${alb.songCount} songs",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { playerViewModel.playSongs(songs, 0) }) {
                            Icon(Icons.Default.PlayArrow, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Play All")
                        }
                    }
                }
            }

            // Song list
            itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
                SongListItem(
                    song = song,
                    onClick = { playerViewModel.playSongs(songs, index) }
                )
            }
        }
    }
}
