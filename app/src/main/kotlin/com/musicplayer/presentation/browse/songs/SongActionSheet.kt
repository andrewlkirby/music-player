package com.musicplayer.presentation.browse.songs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musicplayer.domain.model.Song
import com.musicplayer.presentation.PlayerViewModel
import com.musicplayer.presentation.theme.AppIcons

// Long-press action sheet shared by every song list (Songs/Albums/Artists/
// Folders/Playlists/Search) — "Play Next" and "Add to Queue" both go through
// PlayerViewModel, which routes them to the playback service's virtual queue;
// "Add to Playlist" defers to the caller's existing AddToPlaylistSheet flow via
// onAddToPlaylist so this doesn't duplicate that logic.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongActionSheet(
    song: Song,
    playerViewModel: PlayerViewModel,
    onAddToPlaylist: () -> Unit,
    onDismiss: () -> Unit,
    // Playlist screens pass this to append a "Remove from This Playlist" row
    // for playlist-membership rows; omitted everywhere else.
    onRemoveFromPlaylist: (() -> Unit)? = null
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                song.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            ListItem(
                headlineContent = { Text("Play Next") },
                leadingContent = { Icon(AppIcons.SkipNext, null) },
                modifier = Modifier.clickable {
                    playerViewModel.playNext(song)
                    onDismiss()
                }
            )
            ListItem(
                headlineContent = { Text("Add to Queue") },
                leadingContent = { Icon(AppIcons.QueueMusic, null) },
                modifier = Modifier.clickable {
                    playerViewModel.addToQueue(song)
                    onDismiss()
                }
            )
            ListItem(
                headlineContent = { Text("Add to Playlist") },
                leadingContent = { Icon(AppIcons.PlaylistAdd, null) },
                modifier = Modifier.clickable {
                    onAddToPlaylist()
                    onDismiss()
                }
            )
            if (onRemoveFromPlaylist != null) {
                ListItem(
                    headlineContent = { Text("Remove from This Playlist") },
                    leadingContent = {
                        Icon(AppIcons.Delete, null, tint = MaterialTheme.colorScheme.error)
                    },
                    modifier = Modifier.clickable {
                        onRemoveFromPlaylist()
                        onDismiss()
                    }
                )
            }
        }
    }
}
