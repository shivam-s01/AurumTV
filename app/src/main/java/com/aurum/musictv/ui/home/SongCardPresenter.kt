package com.aurum.musictv.ui.home

import android.view.ViewGroup
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import coil.load
import com.aurum.musictv.data.Song

/**
 * Renders one Song as a focusable Leanback card. ImageCardView already
 * gives us the TV-standard focus scale/elevation animation for free — no
 * custom animation code needed, unlike the phone app's hand-rolled spring
 * physics (aurum_pressable.dart). TV remotes need visible, instant focus
 * feedback, not spring bounce.
 */
class SongCardPresenter : Presenter() {

    companion object {
        private const val CARD_WIDTH = 300
        private const val CARD_HEIGHT = 300
    }

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)
        }
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val song = item as Song
        val cardView = viewHolder.view as ImageCardView
        cardView.titleText = song.title
        cardView.contentText = song.artist
        cardView.setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)
        cardView.mainImageView.load(song.albumArtUrl) {
            crossfade(true)
            // Downsample to card size — critical on 1GB RAM boxes to avoid
            // holding full-resolution decoded bitmaps for offscreen cards.
            size(CARD_WIDTH, CARD_HEIGHT)
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        cardView.mainImageView.load(null as String?) // cancel in-flight request
        cardView.badgeImage = null
        cardView.mainImage = null
    }
}
