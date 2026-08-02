package com.musicplayer.presentation.nowplaying

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.musicplayer.presentation.theme.AppIcons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.musicplayer.domain.model.RepeatMode
import com.musicplayer.presentation.PlayerUiState
import com.musicplayer.presentation.PlayerViewModel
import com.musicplayer.presentation.browse.playlists.AddToPlaylistSheet
import com.musicplayer.presentation.browse.songs.formatDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit,
    onEqualizerClick: () -> Unit
) {
    val state by playerViewModel.uiState.collectAsState()
    var showQueue by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showAddToPlaylist by remember { mutableStateOf(false) }

    if (showAddToPlaylist) {
        state.currentSong?.let { song ->
            AddToPlaylistSheet(songId = song.id, onDismiss = { showAddToPlaylist = false })
        }
    }

    if (showSleepTimerDialog) {
        SleepTimerDialog(
            onDismiss = { showSleepTimerDialog = false },
            onSetTimer = { minutes ->
                playerViewModel.setSleepTimer(minutes)
                showSleepTimerDialog = false
            }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AppIcons.KeyboardArrowDown, "Collapse")
                    }
                },
                actions = {
                    IconButton(onClick = { showQueue = !showQueue }) {
                        Icon(AppIcons.QueueMusic, "Queue")
                    }
                    IconButton(
                        onClick = { showAddToPlaylist = true },
                        enabled = state.currentSong != null
                    ) {
                        Icon(AppIcons.PlaylistAdd, "Add to playlist")
                    }
                    IconButton(onClick = onEqualizerClick) {
                        Icon(AppIcons.Equalizer, "Equalizer")
                    }
                    IconButton(onClick = { showSleepTimerDialog = true }) {
                        Icon(AppIcons.Bedtime, "Sleep timer")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (showQueue) {
                QueueView(
                    state = state,
                    onSongClick = { _ ->
                        // Seek to item in queue — controller handles this via MediaController
                    },
                    onClose = { showQueue = false }
                )
            } else {
                NowPlayingContent(
                    state = state,
                    onPlayPause = { playerViewModel.togglePlayPause() },
                    onNext = { playerViewModel.seekToNext() },
                    onPrevious = { playerViewModel.seekToPrevious() },
                    onSeek = { playerViewModel.seekTo(it) },
                    onToggleShuffle = { playerViewModel.toggleShuffle() },
                    onToggleRepeat = { playerViewModel.toggleRepeat() },
                    onToggleFavorite = { song ->
                        playerViewModel.toggleFavorite(song.id, !song.isFavorite)
                    }
                )
            }
        }
    }
}

@Composable
private fun NowPlayingContent(
    state: PlayerUiState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleFavorite: (com.musicplayer.domain.model.Song) -> Unit
) {
    val song = state.currentSong

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Album art
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = song?.artworkUri,
                contentDescription = "Album art",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop
            )
            if (song == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        AppIcons.MusicNote,
                        null,
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Track info + favorite
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song?.title ?: "Not Playing",
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song?.artist ?: "",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song?.album ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = { song?.let(onToggleFavorite) },
                enabled = song != null
            ) {
                Icon(
                    imageVector = if (song?.isFavorite == true) AppIcons.Favorite
                    else AppIcons.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (song?.isFavorite == true) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Seek bar
        Column(modifier = Modifier.fillMaxWidth()) {
            Slider(
                value = if (state.duration > 0) state.position.toFloat() / state.duration else 0f,
                onValueChange = { fraction ->
                    onSeek((fraction * state.duration).toLong())
                },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    formatDuration(state.position),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    formatDuration(state.duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Playback controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Shuffle
            IconButton(onClick = onToggleShuffle) {
                Icon(
                    AppIcons.Shuffle,
                    "Shuffle",
                    tint = if (state.shuffleEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
            }

            // Previous
            IconButton(onClick = onPrevious, modifier = Modifier.size(48.dp)) {
                Icon(AppIcons.SkipPrevious, "Previous", modifier = Modifier.size(36.dp))
            }

            // Play/Pause
            FilledIconButton(
                onClick = onPlayPause,
                modifier = Modifier.size(64.dp)
            ) {
                Icon(
                    imageVector = if (state.isPlaying) AppIcons.Pause else AppIcons.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(36.dp)
                )
            }

            // Next
            IconButton(onClick = onNext, modifier = Modifier.size(48.dp)) {
                Icon(AppIcons.SkipNext, "Next", modifier = Modifier.size(36.dp))
            }

            // Repeat
            IconButton(onClick = onToggleRepeat) {
                Icon(
                    imageVector = when (state.repeatMode) {
                        RepeatMode.ONE -> AppIcons.RepeatOne
                        else -> AppIcons.Repeat
                    },
                    contentDescription = "Repeat",
                    tint = when (state.repeatMode) {
                        RepeatMode.OFF -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun QueueView(
    state: PlayerUiState,
    onSongClick: (Int) -> Unit,
    onClose: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Queue",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClose) {
                Icon(AppIcons.Close, "Close queue")
            }
        }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(state.queue, key = { i, s -> "$i-${s.id}" }) { _, song ->
                val indexInQueue = state.queue.indexOf(song) // UseindexOf as a safe fallback or just keep using index if we rename it
                val isCurrent = indexInQueue == state.currentQueueIndex
                ListItem(
                    headlineContent = {
                        Text(
                            song.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    supportingContent = {
                        Text(
                            song.artist,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    leadingContent = {
                        if (isCurrent) {
                            Icon(
                                AppIcons.VolumeUp,
                                null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text(
                                "${indexInQueue + 1}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.width(24.dp)
                            )
                        }
                    },
                    modifier = Modifier
                        .clickable { onSongClick(indexInQueue) }
                        .background(
                            if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            else Color.Transparent
                        )
                )
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun SleepTimerDialog(
    onDismiss: () -> Unit,
    onSetTimer: (Int) -> Unit
) {
    val options = listOf(5, 10, 15, 20, 30, 45, 60, 90)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep Timer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEach { minutes ->
                    TextButton(
                        onClick = { onSetTimer(minutes) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (minutes < 60) "$minutes minutes"
                            else "${minutes / 60}h ${if (minutes % 60 > 0) "${minutes % 60}m" else ""}",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
