package com.musicplayer.presentation

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.musicplayer.data.repository.MusicRepository
import com.musicplayer.domain.model.RepeatMode
import com.musicplayer.domain.model.Song
import com.musicplayer.service.MusicPlaybackService
import com.musicplayer.service.toMediaItem
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class PlayerUiState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val position: Long = 0L,
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
    private val repository: MusicRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var controller: MediaController? = null
    private val queueSongs = mutableListOf<Song>()

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
                val index = controller?.currentMediaItemIndex ?: 0
                resolveCurrentSong(mediaItem, index)
                _uiState.update { state ->
                    state.copy(
                        currentQueueIndex = index,
                        duration = controller?.duration?.coerceAtLeast(0L) ?: 0L
                    )
                }
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                _uiState.update { it.copy(shuffleEnabled = shuffleModeEnabled) }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                _uiState.update {
                    it.copy(
                        repeatMode = when (repeatMode) {
                            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                            else -> RepeatMode.OFF
                        }
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

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                // The controller's own initial-state sync can race the
                // service's async restorePlaybackState() — syncState()'s
                // resolveFullQueue() call may see an empty timeline and
                // give up before restoration finishes. This fires whenever
                // the timeline actually changes (including that restore
                // completing), giving queue resolution another chance.
                controller?.let { resolveFullQueue(it) }
            }
        })
    }

    private fun syncState() {
        val ctrl = controller ?: return
        val index = ctrl.currentMediaItemIndex
        _uiState.update { state ->
            state.copy(
                isPlaying = ctrl.isPlaying,
                shuffleEnabled = ctrl.shuffleModeEnabled,
                repeatMode = when (ctrl.repeatMode) {
                    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                    else -> RepeatMode.OFF
                },
                duration = ctrl.duration.coerceAtLeast(0L),
                currentQueueIndex = index
            )
        }
        // On a fresh app start, the service may have already restored a
        // queue (MusicPlaybackService.restorePlaybackState) before this
        // controller finished connecting — the transition event that would
        // normally set currentSong/queue already fired and was missed, so
        // the mini player would otherwise stay empty until the next real
        // track change. Resolve both directly from the controller's
        // already-established state instead of waiting for another event.
        resolveCurrentSong(ctrl.currentMediaItem, index)
        resolveFullQueue(ctrl)
    }

    // Resolves the current song by mediaId rather than trusting queueSongs[index]:
    // queueSongs is only populated by this session's own playSongs()/addToQueue()
    // calls, so right after a cold start (queue restored service-side) or if it's
    // otherwise out of sync with the controller, indexing into it is unreliable.
    private fun resolveCurrentSong(mediaItem: MediaItem?, index: Int) {
        val id = mediaItem?.mediaId?.toLongOrNull()
        if (id == null) {
            _uiState.update { it.copy(currentSong = null) }
            return
        }
        val cached = queueSongs.getOrNull(index)?.takeIf { it.id == id }
        if (cached != null) {
            _uiState.update { it.copy(currentSong = cached) }
            return
        }
        viewModelScope.launch {
            val song = repository.getSongById(id)
            _uiState.update { it.copy(currentSong = song) }
        }
    }

    // Resolves the full queue (e.g. for the "up next" list) after a cold
    // start, when queueSongs is empty because this session never called
    // playSongs() itself. Skips entirely once a session-built queue exists.
    private fun resolveFullQueue(ctrl: MediaController) {
        if (queueSongs.isNotEmpty()) return
        viewModelScope.launch {
            val ids = (0 until ctrl.mediaItemCount).mapNotNull { i ->
                ctrl.getMediaItemAt(i).mediaId.toLongOrNull()
            }
            if (ids.isEmpty()) return@launch
            val songs = repository.getSongsByIds(ids)
            if (queueSongs.isNotEmpty()) return@launch // a real playSongs() won the race
            queueSongs.addAll(songs)
            _uiState.update { it.copy(queue = songs) }
        }
    }

    private fun startPositionUpdater() {
        viewModelScope.launch {
            uiState.map { it.isPlaying }.distinctUntilChanged().collectLatest { isPlaying ->
                if (!isPlaying) return@collectLatest
                while (true) {
                    delay(500)
                    val pos = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L
                    _uiState.update { it.copy(position = pos) }
                }
            }
        }
    }

    // ── Public controls ───────────────────────────────────────────────────

    fun playSongs(songs: List<Song>, startIndex: Int = 0) {
        queueSongs.clear()
        queueSongs.addAll(songs)
        // Update the UI (mini player, now-playing) immediately rather than
        // waiting on the mapping/controller work below, so tapping a song
        // reads as instant even while the queue is still being built.
        _uiState.update { state ->
            state.copy(
                queue = songs,
                currentQueueIndex = startIndex,
                currentSong = songs.getOrNull(startIndex)
            )
        }
        viewModelScope.launch {
            // Building a MediaItem per song (metadata + two Uri.parse calls
            // each) for a 30k-song queue was done synchronously on Main,
            // visibly freezing the UI when starting playback from a large
            // list. Off-main here; only the controller calls need Main.
            val mediaItems = withContext(Dispatchers.Default) { songs.map { it.toMediaItem() } }
            controller?.setMediaItems(mediaItems, startIndex, 0)
            controller?.prepare()
            controller?.play()
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

    fun seekTo(positionMs: Long) { controller?.seekTo(positionMs) }

    fun toggleShuffle() {
        controller?.sendCustomCommand(
            SessionCommand(MusicPlaybackService.CUSTOM_COMMAND_TOGGLE_SHUFFLE, Bundle.EMPTY),
            Bundle.EMPTY
        )
    }

    fun toggleRepeat() {
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

    fun addToQueue(song: Song) {
        queueSongs.add(song)
        controller?.addMediaItem(song.toMediaItem())
        _uiState.update { it.copy(queue = queueSongs.toList()) }
    }

    fun playNext(song: Song) {
        val nextIndex = (controller?.currentMediaItemIndex ?: 0) + 1
        queueSongs.add(nextIndex.coerceAtMost(queueSongs.size), song)
        controller?.addMediaItem(nextIndex, song.toMediaItem())
        _uiState.update { it.copy(queue = queueSongs.toList()) }
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
