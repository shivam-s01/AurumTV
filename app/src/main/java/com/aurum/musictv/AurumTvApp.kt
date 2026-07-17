package com.aurum.musictv

import android.app.Application
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache

/**
 * Configures Coil's global ImageLoader with hard caps tuned for 1GB-RAM
 * Android TV boxes. Coil's defaults size the memory cache off the
 * device's total RAM (up to 25% of it) — on a phone that's reasonable,
 * but on a 1GB box that's ~250MB just for image bitmaps, which starves
 * the video/audio pipeline and every other allocation. We cap it hard
 * instead of trusting the percentage-of-RAM default.
 */
class AurumTvApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val imageLoader = ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    // Hard cap, not a percentage of device RAM. ~24MB is
                    // enough for a couple of screens' worth of 300x300
                    // downsampled album art without ever approaching the
                    // point where the OS starts killing background work.
                    .maxSizeBytes(24 * 1024 * 1024)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("aurum_image_cache"))
                    // Small disk budget too — 8GB total storage means
                    // every MB spent on a disk image cache is a MB not
                    // available for the OS, other apps, or future song
                    // caching. 40MB is plenty for TV-card thumbnails.
                    .maxSizeBytes(40 * 1024 * 1024)
                    .build()
            }
            // Bitmaps are already downsampled to card size at load time
            // (see SongCardPresenter), so RGB_565 halves per-pixel memory
            // vs the ARGB_8888 default with no visible quality loss for
            // small TV thumbnails.
            .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
            .build()

        Coil.setImageLoader(imageLoader)
    }
}
