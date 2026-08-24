package com.musicplayer.data.local.dao

import androidx.paging.PagingSource
import androidx.room.*
import com.musicplayer.data.local.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {

    @Query("SELECT * FROM songs ORDER BY title ASC")
    fun getAllSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getSongById(id: Long): SongEntity?

    @Query("SELECT * FROM songs WHERE albumId = :albumId ORDER BY trackNumber ASC, title ASC")
    fun getSongsByAlbum(albumId: Long): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE artistId = :artistId ORDER BY album ASC, trackNumber ASC")
    fun getSongsByArtist(artistId: Long): Flow<List<SongEntity>>

    @Query("""SELECT * FROM songs WHERE 
        title LIKE '%' || :query || '%' OR 
        artist LIKE '%' || :query || '%' OR 
        album LIKE '%' || :query || '%' OR
        path LIKE '%' || :query || '%'
        ORDER BY title ASC""")
    fun searchSongs(query: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs ORDER BY dateAdded DESC LIMIT :limit")
    fun getRecentlyAdded(limit: Int = 100): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs ORDER BY playCount DESC LIMIT :limit")
    fun getMostPlayed(limit: Int = 100): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isFavorite = 1 ORDER BY title ASC")
    fun getFavorites(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE path LIKE :folderPath || '%' ORDER BY title ASC")
    fun getSongsByFolder(folderPath: String): Flow<List<SongEntity>>

    @Query("SELECT DISTINCT RTRIM(RTRIM(path, REPLACE(path, '/', '')), '/') as folder FROM songs ORDER BY folder ASC")
    suspend fun getAllFolderPaths(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<SongEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(song: SongEntity)

    @Update
    suspend fun updateSong(song: SongEntity)

    @Query("UPDATE songs SET playCount = playCount + 1 WHERE id = :songId")
    suspend fun incrementPlayCount(songId: Long)

    @Query("UPDATE songs SET isFavorite = :isFavorite WHERE id = :songId")
    suspend fun setFavorite(songId: Long, isFavorite: Boolean)

    @Query("SELECT id FROM songs")
    suspend fun getAllSongIds(): List<Long>

    @Query("SELECT id FROM songs WHERE source = :source")
    suspend fun getSongIdsBySource(source: String): List<Long>

    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun getSongsByIds(ids: List<Long>): List<SongEntity>

    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    // One-time cleanup for songs incorrectly scanned in before SdCardScanWorker
    // started excluding playlist files (isAudioMime used to trust SAF's
    // "audio/x-mpegurl" mime for .m3u/.m3u8 as if it were playable audio).
    @Query("DELETE FROM songs WHERE path LIKE '%.m3u' OR path LIKE '%.m3u8' OR path LIKE '%.pls' OR path LIKE '%.cue'")
    suspend fun deleteNonAudioPlaylistFiles()

    @Query("SELECT lastModified FROM songs WHERE path = :path")
    suspend fun getLastModified(path: String): Long?

    @Query("SELECT COUNT(*) FROM songs")
    suspend fun getSongCount(): Int

    // ── Paging (Songs tab) ───────────────────────────────────────────────
    // Query-time ORDER BY, one PagingSource per sort order — avoids loading
    // and sorting the entire table in memory on every screen open/launch.

    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE ASC")
    fun pagingTitleAsc(): PagingSource<Int, SongEntity>

    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE DESC")
    fun pagingTitleDesc(): PagingSource<Int, SongEntity>

    @Query("SELECT * FROM songs ORDER BY artist COLLATE NOCASE ASC, title COLLATE NOCASE ASC")
    fun pagingArtistAsc(): PagingSource<Int, SongEntity>

    @Query("SELECT * FROM songs ORDER BY dateAdded DESC")
    fun pagingDateAddedDesc(): PagingSource<Int, SongEntity>

    @Query("SELECT * FROM songs ORDER BY playCount DESC")
    fun pagingPlayCountDesc(): PagingSource<Int, SongEntity>

    @Query("SELECT COUNT(*) FROM songs")
    fun getSongCountFlow(): Flow<Int>

    // Order-preserving full fetches used only to build the play queue when a
    // song is tapped — the visible paged list is partial, so playback needs
    // the complete sorted list to queue everything after the tapped track.

    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE ASC")
    suspend fun sortedSongsTitleAsc(): List<SongEntity>

    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE DESC")
    suspend fun sortedSongsTitleDesc(): List<SongEntity>

    @Query("SELECT * FROM songs ORDER BY artist COLLATE NOCASE ASC, title COLLATE NOCASE ASC")
    suspend fun sortedSongsArtistAsc(): List<SongEntity>

    @Query("SELECT * FROM songs ORDER BY dateAdded DESC")
    suspend fun sortedSongsDateAddedDesc(): List<SongEntity>

    @Query("SELECT * FROM songs ORDER BY playCount DESC")
    suspend fun sortedSongsPlayCountDesc(): List<SongEntity>

    // Unsorted full fetch for shuffle — order is randomized client-side anyway,
    // so there's no reason to pay for an ORDER BY here.
    @Query("SELECT * FROM songs")
    suspend fun getAllSongsList(): List<SongEntity>
}

@Dao
interface AlbumDao {

    @Query("SELECT * FROM albums ORDER BY name ASC")
    fun getAllAlbums(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun getAlbumById(id: Long): AlbumEntity?

    @Query("SELECT * FROM albums WHERE artistId = :artistId ORDER BY year DESC")
    fun getAlbumsByArtist(artistId: Long): Flow<List<AlbumEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlbums(albums: List<AlbumEntity>)

    @Query("DELETE FROM albums WHERE id NOT IN (:validIds)")
    suspend fun deleteObsolete(validIds: List<Long>)

    @Query("UPDATE albums SET artworkPath = :path WHERE id = :albumId")
    suspend fun updateArtwork(albumId: Long, path: String)
}

@Dao
interface ArtistDao {

    @Query("SELECT * FROM artists ORDER BY name ASC")
    fun getAllArtists(): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artists WHERE id = :id")
    suspend fun getArtistById(id: Long): ArtistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArtists(artists: List<ArtistEntity>)

    @Query("DELETE FROM artists WHERE id NOT IN (:validIds)")
    suspend fun deleteObsolete(validIds: List<Long>)
}

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getPlaylistById(id: Long): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Update
    suspend fun updatePlaylist(playlist: PlaylistEntity)

    @Delete
    suspend fun deletePlaylist(playlist: PlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addSongToPlaylist(entry: PlaylistSongEntity)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long)

    @Query("""
        SELECT s.* FROM songs s 
        INNER JOIN playlist_songs ps ON s.id = ps.songId 
        WHERE ps.playlistId = :playlistId 
        ORDER BY ps.position ASC
    """)
    fun getSongsInPlaylist(playlistId: Long): Flow<List<SongEntity>>

    @Query("SELECT COUNT(*) FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun getPlaylistSongCount(playlistId: Long): Int

    @Query("UPDATE playlist_songs SET position = :position WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun updateSongPosition(playlistId: Long, songId: Long, position: Int)

    @Transaction
    suspend fun reorderSongs(playlistId: Long, orderedSongIds: List<Long>) {
        orderedSongIds.forEachIndexed { index, songId ->
            updateSongPosition(playlistId, songId, index)
        }
    }
}

@Dao
interface PlaybackStateDao {

    @Query("SELECT * FROM playback_state WHERE id = 1")
    suspend fun getPlaybackState(): PlaybackStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePlaybackState(state: PlaybackStateEntity)

    // Cheap counterpart to savePlaybackState — updates progress/mode fields
    // without touching queueJson, so ordinary play/pause and track skips
    // don't re-encode and rewrite the whole (possibly tens-of-thousands-of-
    // ids) queue string on every event. Only takes effect once a row exists
    // (i.e. after the first savePlaybackState call).
    @Query(
        """
        UPDATE playback_state
        SET currentSongId = :currentSongId,
            position = :position,
            shuffleEnabled = :shuffleEnabled,
            repeatMode = :repeatMode,
            currentQueueIndex = :currentQueueIndex
        WHERE id = 1
        """
    )
    suspend fun updatePlaybackProgress(
        currentSongId: Long?,
        position: Long,
        shuffleEnabled: Boolean,
        repeatMode: String,
        currentQueueIndex: Int
    )
}
