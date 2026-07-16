package com.aurum.musictv.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.aurum.musictv.ui.player.PlayerActivity

/**
 * TV equivalent of AurumMediaSessionService.kt from the phone app. Same
 * low-RAM buffer philosophy as AurumAudioEngine.kt (see its comments on
 * the 2026-07-07 stall fix) — target hardware here is WORSE (1GB RAM TV
 * boxes vs. mid-range phones), so buffers are trimmed even further.
 */
@UnstableApi
class AurumTvPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        // Deliberately smaller than the phone app's profile: 1GB-RAM TV
        // boxes can't spare the same headroom. 15s min / 30s max buffer is
        // enough to survive typical home-network jitter without holding
        // multiple megabytes of decoded audio in memory.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 30_000,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 3_000,
            )
            .setTargetBufferBytes(2 * 1024 * 1024) // 2 MiB ceiling, audio-only
            .build()

        val player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build()

        val sessionActivityIntent = Intent(this, PlayerActivity::class.java)
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this, 0, sessionActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
