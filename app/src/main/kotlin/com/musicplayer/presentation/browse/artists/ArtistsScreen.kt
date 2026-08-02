package com.musicplayer.presentation.browse.artists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import com.musicplayer.presentation.theme.AppIcons
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
import com.musicplayer.domain.model.Artist
import com.musicplayer.domain.model.Song
import com.musicplayer.presentation.PlayerViewModel
import com.musicplayer.presentation.browse.albums.AlbumGridCard
import com.musicplayer.presentation.browse.playlists.AddToPlaylistSheet
import com.musicplayer.presentation.browse.songs.SongListItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class ArtistsViewModel @Inject constructor(
    private val repository: MusicRepository
) : ViewModel() {
    val artists: StateFlow<List<Artist>> = repository.getAllArtists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    private val repository: MusicRepository
) : ViewModel() {
    private val _artistId = MutableStateFlow<Long?>(null)

    val artist: StateFlow<Artist?> = _artistId.filterNotNull()
        .flatMapLatest { id -> flow { emit(repository.getAllArtists().first().find { it.id == id }) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val albums: StateFlow<List<Album>> = _artistId.filterNotNull()
        .flatMapLatest { repository.getAlbumsByArtist(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val songs: StateFlow<List<Song>> = _artistId.filterNotNull()
        .flatMapLatest { repository.getSongsByArtist(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setArtistId(id: Long) { _artistId.value = id }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistsScreen(
    onArtistClick: (Long) -> Unit,
    viewModel: ArtistsViewModel = hiltViewModel()
) {
    val artists by viewModel.artists.collectAsState()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Artists") }) }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            itemsIndexed(artists, key = { _, a -> a.id }) { _, artist ->
                ListItem(
                    headlineContent = { Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            "${artist.albumCount} albums • ${artist.songCount} songs",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    leadingContent = {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = CircleShape,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(AppIcons.Person, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                }
                            }
                        }
                    },
                    modifier = Modifier.clickable { onArtistClick(artist.id) }
                )
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
    artistId: Long,
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit,
    onAlbumClick: (Long) -> Unit,
    viewModel: ArtistDetailViewModel = hiltViewModel()
) {
    LaunchedEffect(artistId) { viewModel.setArtistId(artistId) }

    val artist by viewModel.artist.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val songs by viewModel.songs.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var songForPlaylist by remember { mutableStateOf<Song?>(null) }

    songForPlaylist?.let { song ->
        AddToPlaylistSheet(songId = song.id, onDismiss = { songForPlaylist = null })
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(artist?.name ?: "Artist") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, "Back") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Albums") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Songs") })
            }

            when (selectedTab) {
                0 -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(albums, key = { it.id }) { album ->
                        AlbumGridCard(album = album, onClick = { onAlbumClick(album.id) })
                    }
                }
                1 -> LazyColumn {
                    itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
                        SongListItem(
                            song = song,
                            onClick = { playerViewModel.playSongs(songs, index) },
                            onLongClick = { songForPlaylist = song }
                        )
                    }
                }
            }
        }
    }
}
