package com.musicplayer.presentation

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.musicplayer.data.repository.MusicRepository
import com.musicplayer.domain.model.RepeatMode
import com.musicplayer.domain.model.Song
import com.musicplayer.service.MusicPlaybackService
import com.musicplayer.service.PendingQueueHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerUiState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val duration: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val queue: List<Song> = emptyList(),
    val currentQueueIndex: Int = 0,
    val isConnected: Boolean = false
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MusicRepository,
    private val queueHolder: PendingQueueHolder
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    // Ticks every 500ms while playing (see startPositionUpdater). Kept out of
    // PlayerUiState so this high-frequency value doesn't fan out a
    // recomposition to every uiState collector (Scaffold, queue panel, etc.) —
    // only the composables that actually read this flow (the seek bar, mini
    // player progress) recompose on each tick.
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private var controller: MediaController? = null
    // The full ordered queue this session knows about — used for the "up next"
    // list and to resolve a transition's MediaItem to a Song without a DB round
    // trip. The service is the actual source of truth (it may hold a much
    // larger virtual queue than what's currently loaded into the player); this
    // is a session-local mirror for display purposes, built either directly by
    // playSongs()/shufflePlay() or, after a cold-start restore, by
    // hydratePersistedStateIfNeeded().
    private val queueSongs = mutableListOf<Song>()
    private var queueSongsById: Map<Long, Song> = emptyMap()
    // Position of each song within queueSongs — this IS the "virtual queue
    // index" surfaced to the UI (state.currentQueueIndex), which is NOT the
    // same as controller.currentMediaItemIndex once the service windows the
    // live queue (that index is only local to whatever's currently loaded).
    private var queueIndexById: Map<Long, Int> = emptyMap()
    private var queueResolveJob: Job? = null

    init {
        connectToService()
        startPositionUpdater()
    }

    private fun connectToService() {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, MusicPlaybackService::class.java)
        )
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        future.addListener({
            controller = future.get()
            setupControllerListener()
            _uiState.update { it.copy(isConnected = true) }
            syncState()
        }, MoreExecutors.directExecutor())
    }

    private fun setupControllerListener() {
        controller?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.update { it.copy(isPlaying = isPlaying) }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                resolveCurrentSong(mediaItem)
                _position.value = 0L
                val virtualIndex = mediaItem?.mediaId?.toLongOrNull()?.let { queueIndexById[it] }
                _uiState.update { state ->
                    state.copy(
                        currentQueueIndex = virtualIndex ?: state.currentQueueIndex,
                        duration = controller?.duration?.coerceAtLeast(0L) ?: 0L
                    )
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    _uiState.update {
                        it.copy(duration = controller?.duration?.coerceAtLeast(0L) ?: 0L)
                    }
                }
            }

            // shuffleEnabled/repeatMode are app-level concepts now, not native
            // Player state (the service never sets shuffleModeEnabled=true or
            // repeatMode=ALL — see MusicPlaybackService's windowed-queue design
            // notes), so there's no onShuffleModeEnabledChanged/
            // onRepeatModeChanged callback to listen for. Both are updated
            // optimistically by toggleShuffle()/toggleRepeat() and hydrated
            // from persisted state on connect (hydratePersistedStateIfNeeded).
        })
    }

    private fun syncState() {
        val ctrl = controller ?: return
        _position.value = ctrl.currentPosition.coerceAtLeast(0L)
        _uiState.update { state ->
            state.copy(
                isPlaying = ctrl.isPlaying,
                duration = ctrl.duration.coerceAtLeast(0L)
            )
        }
        // On a fresh app start, the service may have already restored a queue
        // (MusicPlaybackService.restorePlaybackState) before this controller
        // finished connecting — the transition event that would normally set
        // currentSong already fired and was missed, so the mini player would
        // otherwise stay empty until the next real track change. Resolve
        // directly from the controller's already-established current item.
        resolveCurrentSong(ctrl.currentMediaItem)
        hydratePersistedStateIfNeeded()
    }

    // Resolves the current song by mediaId via the id-keyed cache (built
    // whenever queueSongs is set — see setQueueSongs). Falls back to a DB
    // lookup on a cache miss (e.g. before hydratePersistedStateIfNeeded/a
    // session queue exists yet).
    private fun resolveCurrentSong(mediaItem: MediaItem?) {
        val id = mediaItem?.mediaId?.toLongOrNull()
        if (id == null) {
            _uiState.update { it.copy(currentSong = null) }
            return
        }
        val cached = queueSongsById[id]
        if (cached != null) {
            _uiState.update { it.copy(currentSong = cached) }
            return
        }
        viewModelScope.launch {
            val song = repository.getSongById(id)
            _uiState.update { it.copy(currentSong = song) }
        }
    }

    // Hydrates UI state a fresh connection can't observe directly from Player
    // callbacks: the full ordered queue (only the service's loaded WINDOW is
    // visible via the controller once windowed, not the whole virtual queue)
    // and shuffle/repeat (app-level now, not native Player state). Reads the
    // same persisted row MusicPlaybackService.restorePlaybackState() itself
    // restores from — no race to wait out, that row is already complete
    // before restore even starts reading it. Skipped once a session-driven
    // shufflePlay()/playSongs() call already established this state locally.
    private fun hydratePersistedStateIfNeeded() {
        if (queueSongs.isNotEmpty()) return
        if (queueResolveJob?.isActive == true) return
        queueResolveJob = viewModelScope.launch {
            val state = repository.getPlaybackState() ?: return@launch
            _uiState.update {
                it.copy(
                    shuffleEnabled = state.shuffleEnabled,
                    repeatMode = when (state.repeatMode) {
                        "ONE" -> RepeatMode.ONE
                        "ALL" -> RepeatMode.ALL
                        else -> RepeatMode.OFF
                    }
                )
            }
            val ids = state.queueJson.trim('[', ']').split(",").mapNotNull { it.trim().toLongOrNull() }
            if (ids.isEmpty()) return@launch
            val songs = repository.getSongsByIds(ids)
            if (queueSongs.isNotEmpty()) return@launch // a real playSongs()/shufflePlay() won the race
            setQueueSongs(songs)
            _uiState.update { it.copy(queue = songs, currentQueueIndex = state.currentQueueIndex) }
        }
    }

    private fun setQueueSongs(songs: List<Song>) {
        queueSongs.clear()
        queueSongs.addAll(songs)
        val byId = HashMap<Long, Song>(songs.size)
        val indexById = HashMap<Long, Int>(songs.size)
        songs.forEachIndexed { i, song ->
            byId[song.id] = song
            indexById[song.id] = i
        }
        queueSongsById = byId
        queueIndexById = indexById
    }

    private fun startPositionUpdater() {
        viewModelScope.launch {
            uiState.map { it.isPlaying }.distinctUntilChanged().collectLatest { isPlaying ->
                if (!isPlaying) return@collectLatest
                while (true) {
                    delay(500)
                    _position.value = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L
                }
            }
        }
    }

    // ── Public controls ───────────────────────────────────────────────────

    // Starts playback across the given ordered list from startIndex. Only the
    // song IDs + start index are sent to the service (via
    // CUSTOM_COMMAND_SET_QUEUE, payload staged in queueHolder) — the service
    // resolves and loads a bounded WINDOW of MediaItems around startIndex
    // itself, so this stays fast regardless of list size instead of building
    // (or worse, pushing over the controller) MediaItems for the whole list.
    fun playSongs(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        setQueueSongs(songs)
        // Update the UI (mini player, now-playing) immediately rather than
        // waiting on the service's window load, so tapping a song reads as
        // instant even before the service confirms anything.
        _uiState.update { state ->
            state.copy(
                queue = songs,
                currentQueueIndex = startIndex,
                currentSong = songs.getOrNull(startIndex),
                shuffleEnabled = false
            )
        }
        queueHolder.pendingIds = songs.map { it.id }
        val args = Bundle().apply {
            putInt(MusicPlaybackService.EXTRA_QUEUE_START_INDEX, startIndex)
            putBoolean(MusicPlaybackService.EXTRA_QUEUE_SHUFFLED, false)
        }
        controller?.sendCustomCommand(
            SessionCommand(MusicPlaybackService.CUSTOM_COMMAND_SET_QUEUE, args),
            args
        )
    }

    // Shuffles the whole given list once (a real full-library shuffle, unlike
    // just picking a random start song) and starts playback on it via the same
    // windowed CUSTOM_COMMAND_SET_QUEUE path as playSongs().
    fun shufflePlay(songs: List<Song>) {
        if (songs.isEmpty()) return
        val shuffled = songs.shuffled()
        setQueueSongs(shuffled)
        _uiState.update { state ->
            state.copy(
                queue = shuffled,
                currentQueueIndex = 0,
                currentSong = shuffled[0],
                shuffleEnabled = true
            )
        }
        queueHolder.pendingIds = shuffled.map { it.id }
        val args = Bundle().apply {
            putInt(MusicPlaybackService.EXTRA_QUEUE_START_INDEX, 0)
            putBoolean(MusicPlaybackService.EXTRA_QUEUE_SHUFFLED, true)
        }
        controller?.sendCustomCommand(
            SessionCommand(MusicPlaybackService.CUSTOM_COMMAND_SET_QUEUE, args),
            args
        )
    }

    // Fetches the shuffle candidates via the given suspend supplier and starts
    // shufflePlay(), all under viewModelScope. The Songs screen's fetch pulls
    // the whole (unsorted) library, which can take a moment — running it on a
    // Composable's rememberCoroutineScope would cancel it if the user
    // navigates away from Songs before it finishes, silently dropping the tap.
    // viewModelScope outlives that navigation.
    fun shufflePlayAll(fetchSongs: suspend () -> List<Song>) {
        viewModelScope.launch {
            shufflePlay(fetchSongs())
        }
    }

    fun togglePlayPause() {
        val ctrl = controller ?: return
        if (ctrl.isPlaying) ctrl.pause() else ctrl.play()
    }

    fun seekToNext() { controller?.seekToNextMediaItem() }
    fun seekToPrevious() {
        val ctrl = controller ?: return
        if (ctrl.currentPosition > 3000L) ctrl.seekTo(0L)
        else ctrl.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
        _position.value = positionMs
    }

    fun toggleShuffle() {
        // Optimistic update — see setupControllerListener's note on why there's
        // no Player.Listener callback to wait for instead.
        _uiState.update { it.copy(shuffleEnabled = !it.shuffleEnabled) }
        controller?.sendCustomCommand(
            SessionCommand(MusicPlaybackService.CUSTOM_COMMAND_TOGGLE_SHUFFLE, Bundle.EMPTY),
            Bundle.EMPTY
        )
    }

    fun toggleRepeat() {
        _uiState.update { state ->
            state.copy(
                repeatMode = when (state.repeatMode) {
                    RepeatMode.OFF -> RepeatMode.ALL
                    RepeatMode.ALL -> RepeatMode.ONE
                    RepeatMode.ONE -> RepeatMode.OFF
                }
            )
        }
        controller?.sendCustomCommand(
            SessionCommand(MusicPlaybackService.CUSTOM_COMMAND_TOGGLE_REPEAT, Bundle.EMPTY),
            Bundle.EMPTY
        )
    }

    fun setSleepTimer(minutes: Int) {
        val args = Bundle().apply { putInt(MusicPlaybackService.EXTRA_SLEEP_TIMER_MINUTES, minutes) }
        controller?.sendCustomCommand(
            SessionCommand(MusicPlaybackService.CUSTOM_COMMAND_SET_SLEEP_TIMER, args),
            args
        )
    }

    // Both route through the service (CUSTOM_COMMAND_ADD_TO_QUEUE/PLAY_NEXT)
    // rather than calling controller.addMediaItem() directly — the service
    // owns the virtual queue, and a direct controller add would only affect
    // whatever's in the loaded WINDOW, silently vanishing on the next window
    // shift/trim instead of actually landing in the full queue.
    fun addToQueue(song: Song) {
        if (queueSongs.isNotEmpty()) {
            val updated = queueSongs + song
            setQueueSongs(updated)
            _uiState.update { it.copy(queue = updated) }
        }
        val args = Bundle().apply { putLong(MusicPlaybackService.EXTRA_SONG_ID, song.id) }
        controller?.sendCustomCommand(
            SessionCommand(MusicPlaybackService.CUSTOM_COMMAND_ADD_TO_QUEUE, args),
            args
        )
    }

    fun playNext(song: Song) {
        if (queueSongs.isNotEmpty()) {
            val insertAt = (_uiState.value.currentQueueIndex + 1).coerceAtMost(queueSongs.size)
            val updated = queueSongs.toMutableList().apply { add(insertAt, song) }
            setQueueSongs(updated)
            _uiState.update { it.copy(queue = updated) }
        }
        val args = Bundle().apply { putLong(MusicPlaybackService.EXTRA_SONG_ID, song.id) }
        controller?.sendCustomCommand(
            SessionCommand(MusicPlaybackService.CUSTOM_COMMAND_PLAY_NEXT, args),
            args
        )
    }

    fun toggleFavorite(songId: Long, isFavorite: Boolean) {
        viewModelScope.launch {
            repository.setFavorite(songId, isFavorite)
            _uiState.update { state ->
                state.copy(
                    currentSong = state.currentSong?.takeIf { it.id == songId }
                        ?.copy(isFavorite = isFavorite) ?: state.currentSong
                )
            }
        }
    }

    override fun onCleared() {
        controller?.release()
        super.onCleared()
    }
}
