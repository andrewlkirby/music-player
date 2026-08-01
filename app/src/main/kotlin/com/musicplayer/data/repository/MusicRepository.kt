package com.musicplayer.data.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.musicplayer.data.local.MusicDatabase
import com.musicplayer.data.local.entities.*
import com.musicplayer.data.local.toDomain
import com.musicplayer.domain.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusicRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: MusicDatabase
) {

    // ── Songs ─────────────────────────────────────────────────────────────
    fun getAllSongs(): Flow<List<Song>> =
        db.songDao().getAllSongs().map { list -> list.map { it.toDomain() } }.flowOn(Dispatchers.Default)

    fun getSongsByAlbum(albumId: Long): Flow<List<Song>> =
        db.songDao().getSongsByAlbum(albumId).map { list -> list.map { it.toDomain() } }.flowOn(Dispatchers.Default)

    fun getSongsByArtist(artistId: Long): Flow<List<Song>> =
        db.songDao().getSongsByArtist(artistId).map { list -> list.map { it.toDomain() } }.flowOn(Dispatchers.Default)

    fun searchSongs(query: String): Flow<List<Song>> =
        db.songDao().searchSongs(query).map { list -> list.map { it.toDomain() } }.flowOn(Dispatchers.Default)

    fun getRecentlyAdded(): Flow<List<Song>> =
        db.songDao().getRecentlyAdded().map { list -> list.map { it.toDomain() } }.flowOn(Dispatchers.Default)

    fun getMostPlayed(): Flow<List<Song>> =
        db.songDao().getMostPlayed().map { list -> list.map { it.toDomain() } }.flowOn(Dispatchers.Default)

    fun getFavorites(): Flow<List<Song>> =
        db.songDao().getFavorites().map { list -> list.map { it.toDomain() } }.flowOn(Dispatchers.Default)

    fun getSongsByFolder(folderPath: String): Flow<List<Song>> =
        db.songDao().getSongsByFolder(folderPath).map { list -> list.map { it.toDomain() } }.flowOn(Dispatchers.Default)

    suspend fun getSongById(id: Long): Song? =
        db.songDao().getSongById(id)?.toDomain()

    suspend fun incrementPlayCount(songId: Long) =
        db.songDao().incrementPlayCount(songId)

    suspend fun setFavorite(songId: Long, isFavorite: Boolean) =
        db.songDao().setFavorite(songId, isFavorite)

    // ── Albums ────────────────────────────────────────────────────────────
    fun getAllAlbums(): Flow<List<Album>> =
        db.albumDao().getAllAlbums().map { list -> list.map { it.toDomain() } }.flowOn(Dispatchers.Default)

    fun getAlbumsByArtist(artistId: Long): Flow<List<Album>> =
        db.albumDao().getAlbumsByArtist(artistId).map { list -> list.map { it.toDomain() } }.flowOn(Dispatchers.Default)

    suspend fun getAlbumById(id: Long): Album? =
        db.albumDao().getAlbumById(id)?.toDomain()

    // ── Artists ───────────────────────────────────────────────────────────
    fun getAllArtists(): Flow<List<Artist>> =
        db.artistDao().getAllArtists().map { list -> list.map { it.toDomain() } }.flowOn(Dispatchers.Default)

    // ── Playlists ─────────────────────────────────────────────────────────
    fun getAllPlaylists(): Flow<List<Playlist>> =
        db.playlistDao().getAllPlaylists().map { list ->
            list.map { it.toDomain() }
        }.flowOn(Dispatchers.Default)

    fun getSongsInPlaylist(playlistId: Long): Flow<List<Song>> =
        db.playlistDao().getSongsInPlaylist(playlistId).map { list -> list.map { it.toDomain() } }.flowOn(Dispatchers.Default)

    suspend fun createPlaylist(name: String): Long =
        db.playlistDao().insertPlaylist(PlaylistEntity(name = name))

    suspend fun renamePlaylist(id: Long, newName: String) {
        val existing = db.playlistDao().getPlaylistById(id) ?: return
        db.playlistDao().updatePlaylist(existing.copy(name = newName))
    }

    suspend fun deletePlaylist(id: Long) {
        val existing = db.playlistDao().getPlaylistById(id) ?: return
        db.playlistDao().deletePlaylist(existing)
    }

    suspend fun addSongToPlaylist(playlistId: Long, songId: Long) {
        val count = db.playlistDao().getPlaylistSongCount(playlistId)
        db.playlistDao().addSongToPlaylist(
            PlaylistSongEntity(playlistId = playlistId, songId = songId, position = count)
        )
    }

    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long) =
        db.playlistDao().removeSongFromPlaylist(playlistId, songId)

    // ── Folders ───────────────────────────────────────────────────────────
    suspend fun getAllFolderPaths(): List<String> =
        db.songDao().getAllFolderPaths()

    suspend fun getSongCount(): Int = db.songDao().getSongCount()

    // ── Direct insert (SAF / SD card scans) ──────────────────────────────

    suspend fun insertSongsDirectly(
        songs: List<com.musicplayer.data.local.entities.SongEntity>,
        albums: List<com.musicplayer.data.local.entities.AlbumEntity>,
        artists: List<com.musicplayer.data.local.entities.ArtistEntity>
    ) {
        db.songDao().insertSongs(songs)
        db.albumDao().insertAlbums(albums)
        db.artistDao().insertArtists(artists)
    }

    // ── Playback state ────────────────────────────────────────────────────
    suspend fun savePlaybackState(state: PlaybackStateEntity) =
        db.playbackStateDao().savePlaybackState(state)

    suspend fun getPlaybackState(): PlaybackStateEntity? =
        db.playbackStateDao().getPlaybackState()

    // ── Media scanning ────────────────────────────────────────────────────

    suspend fun scanMediaStore() = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.ARTIST_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED
        )

            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            val cursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                "${MediaStore.Audio.Media.TITLE} ASC"
            )

        val songs = mutableListOf<SongEntity>()
        val albumMap = mutableMapOf<Long, AlbumEntity>()
        val artistMap = mutableMapOf<Long, ArtistEntity>()
        val validIds = mutableListOf<Long>()

        cursor?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val artistIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val trackCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val yearCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val dataCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val dateAddedCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val dateModifiedCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val path = c.getString(dataCol) ?: continue
                val albumId = c.getLong(albumIdCol)
                val artistId = c.getLong(artistIdCol)
                val albumName = c.getString(albumCol) ?: "Unknown Album"
                val artistName = c.getString(artistCol) ?: "Unknown Artist"
                val lastModified = c.getLong(dateModifiedCol)

                // Optimized: Only read tags if really necessary (e.g. file modified)
                // and use a faster metadata reader or trust MediaStore for most cases.
                // val tagData = if (existingModified == null || existingModified != lastModified) {
                //    readTagsFromFile(path)
                // } else null
                val tagData: TagData? = null // For speed, trust MediaStore during initial scan

                val uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                )

                val artworkUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"), albumId
                ).toString()

                val songEntity = SongEntity(
                    id = id,
                    title = tagData?.title ?: c.getString(titleCol) ?: "Unknown",
                    artist = tagData?.artist ?: artistName,
                    album = tagData?.album ?: albumName,
                    albumId = albumId,
                    artistId = artistId,
                    duration = c.getLong(durationCol),
                    trackNumber = tagData?.trackNumber ?: (c.getInt(trackCol) % 1000),
                    year = tagData?.year ?: c.getInt(yearCol),
                    genre = tagData?.genre ?: "",
                    path = path,
                    uriString = uri.toString(),
                    size = c.getLong(sizeCol),
                    dateAdded = c.getLong(dateAddedCol),
                    artworkPath = tagData?.embeddedArtPath ?: artworkUri,
                    lastModified = lastModified
                )

                songs.add(songEntity)
                validIds.add(id)

                // Build album map
                if (!albumMap.containsKey(albumId)) {
                    albumMap[albumId] = AlbumEntity(
                        id = albumId,
                        name = albumName,
                        artist = artistName,
                        artistId = artistId,
                        year = c.getInt(yearCol),
                        songCount = 0,
                        artworkPath = tagData?.embeddedArtPath ?: artworkUri
                    )
                }

                // Build artist map
                if (!artistMap.containsKey(artistId)) {
                    artistMap[artistId] = ArtistEntity(
                        id = artistId,
                        name = artistName,
                        albumCount = 0,
                        songCount = 0
                    )
                }
            }
        }

        // Update song counts
        val albumCounts = songs.groupBy { it.albumId }.mapValues { it.value.size }
        val artistSongCounts = songs.groupBy { it.artistId }.mapValues { it.value.size }
        val artistAlbumCounts = songs.groupBy { it.artistId }
            .mapValues { entry -> entry.value.map { it.albumId }.toSet().size }

        val finalAlbums = albumMap.values.map { album ->
            album.copy(songCount = albumCounts[album.id] ?: 0)
        }
        val finalArtists = artistMap.values.map { artist ->
            artist.copy(
                songCount = artistSongCounts[artist.id] ?: 0,
                albumCount = artistAlbumCounts[artist.id] ?: 0
            )
        }

        // Persist to DB
        db.songDao().insertSongs(songs)
        db.albumDao().insertAlbums(finalAlbums)
        db.artistDao().insertArtists(finalArtists)

        // Clean up deleted files. Deletes are chunked (SQLite caps bound variables at ~999,
        // so a single "id IN (...)" over a large library would otherwise fail outright).
        val existingIds = db.songDao().getAllSongIds()
        val obsoleteIds = existingIds - validIds.toSet()
        obsoleteIds.chunked(900).forEach { chunk ->
            db.songDao().deleteByIds(chunk)
        }
    }

    private data class TagData(
        val title: String?,
        val artist: String?,
        val album: String?,
        val trackNumber: Int,
        val year: Int,
        val genre: String?,
        val embeddedArtPath: String?
    )

    private fun readTagsFromFile(path: String): TagData? {
        return try {
            val file = File(path)
            if (!file.exists()) return null
            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tag ?: return null

            TagData(
                title = tag.getFirst(FieldKey.TITLE).takeIf { it.isNotEmpty() },
                artist = tag.getFirst(FieldKey.ARTIST).takeIf { it.isNotEmpty() },
                album = tag.getFirst(FieldKey.ALBUM).takeIf { it.isNotEmpty() },
                trackNumber = tag.getFirst(FieldKey.TRACK).toIntOrNull() ?: 0,
                year = tag.getFirst(FieldKey.YEAR).toIntOrNull() ?: 0,
                genre = tag.getFirst(FieldKey.GENRE).takeIf { it.isNotEmpty() },
                embeddedArtPath = null // We use MediaStore artwork URIs for caching
            )
        } catch (e: Exception) {
            null
        }
    }
}
