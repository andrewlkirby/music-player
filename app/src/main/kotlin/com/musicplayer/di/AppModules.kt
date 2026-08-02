package com.musicplayer.di

import android.content.Context
import android.media.AudioManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.musicplayer.data.local.MusicDatabase
import com.musicplayer.data.local.dao.*
import com.musicplayer.data.repository.MusicRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MusicDatabase =
        Room.databaseBuilder(context, MusicDatabase::class.java, "music_player.db")
            .fallbackToDestructiveMigration()
            // Room defaults to WAL + synchronous=FULL, which fsyncs on every
            // commit. NORMAL still fsyncs at WAL checkpoints and is safe
            // against app/process crashes with WAL — it only trades away
            // durability of the last commit(s) on an OS crash/power loss,
            // which is an acceptable tradeoff for a rebuildable music index.
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    db.execSQL("PRAGMA synchronous=NORMAL")
                }
            })
            .build()

    @Provides
    fun provideSongDao(db: MusicDatabase): SongDao = db.songDao()

    @Provides
    fun provideAlbumDao(db: MusicDatabase): AlbumDao = db.albumDao()

    @Provides
    fun provideArtistDao(db: MusicDatabase): ArtistDao = db.artistDao()

    @Provides
    fun providePlaylistDao(db: MusicDatabase): PlaylistDao = db.playlistDao()

    @Provides
    fun providePlaybackStateDao(db: MusicDatabase): PlaybackStateDao = db.playbackStateDao()

    @Provides
    @Singleton
    fun provideMusicRepository(
        @ApplicationContext context: Context,
        db: MusicDatabase
    ): MusicRepository = MusicRepository(context, db)
}

@Module
@InstallIn(SingletonComponent::class)
object PlayerModule {

    @Provides
    @Singleton
    fun provideExoPlayer(@ApplicationContext context: Context): ExoPlayer {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val player = ExoPlayer.Builder(context)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Explicitly assign a real audio session id up front rather than
        // relying on ExoPlayer to lazily generate one once playback starts.
        // Equalizer(priority, audioSessionId=0) attaches to the global output
        // mix, which most device audio HALs reject outright — the Equalizer
        // screen needs a session tied to this player to work at all.
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        player.audioSessionId = audioManager.generateAudioSessionId()

        return player
    }
}
