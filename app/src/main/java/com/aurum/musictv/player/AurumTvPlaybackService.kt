package com.aurum.musictv.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.aurum.musictv.MainActivity
import com.aurum.musictv.settings.AppSettings
import com.aurum.musictv.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * TV equivalent of AurumMediaSessionService.kt / AurumAudioEngine.kt from
 * the phone app, with the same network + buffer settings that make
 * YouTube (googlevideo.com) streams play reliably there.
 */
@UnstableApi
class AurumTvPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    // Live settings -> DSP. Settings changes (EQ, bass, volume boost, skip
    // silence) are applied to the CURRENT song the instant they change.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val fx = AudioFx()
    private var exo: ExoPlayer? = null
    private var latest = AppSettings()

    private fun syncFx() {
        val p = exo ?: return
        fx.apply(p.audioSessionId, latest)
    }

    override fun onCreate() {
        super.onCreate()

        // Duration-based buffering only (no byte ceiling — a byte cap makes
        // DefaultLoadControl stop filling early; same fix as the phone's
        // AurumAudioEngine). Start playing after 1.5 s of audio for a fast
        // first sound; after a stall wait 3 s so it doesn't stutter.
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

        // Phone app's exact HTTP setup: googlevideo.com serves most
        // consistently to a real browser-style User-Agent (an unusual
        // custom UA is what gets some CDN requests throttled/rejected), and
        // 20 s timeouts leave room for slow Wi-Fi without killing a stream
        // that is merely slow. Wrapped in DefaultDataSource so non-http
        // schemes still resolve.
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
            )
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)

        val mediaSourceFactory = DefaultMediaSourceFactory(
            DefaultDataSource.Factory(this, httpDataSourceFactory)
        )

        // Declare this as music so the TV's audio routing/focus treat it
        // correctly (HDMI/ARC, ducking for system sounds, pausing for
        // other media apps).
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setHandleAudioBecomingNoisy(true)
            .build()

        exo = player
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) = syncFx()
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = syncFx()
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) syncFx()
            }
        })
        scope.launch {
            SettingsStore.observe(applicationContext).collect { s ->
                latest = s
                player.setSkipSilenceEnabled(s.skipSilence)
                syncFx()
            }
        }

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
        scope.cancel()
        fx.release()
        exo = null
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
