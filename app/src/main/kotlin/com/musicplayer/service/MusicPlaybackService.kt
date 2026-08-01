package com.musicplayer.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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

    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        const val CUSTOM_COMMAND_TOGGLE_SHUFFLE = "TOGGLE_SHUFFLE"
        const val CUSTOM_COMMAND_TOGGLE_REPEAT = "TOGGLE_REPEAT"
        const val CUSTOM_COMMAND_SET_SLEEP_TIMER = "SET_SLEEP_TIMER"
        const val EXTRA_SLEEP_TIMER_MINUTES = "SLEEP_TIMER_MINUTES"
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

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(activityIntent)
            .setCallback(MediaSessionCallback())
            .build()

        restorePlaybackState()
        setupPlayerListener()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun setupPlayerListener() {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                persistPlaybackState()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.mediaId?.toLongOrNull()?.let { songId ->
                    serviceScope.launch {
                        repository.incrementPlayCount(songId)
                    }
                }
                persistPlaybackState()
            }
        })
    }

    private fun persistPlaybackState() {
        serviceScope.launch {
            val currentMediaItem = player.currentMediaItem
            val songId = currentMediaItem?.mediaId?.toLongOrNull()
            val queue = (0 until player.mediaItemCount).map { i ->
                player.getMediaItemAt(i).mediaId.toLongOrNull() ?: 0L
            }

            repository.savePlaybackState(
                PlaybackStateEntity(
                    currentSongId = songId,
                    position = player.currentPosition,
                    shuffleEnabled = player.shuffleModeEnabled,
                    repeatMode = when (player.repeatMode) {
                        Player.REPEAT_MODE_ONE -> "ONE"
                        Player.REPEAT_MODE_ALL -> "ALL"
                        else -> "OFF"
                    },
                    queueJson = queue.joinToString(",", "[", "]"),
                    currentQueueIndex = player.currentMediaItemIndex
                )
            )
        }
    }

    private fun restorePlaybackState() {
        serviceScope.launch {
            val state = repository.getPlaybackState() ?: return@launch
            val songIds = state.queueJson
                .trim('[', ']')
                .split(",")
                .mapNotNull { it.trim().toLongOrNull() }

            if (songIds.isEmpty()) return@launch

            val songs = songIds.mapNotNull { repository.getSongById(it) }
            val mediaItems = songs.map { it.toMediaItem() }

            player.setMediaItems(mediaItems, state.currentQueueIndex, state.position)
            player.shuffleModeEnabled = state.shuffleEnabled
            player.repeatMode = when (state.repeatMode) {
                "ONE" -> Player.REPEAT_MODE_ONE
                "ALL" -> Player.REPEAT_MODE_ALL
                else -> Player.REPEAT_MODE_OFF
            }
            player.prepare()
            // Don't autoplay on restore
        }
    }

    inner class MediaSessionCallback : MediaSession.Callback {
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            return when (customCommand.customAction) {
                CUSTOM_COMMAND_TOGGLE_SHUFFLE -> {
                    player.shuffleModeEnabled = !player.shuffleModeEnabled
                    persistPlaybackState()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CUSTOM_COMMAND_TOGGLE_REPEAT -> {
                    player.repeatMode = when (player.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                    persistPlaybackState()
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
        .setMediaMetadata(metadata)
        .build()
}
