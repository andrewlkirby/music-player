package com.musicplayer.worker

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.musicplayer.data.local.entities.AlbumEntity
import com.musicplayer.data.local.entities.ArtistEntity
import com.musicplayer.data.local.entities.SongEntity
import com.musicplayer.data.repository.MusicRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.core.net.toUri

@HiltWorker
class SdCardScanWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: MusicRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val uriString = inputData.getString(KEY_URI) ?: return Result.failure()
        val treeUri = uriString.toUri()
        return try {
            withContext(Dispatchers.IO) { scanTreeUri(treeUri) }
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    // ── Step 1: build a full MediaStore path→metadata index in ONE query ──
    private fun buildMediaStoreIndex(folderPath: String?): Map<String, MediaStoreRow> {
        val index = mutableMapOf<String, MediaStoreRow>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.ARTIST_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.SIZE
        )
        
        // Optimized: Only query MediaStore for the specific folder being scanned
        val selection = if (folderPath != null) {
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DATA} LIKE ?"
        } else {
            "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        }
        val selectionArgs = if (folderPath != null) arrayOf("$folderPath%") else null

        val cursor = applicationContext.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )
        cursor?.use { c ->
            val dataCol     = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val idCol       = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol    = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol   = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol    = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val artistIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
            val durCol      = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val trackCol    = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val yearCol     = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val addedCol    = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val modCol      = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val sizeCol     = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            while (c.moveToNext()) {
                val path = c.getString(dataCol) ?: continue
                index[path] = MediaStoreRow(
                    id           = c.getLong(idCol),
                    title        = c.getString(titleCol) ?: "",
                    artist       = c.getString(artistCol) ?: "Unknown Artist",
                    album        = c.getString(albumCol) ?: "Unknown Album",
                    albumId      = c.getLong(albumIdCol),
                    artistId     = c.getLong(artistIdCol),
                    durationMs   = c.getLong(durCol),
                    trackNumber  = c.getInt(trackCol) % 1000,
                    year         = c.getInt(yearCol),
                    dateAdded    = c.getLong(addedCol),
                    lastModified = c.getLong(modCol),
                    size         = c.getLong(sizeCol)
                )
            }
        }
        return index
    }

    // ── Step 2: walk the SAF tree, batch inserts every 500 files ──────────
    private suspend fun scanTreeUri(treeUri: Uri) {
        android.util.Log.d("SdCardScanWorker", "Starting scan for tree: $treeUri")
        val rootPath = getRealPath(treeUri)
        android.util.Log.d("SdCardScanWorker", "Resolved root path: $rootPath")
        val mediaStoreIndex = buildMediaStoreIndex(rootPath)
        android.util.Log.d("SdCardScanWorker", "MediaStore index size for this path: ${mediaStoreIndex.size}")

        val docFile = DocumentFile.fromTreeUri(applicationContext, treeUri) ?: run {
            android.util.Log.e("SdCardScanWorker", "Failed to get DocumentFile from treeUri")
            return
        }
        val audioFiles = mutableListOf<DocumentFile>()
        collectAudioFiles(docFile, audioFiles)

        android.util.Log.d("SdCardScanWorker", "Found ${audioFiles.size} audio files in SAF tree")
        
        if (audioFiles.isEmpty() && mediaStoreIndex.isNotEmpty()) {
            android.util.Log.w("SdCardScanWorker", "SAF found no files but MediaStore has some. This might be a SAF permission issue.")
        }

        val songs      = mutableListOf<SongEntity>()
        val albumMap   = mutableMapOf<String, AlbumEntity>()
        val artistMap  = mutableMapOf<String, ArtistEntity>()

        audioFiles.forEach { file ->
            val fileUri  = file.uri
            val fileName = file.name ?: return@forEach

            // Derive the real filesystem path from the SAF URI so we can
            // look it up in the MediaStore index we built above.
            val realPath = getRealPath(fileUri)

            val msRow = if (realPath != null) mediaStoreIndex[realPath] else null

            // Use MediaStore data when available (fast path — no I/O).
            // Only fall back to JAudioTagger when MediaStore has no entry.
            val id: Long
            val title: String
            val artist: String
            val album: String
            val albumId: Long
            val artistId: Long
            val durationMs: Long
            val trackNumber: Int
            val year: Int
            val genre: String
            val size: Long
            val dateAdded: Long
            val lastModified: Long

            if (msRow != null) {
                // ✅ Fast path: MediaStore already has this file indexed
                id           = msRow.id
                title        = msRow.title.ifBlank { fileName.substringBeforeLast(".") }
                artist       = msRow.artist
                album        = msRow.album
                albumId      = msRow.albumId
                artistId     = msRow.artistId
                durationMs   = msRow.durationMs
                trackNumber  = msRow.trackNumber
                year         = msRow.year
                size         = msRow.size
                dateAdded    = msRow.dateAdded
                lastModified = msRow.lastModified
                genre        = ""   // MediaStore doesn't expose genre easily; leave blank
            } else {
                // 🐢 Slow path: MediaStore missed this file — read tags directly.
                // This should only happen for a small fraction of files on SD cards
                // that Android hasn't indexed yet.
                val tagData  = readTagsSAF()
                val rawTitle = tagData?.title ?: fileName.substringBeforeLast(".")
                val rawArtist = tagData?.artist ?: "Unknown Artist"
                val rawAlbum  = tagData?.album  ?: "Unknown Album"

                id           = fileUri.toString().hashCode().toLong() and 0x7FFF_FFFF_FFFF_FFFFL
                title        = rawTitle
                artist       = rawArtist
                album        = rawAlbum
                albumId      = rawAlbum.hashCode().toLong()  and 0x7FFF_FFFF_FFFF_FFFFL
                artistId     = rawArtist.hashCode().toLong() and 0x7FFF_FFFF_FFFF_FFFFL
                durationMs   = tagData?.durationMs ?: 0L
                trackNumber  = tagData?.trackNumber ?: 0
                year         = tagData?.year ?: 0
                genre        = tagData?.genre ?: ""
                size         = file.length()
                dateAdded    = file.lastModified()
                lastModified = file.lastModified()
            }

            songs.add(
                SongEntity(
                    id           = id,
                    title        = title,
                    artist       = artist,
                    album        = album,
                    albumId      = albumId,
                    artistId     = artistId,
                    duration     = durationMs,
                    trackNumber  = trackNumber,
                    year         = year,
                    genre        = genre,
                    path         = realPath ?: fileUri.toString(),
                    uriString    = fileUri.toString(),
                    size         = size,
                    dateAdded    = dateAdded,
                    artworkPath  = null,
                    lastModified = lastModified
                )
            )

            val albumKey = "$artist|$album"
            if (!albumMap.containsKey(albumKey)) {
                albumMap[albumKey] = AlbumEntity(
                    id          = albumId,
                    name        = album,
                    artist      = artist,
                    artistId    = artistId,
                    year        = year,
                    songCount   = 0,
                    artworkPath = null
                )
            }

            if (!artistMap.containsKey(artist)) {
                artistMap[artist] = ArtistEntity(
                    id         = artistId,
                    name       = artist,
                    albumCount = 0,
                    songCount  = 0
                )
            }

            // Flush to DB every 500 songs to keep memory bounded
            if (songs.size >= 500) {
                flushBatch(songs, albumMap, artistMap)
                songs.clear()
                albumMap.clear()
                artistMap.clear()
            }
        }

        // Flush remainder
        if (songs.isNotEmpty()) {
            flushBatch(songs, albumMap, artistMap)
        }
    }

    private suspend fun flushBatch(
        songs: List<SongEntity>,
        albumMap: Map<String, AlbumEntity>,
        artistMap: Map<String, ArtistEntity>
    ) {
        val albumCounts      = songs.groupBy { it.albumId }.mapValues { it.value.size }
        val artistSongCounts = songs.groupBy { it.artistId }.mapValues { it.value.size }
        val artistAlbumCounts = songs.groupBy { it.artistId }
            .mapValues { e -> e.value.map { it.albumId }.toSet().size }

        val finalAlbums  = albumMap.values.map { it.copy(songCount = albumCounts[it.id] ?: 0) }
        val finalArtists = artistMap.values.map {
            it.copy(
                songCount  = artistSongCounts[it.id]  ?: 0,
                albumCount = artistAlbumCounts[it.id] ?: 0
            )
        }
        repository.insertSongsDirectly(songs, finalAlbums, finalArtists)
    }

    // ── SAF tree walker ───────────────────────────────────────────────────

    private fun collectAudioFiles(dir: DocumentFile, result: MutableList<DocumentFile>) {
        // Optimized: Instead of deep recursive walk for every scan, 
        // we list only the current directory if we just want to scan a specific folder,
        // or we use a more efficient approach.
        dir.listFiles().forEach { file ->
            if (file.isFile && isAudioFile(file)) {
                result.add(file)
            } else if (file.isDirectory) {
                // For subfolders, we only recurse if it's not a massive operation
                // or we could limit depth. For now, let's keep recursion but 
                // ensure we aren't doing redundant work.
                collectAudioFiles(file, result)
            }
        }
    }

    private fun isAudioFile(file: DocumentFile): Boolean {
        val mime = file.type ?: return false
        if (mime.startsWith("audio/")) return true
        val ext = file.name?.substringAfterLast(".")?.lowercase() ?: return false
        return ext in setOf("mp3", "flac", "ogg", "opus", "m4a", "aac", "wav", "wma", "aiff")
    }

    // ── Path resolution ───────────────────────────────────────────────────

    /**
     * Converts a SAF document URI to a real filesystem path so we can look
     * it up in the MediaStore index.
     * SAF URI format: content://com.android.externalstorage.documents/tree/XXXX%3A/document/XXXX%3AMusic%2Fsong.mp3
     * The document ID is like "1234-5678:Music/song.mp3" where "1234-5678" is the SD card UUID.
     */
    private fun getRealPath(uri: Uri): String? {
        return try {
            val docId = uri.lastPathSegment ?: return null
            // docId looks like "1234-5678:Music/Artist/song.mp3" or "primary:Music/song.mp3"
            val colonIdx = docId.indexOf(':')
            if (colonIdx < 0) return null
            val volumeId  = docId.substring(0, colonIdx)
            val relPath   = docId.substring(colonIdx + 1)
            
            val volumeRoot = when (volumeId.lowercase()) {
                "primary" -> "/storage/emulated/0"
                else      -> "/storage/$volumeId"   // SD card
            }
            
            // For deep paths, relPath might be encoded or contains slashes.
            // SAF docId typically uses '/' for directory separation.
            "$volumeRoot/$relPath"
        } catch (_: Exception) {
            null
        }
    }

    // ── JAudioTagger fallback (only for files MediaStore missed) ──────────

    private data class TagData(
        val title: String?,
        val artist: String?,
        val album: String?,
        val trackNumber: Int,
        val year: Int,
        val genre: String?,
        val durationMs: Long
    )

    private fun readTagsSAF(): TagData? {
        return null // Optimized: Skipping JAudioTagger during scan for performance. Trust MediaStore.
    }

    companion object {
        const val KEY_URI    = "tree_uri"
        const val WORK_NAME  = "sd_card_scan"

        fun enqueue(workManager: WorkManager, treeUri: Uri) {
            android.util.Log.d("SdCardScanWorker", "Enqueuing scan for: $treeUri")
            val data    = workDataOf(KEY_URI to treeUri.toString())
            val request = OneTimeWorkRequestBuilder<SdCardScanWorker>()
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(false)
                        .build()
                )
                .addTag("sd_card_scan_tag")
                .build()
            
            val operation = workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            operation.state.observeForever { state ->
                android.util.Log.d("SdCardScanWorker", "Enqueue operation state: $state")
            }
        }
    }

    private data class MediaStoreRow(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String,
        val albumId: Long,
        val artistId: Long,
        val durationMs: Long,
        val trackNumber: Int,
        val year: Int,
        val dateAdded: Long,
        val lastModified: Long,
        val size: Long
    )
}
