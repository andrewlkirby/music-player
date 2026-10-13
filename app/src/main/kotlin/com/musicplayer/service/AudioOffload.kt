package com.musicplayer.service

import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences

// Hardware audio offload hands decoding to the phone's low-power audio DSP so
// the CPU can sleep through long stretches of playback — a large battery win
// for screen-off listening. Audio effects on the player's session (the
// Equalizer) don't apply to an offloaded stream, so EqualizerViewModel turns
// offload off while the EQ is enabled and back on when it's disabled/released.
// Gapless support is deliberately NOT required: the SM-G781V supports MP3
// offload but not gapless offload, so requiring it disabled offload entirely
// (verified on-device). The cost is a possible small gap between MP3 tracks;
// formats the DSP can't take (FLAC on this phone) fall back to normal, gapless
// playback automatically.
fun Player.setAudioOffloadEnabled(enabled: Boolean) {
    val prefs = AudioOffloadPreferences.Builder()
        .setAudioOffloadMode(
            if (enabled) AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
            else AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
        )
        .setIsGaplessSupportRequired(false)
        .build()
    trackSelectionParameters = trackSelectionParameters.buildUpon()
        .setAudioOffloadPreferences(prefs)
        .build()
}
