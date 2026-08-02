package com.musicplayer.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Which scan pipeline a song row came from. scanMediaStore()'s obsolete-row
// cleanup diffs against MediaStore's own index — without this, it can't
// tell a MediaStore song that's genuinely gone from a SAF/SD-card song that
// was never in MediaStore to begin with, and deletes the latter too.
object SongSource {
    const val MEDIASTORE = "mediastore"
    const val SAF = "saf"
}

@Entity(
    tableName = "songs",
    indices = [
        Index("albumId"), Index("artistId"), Index("path"),
        Index("isFavorite"), Index("dateAdded"), Index("playCount"), Index("source")
    ]
)
data class SongEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val artistId: Long,
    val duration: Long,
    val trackNumber: Int,
    val year: Int,
    val genre: String,
    val path: String,
    val uriString: String,
    val size: Long,
    val dateAdded: Long,
    val playCount: Int = 0,
    val isFavorite: Boolean = false,
    val artworkPath: String? = null,
    val lastModified: Long = 0L,
    val source: String = SongSource.MEDIASTORE
)

@Entity(
    tableName = "albums",
    indices = [Index("artistId")]
)
data class AlbumEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val artist: String,
    val artistId: Long,
    val year: Int,
    val songCount: Int,
    val artworkPath: String? = null
)

@Entity(tableName = "artists")
data class ArtistEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val albumCount: Int,
    val songCount: Int
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String = "USER",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("playlistId"), Index("songId")]
)
data class PlaylistSongEntity(
    val playlistId: Long,
    val songId: Long,
    val position: Int
)

@Entity(tableName = "playback_state")
data class PlaybackStateEntity(
    @PrimaryKey val id: Int = 1,
    val currentSongId: Long? = null,
    val position: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: String = "OFF",
    val queueJson: String = "[]",
    val currentQueueIndex: Int = 0
)
