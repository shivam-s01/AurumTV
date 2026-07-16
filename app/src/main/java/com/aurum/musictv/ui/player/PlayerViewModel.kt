package com.aurum.musictv.ui.player

import android.content.ComponentName
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.aurum.musictv.data.AurumApi
import com.aurum.musictv.data.Song
import com.aurum.musictv.player.AurumTvPlaybackService
import com.google.common.util.concurrent.ListenableFuture

@UnstableApi
class PlayerViewModel : ViewModel() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    fun bindSession(context: Context) {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, AurumTvPlaybackService::class.java)
        )
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            controller = controllerFuture?.get()
        }, context.mainExecutor)
    }

    suspend fun play(song: Song) {
        val streamUrl = AurumApi.resolveStreamUrl(song) ?: return
        val mediaItem = MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaId(song.id)
            .build()
        controller?.apply {
            setMediaItem(mediaItem)
            prepare()
            play()
        }
    }

    fun unbindSession() {
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}
