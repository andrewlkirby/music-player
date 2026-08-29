package com.musicplayer.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.musicplayer.data.local.dao.*
import com.musicplayer.data.local.entities.*

@Database(
    entities = [
        SongEntity::class,
        AlbumEntity::class,
        ArtistEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        PlaybackStateEntity::class
    ],
    // v4: added Index on songs.title/artist, albums.name, artists.name (with
    // NOCASE collation to match COLLATE NOCASE sort queries), so sorted/paged
    // fetches use an index instead of a transient sort. No Migration is
    // written — relies on fallbackToDestructiveMigration (AppModules.kt),
    // which drops and repopulates the DB from MediaStore on next launch. This
    // was an explicit, accepted one-time tradeoff (favorites/playCount/
    // playlists are lost once); going forward scanMediaStore() preserves them
    // across rescans (see MusicRepository.scanMediaStore's existingById map).
    version = 4,
    exportSchema = false
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun albumDao(): AlbumDao
    abstract fun artistDao(): ArtistDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playbackStateDao(): PlaybackStateDao
}
