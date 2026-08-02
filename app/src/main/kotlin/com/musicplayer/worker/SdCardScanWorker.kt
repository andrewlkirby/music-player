package com.musicplayer.worker

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.musicplayer.data.local.entities.AlbumEntity
import com.musicplayer.data.local.entities.ArtistEntity
import com.musicplayer.data.local.entities.SongEntity
import com.musicplayer.data.local.entities.SongSource
import com.musicplayer.data.repository.MusicRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import androidx.core.net.toUri
import java.util.concurrent.atomic.AtomicInteger

@HiltWorker
class SdCardScanWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: MusicRepository
) : CoroutineWorker(appContext, workerParams) {

    private val foldersScanned = AtomicInteger(0)
    private val songsFound = AtomicInteger(0)

    override suspend fun doWork(): Result {
        val uriString = inputData.getString(KEY_URI) ?: return Result.failure()
        val treeUri = uriString.toUri()
        return try {
            withContext(Dispatchers.IO) {
                repository.deleteNonAudioPlaylistFiles()
                scanTreeUri(treeUri)
            }
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

    // ── Step 2: walk the SAF tree in parallel, batch inserts every 500 files ──
    private suspend fun scanTreeUri(treeUri: Uri): Unit = coroutineScope {
        setProgress(workDataOf(KEY_PHASE to PHASE_SCANNING))

        // Reports folders/songs-found counts while the tree walk is in
        // flight — we don't know the total up front, so this is the best
        // signal of liveness/progress until the walk completes.
        val progressTicker = launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                setProgress(
                    workDataOf(
                        KEY_PHASE to PHASE_SCANNING,
                        KEY_FOLDERS_SCANNED to foldersScanned.get(),
                        KEY_SONGS_FOUND to songsFound.get()
                    )
                )
            }
        }

        val rootPath = getRealPath(treeUri)
        val mediaStoreIndex = buildMediaStoreIndex(rootPath)

        val semaphore = Semaphore(SCAN_CONCURRENCY)
        val audioFiles = collectAudioFiles(treeUri, DocumentsContract.getTreeDocumentId(treeUri), semaphore)

        progressTicker.cancel()

        if (audioFiles.isEmpty() && mediaStoreIndex.isNotEmpty()) {
            android.util.Log.w("SdCardScanWorker", "SAF found no files but MediaStore has some. This might be a SAF permission issue.")
        }

        setProgress(
            workDataOf(
                KEY_PHASE to PHASE_SAVING,
                KEY_SONGS_TOTAL to audioFiles.size,
                KEY_SONGS_SAVED to 0
            )
        )

        val songs      = mutableListOf<SongEntity>()
        val albumMap   = mutableMapOf<String, AlbumEntity>()
        val artistMap  = mutableMapOf<String, ArtistEntity>()
        var songsSaved = 0

        audioFiles.forEach { file ->
            val fileUri  = file.uri
            val fileName = file.name

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

            val artworkPath: String?
            val source: String

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
                artworkPath  = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"), albumId
                ).toString()
                // Uses MediaStore's own _ID as the primary key, so a later
                // scanMediaStore() run will find (and correctly refresh or
                // prune) this exact row — safe to treat as MediaStore-sourced.
                source       = SongSource.MEDIASTORE
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
                size         = file.size
                dateAdded    = file.lastModified
                lastModified = file.lastModified
                // No MediaStore albumId on this path, so the legacy albumart
                // provider URI can't be built — leave art unset.
                artworkPath  = null
                // Synthetic id, unknown to MediaStore — scanMediaStore()'s
                // obsolete-cleanup must never consider this row for deletion.
                source       = SongSource.SAF
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
                    artworkPath  = artworkPath,
                    lastModified = lastModified,
                    source       = source
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
                    artworkPath = artworkPath
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

            // Flush to DB periodically — each flush is now one transaction
            // (see insertSongsDirectly), so a larger batch means fewer
            // commits without meaningfully increasing memory use.
            if (songs.size >= SAVE_BATCH_SIZE) {
                songsSaved += songs.size
                flushBatch(songs, albumMap, artistMap)
                songs.clear()
                albumMap.clear()
                artistMap.clear()
                setProgress(workDataOf(KEY_PHASE to PHASE_SAVING, KEY_SONGS_TOTAL to audioFiles.size, KEY_SONGS_SAVED to songsSaved))
            }
        }

        // Flush remainder
        if (songs.isNotEmpty()) {
            songsSaved += songs.size
            flushBatch(songs, albumMap, artistMap)
        }
        setProgress(workDataOf(KEY_PHASE to PHASE_SAVING, KEY_SONGS_TOTAL to audioFiles.size, KEY_SONGS_SAVED to songsSaved))
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
    //
    // DocumentFile.listFiles() only fetches child document IDs in bulk; every
    // subsequent access to .name/.type/.isDirectory/.length()/.lastModified()
    // on the returned DocumentFile is its own individual ContentResolver round
    // trip to the SD card's DocumentsProvider. For a library with tens of
    // thousands of files that was thousands of extra binder calls per file.
    // Querying DocumentsContract directly with a full column projection gets
    // every child's complete metadata for a directory in one query instead.
    private val childProjection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    )

    // Walks the tree with up to SCAN_CONCURRENCY directory queries in flight
    // at once. Each call returns its own list rather than appending to a
    // shared collection, so sibling directories can be walked concurrently
    // with no locking. The semaphore only gates the query itself — it's
    // released before recursing, so it bounds concurrent SAF round trips
    // without limiting how many directories can be *waiting* on one.
    private suspend fun collectAudioFiles(
        treeUri: Uri,
        parentDocumentId: String,
        semaphore: Semaphore
    ): List<SafEntry> {
        val (files, subDirIds) = semaphore.withPermit {
            queryChildren(treeUri, parentDocumentId)
        }
        foldersScanned.incrementAndGet()
        if (files.isNotEmpty()) songsFound.addAndGet(files.size)

        if (subDirIds.isEmpty()) return files

        return coroutineScope {
            val childLists = subDirIds.map { id -> async { collectAudioFiles(treeUri, id, semaphore) } }
            files + childLists.awaitAll().flatten()
        }
    }

    private fun queryChildren(treeUri: Uri, parentDocumentId: String): Pair<List<SafEntry>, List<String>> {
        val resolver = applicationContext.contentResolver
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val files = mutableListOf<SafEntry>()
        val subDirIds = mutableListOf<String>()

        resolver.query(childrenUri, childProjection, null, null, null)?.use { c ->
            val idCol   = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modCol  = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            while (c.moveToNext()) {
                val docId = c.getString(idCol) ?: continue
                val mime  = c.getString(mimeCol) ?: ""
                val name  = c.getString(nameCol) ?: continue

                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    subDirIds.add(docId)
                } else if (isAudioMime(mime, name)) {
                    files.add(
                        SafEntry(
                            uri          = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                            name         = name,
                            size         = c.getLong(sizeCol),
                            lastModified = c.getLong(modCol)
                        )
                    )
                }
            }
        }
        return files to subDirIds
    }

    // Android's MimeTypeMap reports playlist files (.m3u/.m3u8/.pls) as
    // "audio/x-mpegurl" etc. — they'd otherwise pass the mime.startsWith
    // ("audio/") check below and get scanned in as if they were songs, even
    // though they aren't playable media. Excluded regardless of reported
    // mime type.
    private val nonAudioExtensions = setOf("m3u", "m3u8", "pls", "cue")

    private fun isAudioMime(mime: String, name: String): Boolean {
        val ext = name.substringAfterLast(".", "").lowercase()
        if (ext in nonAudioExtensions) return false
        if (mime.startsWith("audio/")) return true
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
        const val KEY_URI = "tree_uri"
        const val TAG = "sd_card_scan_tag"
        private const val WORK_NAME_PREFIX = "sd_card_scan_"

        const val KEY_PHASE           = "phase"
        const val PHASE_SCANNING      = "scanning"
        const val PHASE_SAVING        = "saving"
        const val KEY_FOLDERS_SCANNED = "folders_scanned"
        const val KEY_SONGS_FOUND     = "songs_found"
        const val KEY_SONGS_SAVED     = "songs_saved"
        const val KEY_SONGS_TOTAL     = "songs_total"

        private const val SCAN_CONCURRENCY     = 6
        private const val PROGRESS_INTERVAL_MS = 300L
        private const val SAVE_BATCH_SIZE      = 2000

        fun enqueue(workManager: WorkManager, treeUri: Uri) {
            val data    = workDataOf(KEY_URI to treeUri.toString())
            val request = OneTimeWorkRequestBuilder<SdCardScanWorker>()
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(false)
                        .build()
                )
                .addTag(TAG)
                .build()

            // Unique per folder rather than per app: with a single shared
            // name, scanning several watched folders (e.g. via "Rescan All")
            // meant each new enqueue replaced — and silently cancelled —
            // whichever folder's scan was still running.
            val workName = WORK_NAME_PREFIX + treeUri.toString().hashCode()
            workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request)
        }
    }

    private data class SafEntry(
        val uri: Uri,
        val name: String,
        val size: Long,
        val lastModified: Long
    )

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
