package com.musicplayer.domain.model

import android.net.Uri

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val artistId: Long,
    val duration: Long,          // milliseconds
    val trackNumber: Int,
    val year: Int,
    val genre: String,
    val path: String,
    val uri: Uri,
    val size: Long,
    val dateAdded: Long,
    val playCount: Int = 0,
    val isFavorite: Boolean = false,
    val artworkUri: Uri? = null
)

data class Album(
    val id: Long,
    val name: String,
    val artist: String,
    val artistId: Long,
    val year: Int,
    val songCount: Int,
    val artworkUri: Uri? = null
)

data class Artist(
    val id: Long,
    val name: String,
    val albumCount: Int,
    val songCount: Int,
    val artworkUri: Uri? = null
)

data class Playlist(
    val id: Long,
    val name: String,
    val songCount: Int,
    val type: PlaylistType,
    val artworkUri: Uri? = null
)

enum class PlaylistType {
    USER,
    RECENTLY_ADDED,
    MOST_PLAYED,
    FAVORITES
}

data class FolderNode(
    val name: String,
    val path: String,
    val uri: Uri,
    val songCount: Int,
    val children: List<FolderNode> = emptyList(),
    val songs: List<Song> = emptyList()
)

data class PlaybackState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val position: Long = 0L,
    val duration: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val queue: List<Song> = emptyList(),
    val currentQueueIndex: Int = 0
)

enum class RepeatMode { OFF, ONE, ALL }

data class EqPreset(
    val name: String,
    val bands: List<Float> // gains in dB for each frequency band
)

sealed class SortOrder {
    data object TitleAsc : SortOrder()
    data object TitleDesc : SortOrder()
    data object ArtistAsc : SortOrder()
    data object YearAsc : SortOrder()
    data object YearDesc : SortOrder()
    data object DateAddedDesc : SortOrder()
    data object PlayCountDesc : SortOrder()
}
