package com.musicplayer.presentation.browse.songs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import com.musicplayer.presentation.browse.playlists.AddToPlaylistSheet
import com.musicplayer.presentation.theme.AppIcons
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
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.musicplayer.domain.model.Song
import com.musicplayer.domain.model.SortOrder
import com.musicplayer.presentation.PlayerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongsScreen(
    playerViewModel: PlayerViewModel,
    onNavigateToSearch: () -> Unit,
    viewModel: SongsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var showSortMenu by remember { mutableStateOf(false) }
    var songForPlaylist by remember { mutableStateOf<Song?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    songForPlaylist?.let { song ->
        AddToPlaylistSheet(songId = song.id, onDismiss = { songForPlaylist = null })
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Songs")
                        if (state.totalCount > 0) {
                            Text(
                                "${state.totalCount} tracks",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSearch) {
                        Icon(AppIcons.Search, "Search")
                    }
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(AppIcons.Sort, "Sort")
                    }
                    DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                        DropdownMenuItem(text = { Text("Title A-Z") }, onClick = {
                            viewModel.setSortOrder(SortOrder.TitleAsc); showSortMenu = false
                        })
                        DropdownMenuItem(text = { Text("Title Z-A") }, onClick = {
                            viewModel.setSortOrder(SortOrder.TitleDesc); showSortMenu = false
                        })
                        DropdownMenuItem(text = { Text("Artist") }, onClick = {
                            viewModel.setSortOrder(SortOrder.ArtistAsc); showSortMenu = false
                        })
                        DropdownMenuItem(text = { Text("Recently Added") }, onClick = {
                            viewModel.setSortOrder(SortOrder.DateAddedDesc); showSortMenu = false
                        })
                        DropdownMenuItem(text = { Text("Most Played") }, onClick = {
                            viewModel.setSortOrder(SortOrder.PlayCountDesc); showSortMenu = false
                        })
                    }
                }
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                itemsIndexed(state.songs, key = { _, song -> song.id }) { index, song ->
                    SongListItem(
                        song = song,
                        onClick = { playerViewModel.playSongs(state.songs, index) },
                        onLongClick = { songForPlaylist = song }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongListItem(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showTrackNumber: Boolean = false,
    onLongClick: (() -> Unit)? = null
) {
    ListItem(
        headlineContent = {
            val title = if (showTrackNumber && song.trackNumber > 0) {
                "${song.trackNumber}. ${song.title}"
            } else {
                song.title
            }
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                "${song.artist} • ${song.album}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            // Explicit small decode size — a 30k-song list flinging through
            // full-resolution art (some source JPEGs are 1000px+) wastes
            // decode work the 48dp slot never uses.
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(song.artworkUri)
                    .size(160)
                    .crossfade(false)
                    .build(),
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.small),
                contentScale = ContentScale.Crop
            )
        },
        trailingContent = {
            Text(
                formatDuration(song.duration),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    )
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
