package com.musicplayer.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.musicplayer.data.local.entities.PlaybackStateEntity
import com.musicplayer.data.repository.MusicRepository
import com.musicplayer.domain.model.Song
import com.musicplayer.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class MusicPlaybackService : MediaSessionService() {

    @Inject lateinit var player: ExoPlayer
    @Inject lateinit var repository: MusicRepository
    @Inject lateinit var queueHolder: PendingQueueHolder

    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var progressPersistJob: Job? = null
    private var periodicPersistJob: Job? = null
    private var fullStatePersistJob: Job? = null

    // ── Virtual queue ────────────────────────────────────────────────────
    // The full ordered list of song IDs for "what's playing and in what order."
    // Only a bounded WINDOW of this is ever loaded into the real ExoPlayer at
    // once — see docs/PLAN_windowed_playback_queue.md. Handing ExoPlayer/
    // MediaSession the *entire* library as a live Timeline was the root cause of
    // an ~800-1000ms main-thread stall on every skip during full-library shuffle
    // (Media3's legacy MediaSessionCompat queue republish scales with total
    // loaded item count); windowing keeps what's live small regardless of
    // library size.
    private var virtualQueueIds: List<Long> = emptyList()
    private var virtualQueueShuffled: Boolean = false
    // Virtual index of whatever is currently loaded at the player's local index 0.
    private var windowStartOffset: Int = 0
    // REPEAT_ALL can't be native Player state here: it would loop the loaded
    // WINDOW, not the full virtual queue. Tracked app-side; wrapping to index 0
    // happens explicitly in onPlaybackStateChanged(STATE_ENDED).
    private var repeatAllEnabled: Boolean = false
    // Single job for every queue-affecting operation (restore, a fresh
    // shufflePlay/playSongs, a window shift, the toggle-shuffle reshuffle) — each
    // cancels whatever's still running before starting, so a fast-follow action
    // can't race a still-filling previous one (this exact race previously
    // corrupted the queue to far more items than the library actually has).
    private var queueLoadJob: Job? = null

    companion object {
        const val CUSTOM_COMMAND_TOGGLE_SHUFFLE = "TOGGLE_SHUFFLE"
        const val CUSTOM_COMMAND_TOGGLE_REPEAT = "TOGGLE_REPEAT"
        const val CUSTOM_COMMAND_SET_SLEEP_TIMER = "SET_SLEEP_TIMER"
        const val CUSTOM_COMMAND_SET_QUEUE = "SET_QUEUE"
        const val CUSTOM_COMMAND_ADD_TO_QUEUE = "ADD_TO_QUEUE"
        const val CUSTOM_COMMAND_PLAY_NEXT = "PLAY_NEXT"
        const val EXTRA_SLEEP_TIMER_MINUTES = "SLEEP_TIMER_MINUTES"
        const val EXTRA_QUEUE_START_INDEX = "QUEUE_START_INDEX"
        const val EXTRA_QUEUE_SHUFFLED = "QUEUE_SHUFFLED"
        const val EXTRA_SONG_ID = "SONG_ID"
        private const val PROGRESS_PERSIST_DEBOUNCE_MS = 500L
        private const val PERIODIC_PERSIST_INTERVAL_MS = 10_000L
        private const val FULL_STATE_PERSIST_DEBOUNCE_MS = 500L
        // Matches PlayerViewModel.SHUFFLE_FILL_CHUNK_SIZE's tuned value: on a
        // 34k-song queue, larger chunks (fewer total addMediaItems() calls)
        // measurably reduced BOTH total fill time and total dropped frames,
        // not just individual-stall size — each call's cost scales with the
        // *current* queue size, so more chunks means more compounding cost,
        // not less. No Binder/IPC here (player is the local ExoPlayer
        // instance, unlike a MediaController call), so this could safely go
        // even higher, but reuses the same tested value. With windowing, each
        // load/shift only ever touches a few hundred items at most anyway.
        private const val RESTORE_FILL_CHUNK_SIZE = 5000
        // How many virtual-queue items to keep loaded behind/ahead of the
        // current position. Validated on the real 34,954-song library: window
        // fills in well under 1s (vs. ~8s to fill the whole library
        // pre-windowing), and skip-next — including rapid repeated taps —
        // produced zero Choreographer/Davey jank in testing, so unlike
        // SHUFFLE_FILL_CHUNK_SIZE these starting values needed no further
        // empirical tuning.
        private const val WINDOW_BEHIND = 20
        private const val WINDOW_AHEAD = 150
        // Extend the window once fewer than this many loaded items remain in
        // the direction of travel.
        private const val SHIFT_TRIGGER_REMAINING = 30
        private const val EXTEND_BATCH = 100
    }

    override fun onCreate() {
        super.onCreate()
        val activityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, WindowedTransportPlayer(player))
            .setSessionActivity(activityIntent)
            .setCallback(MediaSessionCallback())
            .build()

        restorePlaybackState()
        setupPlayerListener()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        // Flush any pending debounced progress write synchronously before the
        // player/session are released, so a skip immediately followed by the
        // app/service being killed doesn't lose the last position/index.
        progressPersistJob?.cancel()
        periodicPersistJob?.cancel()
        fullStatePersistJob?.cancel()
        queueLoadJob?.cancel()
        runBlocking { persistProgress() }
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    // Virtual index (position within virtualQueueIds) of the currently playing
    // item — NOT the same as player.currentMediaItemIndex, which is local to
    // whatever's loaded in the window right now.
    private fun virtualIndex(): Int = windowStartOffset + player.currentMediaItemIndex

    private fun setupPlayerListener() {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                schedulePersistProgress()
                if (isPlaying) startPeriodicPersist() else periodicPersistJob?.cancel()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.mediaId?.toLongOrNull()?.let { songId ->
                    serviceScope.launch {
                        repository.incrementPlayCount(songId)
                    }
                }
                schedulePersistProgress()
                maybeShiftWindow()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // REPEAT_ALL wrap-around: the real player's repeatMode is never
                // set to ALL (would only loop the loaded window), so at the true
                // end of the virtual queue it reaches STATE_ENDED normally —
                // restart from the top ourselves when that mode is active.
                if (playbackState == Player.STATE_ENDED && repeatAllEnabled && virtualQueueIds.isNotEmpty()) {
                    loadWindowAround(virtualQueueIds, 0, 0L, virtualQueueShuffled, autoplay = true)
                }
            }
        })
    }

    // Rebuilds and writes the full playback_state row, including queueJson.
    // Building queueJson from virtualQueueIds (already in memory) instead of
    // walking player.currentTimeline means this is cheap regardless of library
    // size — only called when virtualQueueIds itself changes (a fresh queue
    // load), not on every window shift or track transition.
    private fun persistFullPlaybackState() {
        progressPersistJob?.cancel()
        fullStatePersistJob?.cancel()
        fullStatePersistJob = serviceScope.launch {
            delay(FULL_STATE_PERSIST_DEBOUNCE_MS)
            val songId = player.currentMediaItem?.mediaId?.toLongOrNull()
            val position = player.currentPosition
            val shuffleEnabled = virtualQueueShuffled
            val repeatMode = repeatModeString()
            val currentQueueIndex = virtualIndex()
            val ids = virtualQueueIds

            val queueJson = withContext(Dispatchers.Default) {
                buildString(ids.size + 2) {
                    append('[')
                    ids.forEachIndexed { i, id ->
                        if (i > 0) append(',')
                        append(id)
                    }
                    append(']')
                }
            }

            repository.savePlaybackState(
                PlaybackStateEntity(
                    currentSongId = songId,
                    position = position,
                    shuffleEnabled = shuffleEnabled,
                    repeatMode = repeatMode,
                    queueJson = queueJson,
                    currentQueueIndex = currentQueueIndex
                )
            )
        }
    }

    // Debounces progress-only writes so a burst of rapid skips (or the
    // isPlaying + transition events that fire together on every track
    // change) collapse into a single DB write instead of one each.
    private fun schedulePersistProgress() {
        progressPersistJob?.cancel()
        progressPersistJob = serviceScope.launch {
            delay(PROGRESS_PERSIST_DEBOUNCE_MS)
            persistProgress()
        }
    }

    // Keeps the saved position within ~PERIODIC_PERSIST_INTERVAL_MS of reality
    // while a track plays steadily, so a hard process kill (no clean onDestroy)
    // doesn't lose more than a few seconds of progress. Runs only while playing.
    private fun startPeriodicPersist() {
        periodicPersistJob?.cancel()
        periodicPersistJob = serviceScope.launch {
            while (true) {
                delay(PERIODIC_PERSIST_INTERVAL_MS)
                persistProgress()
            }
        }
    }

    private suspend fun persistProgress() {
        repository.updatePlaybackProgress(
            currentSongId = player.currentMediaItem?.mediaId?.toLongOrNull(),
            position = player.currentPosition,
            shuffleEnabled = virtualQueueShuffled,
            repeatMode = repeatModeString(),
            currentQueueIndex = virtualIndex()
        )
    }

    private fun repeatModeString(): String = when {
        repeatAllEnabled -> "ALL"
        player.repeatMode == Player.REPEAT_MODE_ONE -> "ONE"
        else -> "OFF"
    }

    private fun restorePlaybackState() {
        queueLoadJob = serviceScope.launch {
            val state = repository.getPlaybackState() ?: return@launch
            val songIds = state.queueJson
                .trim('[', ']')
                .split(",")
                .mapNotNull { it.trim().toLongOrNull() }
            if (songIds.isEmpty()) return@launch

            repeatAllEnabled = state.repeatMode == "ALL"
            player.repeatMode = if (state.repeatMode == "ONE") Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF

            loadWindowInline(
                ids = songIds,
                targetIndex = state.currentQueueIndex,
                startPositionMs = state.position,
                shuffled = state.shuffleEnabled,
                autoplay = true // auto-resume where the user left off
            )
        }
    }

    // Cancels any in-flight queue operation and loads a bounded window of
    // MediaItems around targetIndex from ids, replacing the player's entire
    // current queue. The one entry point for "the virtual queue is now this" —
    // used by restore, a fresh shufflePlay/playSongs, the toggle-shuffle
    // reshuffle, REPEAT_ALL wrap, and the skip-past-window safety net.
    private fun loadWindowAround(
        ids: List<Long>,
        targetIndex: Int,
        startPositionMs: Long,
        shuffled: Boolean,
        autoplay: Boolean
    ) {
        queueLoadJob?.cancel()
        if (ids.isEmpty()) return
        queueLoadJob = serviceScope.launch {
            loadWindowInline(ids, targetIndex, startPositionMs, shuffled, autoplay)
        }
    }

    private suspend fun loadWindowInline(
        ids: List<Long>,
        targetIndex: Int,
        startPositionMs: Long,
        shuffled: Boolean,
        autoplay: Boolean
    ) {
        virtualQueueIds = ids
        virtualQueueShuffled = shuffled
        val clampedTarget = targetIndex.coerceIn(0, ids.size - 1)

        // Resolve and start just the currently-playing track first, so playback
        // resumes/starts immediately regardless of the virtual queue's size —
        // handing ExoPlayer a single-item setMediaItems() instead of a whole
        // window up front avoids the allocation/GC burst a large setMediaItems()
        // call causes. The rest of the window is filled in around this item
        // below, preserving order and the current index.
        val currentId = ids[clampedTarget]
        val currentSong = repository.getSongById(currentId) ?: return
        player.setMediaItems(listOf(currentSong.toMediaItem()), 0, startPositionMs)
        // Native shuffle is never used for windowed queues — virtualQueueIds
        // already encodes final playback order (shuffled or not); a second,
        // independent shuffle over just the loaded window would make "next"
        // jump inconsistently and break "previous"/history.
        player.shuffleModeEnabled = false
        player.prepare()
        if (autoplay) player.play()
        windowStartOffset = clampedTarget

        val windowStart = (clampedTarget - WINDOW_BEHIND).coerceAtLeast(0)
        val windowEnd = (clampedTarget + WINDOW_AHEAD).coerceAtMost(ids.size - 1)
        val beforeIds = ids.subList(windowStart, clampedTarget)
        val afterIds = ids.subList(clampedTarget + 1, windowEnd + 1)

        // Order-preserving batch lookups (chunked IN queries) instead of one
        // getSongById round-trip per song. Each chunk's withContext is a
        // coroutine suspension point, so cancelling queueLoadJob (see
        // onSetMediaItems below, or a fresh loadWindowAround call) stops this
        // loop before its next addMediaItems() call rather than racing a
        // newly-started queue.
        if (beforeIds.isNotEmpty()) {
            val beforeSongs = repository.getSongsByIds(beforeIds)
            var insertAt = 0
            beforeSongs.chunked(RESTORE_FILL_CHUNK_SIZE).forEach { chunk ->
                val items = withContext(Dispatchers.Default) { chunk.map { it.toMediaItem() } }
                player.addMediaItems(insertAt, items)
                insertAt += items.size
            }
            windowStartOffset = windowStart
        }
        if (afterIds.isNotEmpty()) {
            val afterSongs = repository.getSongsByIds(afterIds)
            afterSongs.chunked(RESTORE_FILL_CHUNK_SIZE).forEach { chunk ->
                val items = withContext(Dispatchers.Default) { chunk.map { it.toMediaItem() } }
                player.addMediaItems(items) // appended at end
            }
        }
        persistFullPlaybackState()
    }

    // Inserts songId into the virtual queue — either right after the current
    // song (playNext) or at the very end (add to queue) — and, if that
    // position falls within the currently loaded window, adds it to the real
    // player queue too so it's audible without waiting for a window shift.
    // Deliberately does NOT cancel/go through queueLoadJob: this is additive,
    // not a queue replacement, so a concurrent restore/shufflePlay/window-shift
    // can keep running — the queueLoadJob?.isActive check below just skips the
    // live player insert (virtualQueueIds already has it, so it's picked up
    // whenever the window next loads/shifts) rather than risking a stale index
    // racing whatever that job is doing to the player's queue mid-flight.
    private fun addSongIdToQueue(songId: Long, playNext: Boolean) {
        serviceScope.launch {
            val song = repository.getSongById(songId) ?: return@launch
            if (virtualQueueIds.isEmpty()) {
                loadWindowAround(listOf(songId), 0, 0L, shuffled = false, autoplay = false)
                return@launch
            }
            // Everything from here runs synchronously (no suspension) until the
            // player.addMediaItem() call, so this insert can't itself race a
            // concurrent operation's player mutations mid-way through.
            val insertAt = if (playNext) {
                (virtualIndex() + 1).coerceAtMost(virtualQueueIds.size)
            } else {
                virtualQueueIds.size
            }
            virtualQueueIds = virtualQueueIds.subList(0, insertAt) +
                songId +
                virtualQueueIds.subList(insertAt, virtualQueueIds.size)

            val windowEndV = windowStartOffset + player.mediaItemCount - 1
            if (queueLoadJob?.isActive != true && insertAt in windowStartOffset..(windowEndV + 1)) {
                player.addMediaItem(insertAt - windowStartOffset, song.toMediaItem())
            }
            persistFullPlaybackState()
        }
    }

    // Called on every real track transition. Extends the loaded window once
    // few enough items remain in the direction of travel, and trims the far
    // side to keep the window bounded regardless of how long a session runs.
    private fun maybeShiftWindow() {
        if (virtualQueueIds.isEmpty()) return
        if (queueLoadJob?.isActive == true) return
        val vIndex = virtualIndex()
        val windowEndV = windowStartOffset + player.mediaItemCount - 1
        val remainingAhead = windowEndV - vIndex
        val remainingBehind = vIndex - windowStartOffset
        if (remainingAhead < SHIFT_TRIGGER_REMAINING && vIndex + 1 < virtualQueueIds.size) {
            extendAhead(windowEndV)
        } else if (remainingBehind < SHIFT_TRIGGER_REMAINING / 2 && windowStartOffset > 0) {
            extendBehind()
        }
    }

    private fun extendAhead(windowEndV: Int) {
        val extendFrom = windowEndV + 1
        val extendTo = (extendFrom + EXTEND_BATCH - 1).coerceAtMost(virtualQueueIds.size - 1)
        if (extendFrom > extendTo) return
        val idsToAdd = virtualQueueIds.subList(extendFrom, extendTo + 1)
        queueLoadJob = serviceScope.launch {
            val songs = repository.getSongsByIds(idsToAdd)
            songs.chunked(RESTORE_FILL_CHUNK_SIZE).forEach { chunk ->
                val items = withContext(Dispatchers.Default) { chunk.map { it.toMediaItem() } }
                player.addMediaItems(items)
            }
            // Trim from the front to keep the window bounded.
            val currentVIndex = virtualIndex()
            val excessBehind = (currentVIndex - windowStartOffset) - WINDOW_BEHIND
            if (excessBehind > 0) {
                player.removeMediaItems(0, excessBehind)
                windowStartOffset += excessBehind
            }
        }
    }

    private fun extendBehind() {
        val extendTo = windowStartOffset - 1
        val extendFrom = (extendTo - EXTEND_BATCH + 1).coerceAtLeast(0)
        if (extendFrom > extendTo) return
        val idsToAdd = virtualQueueIds.subList(extendFrom, extendTo + 1)
        queueLoadJob = serviceScope.launch {
            val songs = repository.getSongsByIds(idsToAdd)
            var insertAt = 0
            songs.chunked(RESTORE_FILL_CHUNK_SIZE).forEach { chunk ->
                val items = withContext(Dispatchers.Default) { chunk.map { it.toMediaItem() } }
                player.addMediaItems(insertAt, items)
                insertAt += items.size
            }
            windowStartOffset = extendFrom
            // Trim from the end to keep the window bounded.
            val currentVIndex = virtualIndex()
            val windowEndV = windowStartOffset + player.mediaItemCount - 1
            val excessAhead = windowEndV - (currentVIndex + WINDOW_AHEAD)
            if (excessAhead > 0) {
                val newCount = player.mediaItemCount - excessAhead
                player.removeMediaItems(newCount, player.mediaItemCount)
            }
        }
    }

    // Wraps the real ExoPlayer for the MediaSession only (the raw `player` field
    // is still used directly everywhere else in this class). hasNext/hasPrevious
    // reflect the VIRTUAL queue's boundaries, not just the loaded window, so
    // transport controls (notification/Bluetooth/lockscreen) don't look
    // incorrectly disabled near a window edge. seekToNext/PreviousMediaItem add a
    // safety net for the rare case where a user skips faster than the
    // proactive background extend (triggered well before the window's true
    // edge) can keep up.
    private inner class WindowedTransportPlayer(player: Player) : ForwardingPlayer(player) {
        override fun hasNextMediaItem(): Boolean {
            if (super.hasNextMediaItem()) return true
            return virtualIndex() + 1 < virtualQueueIds.size
        }

        override fun hasPreviousMediaItem(): Boolean {
            if (super.hasPreviousMediaItem()) return true
            return virtualIndex() > 0
        }

        override fun seekToNextMediaItem() {
            if (super.hasNextMediaItem()) {
                super.seekToNextMediaItem()
                return
            }
            val nextVIndex = virtualIndex() + 1
            if (nextVIndex < virtualQueueIds.size) {
                loadWindowAround(virtualQueueIds, nextVIndex, 0L, virtualQueueShuffled, autoplay = true)
            }
        }

        override fun seekToPreviousMediaItem() {
            if (super.hasPreviousMediaItem()) {
                super.seekToPreviousMediaItem()
                return
            }
            val prevVIndex = virtualIndex() - 1
            if (prevVIndex >= 0) {
                loadWindowAround(virtualQueueIds, prevVIndex, 0L, virtualQueueShuffled, autoplay = true)
            }
        }
    }

    inner class MediaSessionCallback : MediaSession.Callback {
        // Defensive: cancel any in-flight queue operation if some controller
        // ever calls setMediaItems() directly (PlayerViewModel no longer does —
        // it goes through CUSTOM_COMMAND_SET_QUEUE — but this guards against any
        // other client, e.g. Android Auto/Assistant, doing so directly).
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            queueLoadJob?.cancel()
            return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            return when (customCommand.customAction) {
                CUSTOM_COMMAND_SET_QUEUE -> {
                    val ids = queueHolder.pendingIds
                    queueHolder.pendingIds = null
                    if (ids != null) {
                        val startIndex = args.getInt(EXTRA_QUEUE_START_INDEX, 0)
                        val shuffled = args.getBoolean(EXTRA_QUEUE_SHUFFLED, false)
                        loadWindowAround(ids, startIndex, 0L, shuffled, autoplay = true)
                    }
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CUSTOM_COMMAND_TOGGLE_SHUFFLE -> {
                    if (!virtualQueueShuffled) {
                        // Reshuffle the unplayed tail only — history + current
                        // song stay put, matching what a listener would expect
                        // from turning shuffle on mid-playback.
                        val vIndex = virtualIndex()
                        val head = virtualQueueIds.subList(0, (vIndex + 1).coerceAtMost(virtualQueueIds.size))
                        val tail = virtualQueueIds.subList(head.size, virtualQueueIds.size).shuffled()
                        loadWindowAround(head + tail, vIndex, player.currentPosition, true, autoplay = player.isPlaying)
                    } else {
                        // Turning shuffle off just stops future reshuffling —
                        // the already-established order is left as-is.
                        virtualQueueShuffled = false
                        persistFullPlaybackState()
                    }
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CUSTOM_COMMAND_TOGGLE_REPEAT -> {
                    when {
                        !repeatAllEnabled && player.repeatMode == Player.REPEAT_MODE_OFF -> repeatAllEnabled = true
                        repeatAllEnabled -> {
                            repeatAllEnabled = false
                            player.repeatMode = Player.REPEAT_MODE_ONE
                        }
                        else -> player.repeatMode = Player.REPEAT_MODE_OFF
                    }
                    schedulePersistProgress()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CUSTOM_COMMAND_ADD_TO_QUEUE -> {
                    val songId = args.getLong(EXTRA_SONG_ID, -1L)
                    if (songId >= 0) addSongIdToQueue(songId, playNext = false)
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CUSTOM_COMMAND_PLAY_NEXT -> {
                    val songId = args.getLong(EXTRA_SONG_ID, -1L)
                    if (songId >= 0) addSongIdToQueue(songId, playNext = true)
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CUSTOM_COMMAND_SET_SLEEP_TIMER -> {
                    val minutes = args.getInt(EXTRA_SLEEP_TIMER_MINUTES, 0)
                    if (minutes > 0) {
                        serviceScope.launch {
                            delay(minutes * 60_000L)
                            player.pause()
                        }
                    }
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                else -> super.onCustomCommand(session, controller, customCommand, args)
            }
        }

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(CUSTOM_COMMAND_TOGGLE_SHUFFLE, Bundle.EMPTY))
                .add(SessionCommand(CUSTOM_COMMAND_TOGGLE_REPEAT, Bundle.EMPTY))
                .add(SessionCommand(CUSTOM_COMMAND_SET_SLEEP_TIMER, Bundle.EMPTY))
                .add(SessionCommand(CUSTOM_COMMAND_SET_QUEUE, Bundle.EMPTY))
                .add(SessionCommand(CUSTOM_COMMAND_ADD_TO_QUEUE, Bundle.EMPTY))
                .add(SessionCommand(CUSTOM_COMMAND_PLAY_NEXT, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.accept(
                sessionCommands,
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
            )
        }
    }
}

fun Song.toMediaItem(): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setTrackNumber(trackNumber)
        .setRecordingYear(year)
        .setArtworkUri(artworkUri)
        .build()

    return MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMimeType(mimeTypeForPath(path))
        .setMediaMetadata(metadata)
        .build()
}

// Without an explicit mime type, ExoPlayer's DefaultMediaSourceFactory infers
// content type from the URI's extension and, for manifest-like extensions
// (.m3u8/.mpd/.ism), reflectively loads a HlsMediaSource/DashMediaSource/
// SsMediaSource factory class. This app only depends on media3-exoplayer
// (core), not the HLS/DASH/SmoothStreaming extension artifacts, so that
// reflective load throws ClassNotFoundException — synchronously, for the
// whole setMediaItems() batch, not just the one bad item. Setting a real
// audio mime type here (whenever we can tell one from the extension) keeps
// content-type inference on the safe "other/progressive" path.
private fun mimeTypeForPath(path: String): String? = when (path.substringAfterLast('.', "").lowercase()) {
    "mp3" -> MimeTypes.AUDIO_MPEG
    "flac" -> MimeTypes.AUDIO_FLAC
    "ogg" -> MimeTypes.AUDIO_OGG
    "opus" -> MimeTypes.AUDIO_OPUS
    "m4a" -> MimeTypes.AUDIO_MP4
    "aac" -> MimeTypes.AUDIO_AAC
    "wav" -> MimeTypes.AUDIO_WAV
    "wma" -> "audio/x-ms-wma"
    "aiff", "aif" -> "audio/x-aiff"
    else -> null
}
