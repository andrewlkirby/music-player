package com.musicplayer.data.local

import android.net.Uri
import com.musicplayer.data.local.entities.*
import com.musicplayer.domain.model.*

fun SongEntity.toDomain(): Song = Song(
    id = id,
    title = title,
    artist = artist,
    album = album,
    albumId = albumId,
    artistId = artistId,
    duration = duration,
    trackNumber = trackNumber,
    year = year,
    genre = genre,
    path = path,
    uri = Uri.parse(uriString),
    size = size,
    dateAdded = dateAdded,
    playCount = playCount,
    isFavorite = isFavorite,
    artworkUri = artworkPath?.let { Uri.parse(it) }
)

fun AlbumEntity.toDomain(): Album = Album(
    id = id,
    name = name,
    artist = artist,
    artistId = artistId,
    year = year,
    songCount = songCount,
    artworkUri = artworkPath?.let { Uri.parse(it) }
)

fun ArtistEntity.toDomain(): Artist = Artist(
    id = id,
    name = name,
    albumCount = albumCount,
    songCount = songCount
)

fun PlaylistEntity.toDomain(songCount: Int = 0): Playlist = Playlist(
    id = id,
    name = name,
    songCount = songCount,
    type = when (type) {
        "RECENTLY_ADDED" -> PlaylistType.RECENTLY_ADDED
        "MOST_PLAYED" -> PlaylistType.MOST_PLAYED
        "FAVORITES" -> PlaylistType.FAVORITES
        else -> PlaylistType.USER
    }
)
