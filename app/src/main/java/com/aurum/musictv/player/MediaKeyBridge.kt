package com.aurum.musictv.player

/**
 * Link between the playback service (which owns the system media session)
 * and PlayerManager (which owns the queue). The session's player only ever
 * holds ONE media item, so without this a remote's Next/Previous key or the
 * notification's skip buttons would do nothing.
 */
object MediaKeyBridge {
    @Volatile var onNext: (() -> Unit)? = null
    @Volatile var onPrevious: (() -> Unit)? = null
}
