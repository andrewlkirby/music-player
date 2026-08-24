package com.musicplayer.service

import javax.inject.Inject
import javax.inject.Singleton

// In-process handoff for PlayerViewModel.shufflePlay()/playSongs() to hand the
// service a large ordered song-ID list without putting it in a Binder Bundle.
// MusicPlaybackService and the app UI run in the same process (no
// android:process on the service in AndroidManifest.xml), so a shared
// @Singleton is enough — CUSTOM_COMMAND_SET_QUEUE just signals "load what's
// staged here" instead of carrying the list itself over the session's Binder
// transport.
@Singleton
class PendingQueueHolder @Inject constructor() {
    @Volatile
    var pendingIds: List<Long>? = null
}
