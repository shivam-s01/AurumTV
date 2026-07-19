package com.aurum.musictv.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.aurum.musictv.MainActivity

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

        // FIX (2026-07-19): setTargetBufferBytes(2 MiB) here was the exact
        // same bug already fixed once on the phone app
        // (AurumAudioEngine.kt: setTargetBufferBytes(1MB) -> -1). A byte
        // ceiling makes DefaultLoadControl stop filling the buffer once
        // that many bytes load, even if bufferForPlaybackMs hasn't been
        // reached yet. Passing -1 disables the byte ceiling so the
        // duration-based bounds below are the only limits (matches
        // ExoPlayer's own default and the phone app's fix).
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 30_000,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 3_000,
            )
            .setTargetBufferBytes(-1)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // FIX: MediaItem.fromUri() (PlayerManager.playIndex) used
        // ExoPlayer's default HttpDataSource — no explicit User-Agent,
        // short timeouts. Worker/CDN links can cold-start slowly and some
        // CDNs reject requests with no User-Agent, causing silent connect
        // failures. Explicit factory fixes both.
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Aurum-TV/1.0 (Linux;Android) ExoPlayerLib/1.4.0")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpDataSourceFactory)

        val player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            // TV boxes often sit connected via ethernet or WiFi with the
            // screen technically always "on" (no lockscreen the way a
            // phone has), but this still matters for CPU wake locks during
            // network buffering — same reasoning as the phone app's
            // AurumAudioEngine wake-lock handling, just simpler since TV
            // has no doze-mode battery restrictions to fight.
            .setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
            .setHandleAudioBecomingNoisy(true)
            .build()

        val sessionActivityIntent = Intent(this, MainActivity::class.java)
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
