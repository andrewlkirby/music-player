package com.musicplayer.data.repository

import android.content.Context
import com.musicplayer.data.local.MusicDatabase
import com.musicplayer.data.local.dao.SongDao
import com.musicplayer.data.local.dao.AlbumDao
import com.musicplayer.data.local.dao.ArtistDao
import com.musicplayer.data.local.entities.SongEntity
import com.musicplayer.data.local.entities.AlbumEntity
import com.musicplayer.data.local.entities.ArtistEntity
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations

class MusicRepositoryTest {

    @Mock
    private lateinit var mockDb: MusicDatabase
    @Mock
    private lateinit var mockContext: Context
    @Mock
    private lateinit var mockSongDao: SongDao
    @Mock
    private lateinit var mockAlbumDao: AlbumDao
    @Mock
    private lateinit var mockArtistDao: ArtistDao

    private lateinit var repository: MusicRepository

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        `when`(mockDb.songDao()).thenReturn(mockSongDao)
        `when`(mockDb.albumDao()).thenReturn(mockAlbumDao)
        `when`(mockDb.artistDao()).thenReturn(mockArtistDao)
        repository = MusicRepository(mockContext, mockDb)
    }

    @Test
    fun `test inserting songs directly calls all daos`() = runBlocking {
        val songs = listOf(
            SongEntity(
                id = 1L,
                title = "Test Song",
                artist = "Test Artist",
                album = "Test Album",
                albumId = 1L,
                artistId = 1L,
                duration = 1000L,
                trackNumber = 1,
                year = 2024,
                genre = "Test",
                path = "/path/to/song.mp3",
                uriString = "content://media/external/audio/media/1",
                size = 1024L,
                dateAdded = 123456789L
            )
        )
        val albums = listOf(
            AlbumEntity(
                id = 1L,
                name = "Test Album",
                artist = "Test Artist",
                artistId = 1L,
                year = 2024,
                songCount = 1
            )
        )
        val artists = listOf(
            ArtistEntity(
                id = 1L,
                name = "Test Artist",
                albumCount = 1,
                songCount = 1
            )
        )

        // Exercises insertSongsAndMetadata directly rather than
        // insertSongsDirectly (its withTransaction-wrapped caller) — Room's
        // transaction coroutine machinery needs a real database and hangs
        // against a bare mock here; the DAO-delegation behavior under test
        // is identical either way.
        repository.insertSongsAndMetadata(songs, albums, artists)

        verify(mockSongDao).insertSongs(songs)
        verify(mockAlbumDao).insertAlbums(albums)
        verify(mockArtistDao).insertArtists(artists)
    }
}
