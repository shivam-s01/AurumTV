package com.aurum.musictv.data.remote

import android.util.Log
import com.aurum.musictv.settings.AudioQuality
import io.github.shalva97.initNewPipe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream as NpAudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod

/** A playable audio URL plus what we know about it. */
data class ResolvedStream(
    val url: String,
    val bitrateKbps: Int,
    val mimeType: String,
)

/**
 * Turns a YouTube video id into a playable audio URL — the TV twin of the
 * phone app's HybridStreamResolver / YoutubeInnertube / YoutubeSaverResolver.
 *
 * WHY THIS EXISTS: TV used to hand ExoPlayer the Worker's /api/yt-proxy URL
 * for every YouTube song, i.e. all audio bytes were relayed through a
 * Cloudflare Worker (cold starts, per-request CPU limits, an extra probe
 * request before every song). The phone app instead resolves the real
 * googlevideo.com URL ON the device with NewPipeExtractor and streams
 * straight from YouTube's CDN — that is why it is fast and reliable. This
 * does the same on TV.
 *
 * Strategy per song (all of it behind one in-memory URL cache):
 *   1. Native NewPipe extraction (fast common path, no Worker hop).
 *   2. If native is still running after [HEDGE_MS], or already failed, the
 *      Worker path (/api/yt-proxy, then /api/yt-stream) is raced against it
 *      and whichever delivers first wins. One slow or broken path can never
 *      stall playback.
 *
 * Quality tiers pick a real YouTube audio stream by bitrate, see [pick].
 */
object YoutubeStreamResolver {

    private const val TAG = "YtStreamResolver"

    // YouTube stream URLs stay valid for hours; 45 min is conservative. A
    // dead URL still recovers: PlayerManager calls invalidate() first.
    private const val URL_TTL_MS = 45L * 60L * 1000L

    // How long the native extractor gets to itself before the Worker path
    // is raced against it. On a healthy box native finishes well inside
    // this, so the Worker is almost never touched.
    private const val HEDGE_MS = 3_000L

    // Bitrate ceilings (kbps) per quality tier. YouTube's audio-only tiers
    // are roughly AAC ~48, Opus ~50, Opus ~70, AAC ~128, Opus ~130-160.
    private const val LOW_CEILING_KBPS = 64
    private const val NORMAL_CEILING_KBPS = 135

    // Independent scope on purpose: NewPipe's extraction is blocking and
    // not interruptible, so racing/prefetch work must not be tied to (and
    // must not hold up) the caller's coroutine.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Cached(val stream: ResolvedStream, val at: Long)

    private val cache = object : LinkedHashMap<String, Cached>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?): Boolean =
            size > 48
    }

    private val inFlight = HashMap<String, Deferred<ResolvedStream?>>()

    @Volatile
    private var initialized = false

    @Volatile
    var lastFailureReason: String = ""
        private set

    private fun key(videoId: String, quality: AudioQuality, preferAac: Boolean) =
        "$videoId|${quality.name}|${if (preferAac) "aac" else "any"}"

    private fun cacheGet(key: String): ResolvedStream? = synchronized(cache) {
        val e = cache[key] ?: return null
        if (System.currentTimeMillis() - e.at > URL_TTL_MS) {
            cache.remove(key)
            null
        } else {
            e.stream
        }
    }

    private fun cachePut(key: String, stream: ResolvedStream) = synchronized(cache) {
        cache[key] = Cached(stream, System.currentTimeMillis())
    }

    /** Drops every cached URL for [videoId] (all quality/codec variants). */
    fun invalidate(videoId: String) {
        synchronized(cache) {
            val prefix = "$videoId|"
            cache.keys.removeAll { it.startsWith(prefix) }
        }
    }

    fun clearCache() {
        synchronized(cache) { cache.clear() }
    }

    /**
     * Resolves [videoId] to a playable URL, or null if every path failed.
     *
     * @param preferAac pick an AAC (mp4a) stream instead of Opus — used as a
     *   recovery step when a TV box's decoder rejects Opus.
     * @param forceRefresh ignore + drop any cached URL (dead/expired link).
     */
    suspend fun resolve(
        videoId: String,
        quality: AudioQuality,
        preferAac: Boolean = false,
        forceRefresh: Boolean = false,
    ): ResolvedStream? {
        if (videoId.isBlank()) return null
        val k = key(videoId, quality, preferAac)
        if (forceRefresh) invalidate(videoId) else cacheGet(k)?.let { return it }

        // One extraction per (song, quality): a prefetch already running for
        // the next track is simply joined when the user skips to it.
        val deferred = synchronized(inFlight) {
            inFlight[k] ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    race(videoId, quality, preferAac)?.also { cachePut(k, it) }
                } finally {
                    synchronized(inFlight) { inFlight.remove(k) }
                }
            }.also {
                inFlight[k] = it
                it.start()
            }
        }
        return deferred.await()
    }

    /** Fire-and-forget: warms the cache for a song the user will likely
     *  play next, so skipping/auto-advancing starts instantly. */
    fun prefetch(videoId: String, quality: AudioQuality) {
        if (videoId.isBlank() || cacheGet(key(videoId, quality, false)) != null) return
        scope.launch {
            try {
                resolve(videoId, quality)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    // ── Race: native first, Worker hedge ────────────────────────────────

    private suspend fun race(
        videoId: String,
        quality: AudioQuality,
        preferAac: Boolean,
    ): ResolvedStream? {
        val winner = CompletableDeferred<ResolvedStream?>()

        val nativeJob = scope.launch {
            val r = resolveNative(videoId, quality, preferAac)
            if (r != null) winner.complete(r)
        }
        val workerJob = scope.launch {
            // Head start for native; but if native already failed, don't wait.
            withTimeoutOrNull(HEDGE_MS) { nativeJob.join() }
            if (winner.isCompleted) return@launch
            val r = resolveViaWorker(videoId)
            if (r != null) winner.complete(r)
        }
        // Both paths done and nobody won -> unresolvable.
        scope.launch {
            nativeJob.join()
            workerJob.join()
            winner.complete(null)
        }

        val result = winner.await()
        // Loser is no longer needed (Worker call is cancellable; a native
        // extraction already in progress just finishes in the background
        // and its result is simply ignored).
        nativeJob.cancel()
        workerJob.cancel()
        if (result == null) Log.w(TAG, "all resolve paths failed for $videoId: $lastFailureReason")
        return result
    }

    // ── Native (NewPipeExtractor) ───────────────────────────────────────

    internal fun ensureInit() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            if (NewPipe.getDownloader() == null) initNewPipe()
            initialized = true
        }
    }

    private suspend fun resolveNative(
        videoId: String,
        quality: AudioQuality,
        preferAac: Boolean,
    ): ResolvedStream? = withContext(Dispatchers.IO) {
        // YouTube's first page fetch sometimes fails transiently; a FRESH
        // extractor instance on a second try usually succeeds.
        for (attempt in 0..1) {
            try {
                return@withContext extractBlocking(videoId, quality, preferAac)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastFailureReason = "native $videoId ${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, lastFailureReason)
                if (attempt == 0) delay(400)
            }
        }
        null
    }

    private fun extractBlocking(
        videoId: String,
        quality: AudioQuality,
        preferAac: Boolean,
    ): ResolvedStream? {
        ensureInit()
        val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
        extractor.fetchPage()

        val usable = extractor.audioStreams.orEmpty().filter {
            it.averageBitrate > 0 && !it.content.isNullOrBlank()
        }
        if (usable.isEmpty()) {
            lastFailureReason = "native $videoId: no audio streams returned"
            return null
        }
        val best = pick(usable, quality, preferAac) ?: return null
        Log.i(TAG, "picked ${best.averageBitrate}kbps ${best.format?.mimeType} for $videoId ($quality)")
        return ResolvedStream(
            url = best.content,
            bitrateKbps = best.averageBitrate,
            mimeType = best.format?.mimeType ?: "",
        )
    }

    /**
     * Stream choice:
     *  1. progressive-HTTP streams only (what ExoPlayer plays directly);
     *  2. the ORIGINAL audio track (never an auto-dubbed language);
     *  3. optionally AAC only (decoder-recovery mode);
     *  4. the highest bitrate within the tier's ceiling, Opus winning ties;
     *  5. nothing under the ceiling -> the smallest stream available.
     */
    private fun pick(streams: List<NpAudioStream>, quality: AudioQuality, preferAac: Boolean): NpAudioStream? {
        val progressive = streams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .ifEmpty { streams }
        val original = progressive.filter { isOriginalTrack(it) }.ifEmpty { progressive }
        val pool = if (preferAac) original.filter { !isOpus(it) }.ifEmpty { original } else original

        val ceiling = when (quality) {
            AudioQuality.LOW -> LOW_CEILING_KBPS
            AudioQuality.NORMAL -> NORMAL_CEILING_KBPS
            AudioQuality.HIGH -> Int.MAX_VALUE
        }
        val within = pool.filter { it.averageBitrate <= ceiling }
        return if (within.isNotEmpty()) {
            within.maxWithOrNull(compareBy<NpAudioStream>({ it.averageBitrate }, { isOpus(it) }))
        } else {
            pool.minByOrNull { it.averageBitrate }
        }
    }

    private fun isOpus(s: NpAudioStream): Boolean {
        val mime = s.format?.mimeType?.lowercase() ?: return false
        return mime.contains("opus") || mime.contains("webm")
    }

    // audioTrackType only exists on newer extractor builds; read it
    // reflectively so this compiles regardless and "unknown" = original.
    private fun isOriginalTrack(s: NpAudioStream): Boolean = try {
        val v = s.javaClass.methods
            .firstOrNull { it.name == "getAudioTrackType" && it.parameterTypes.isEmpty() }
            ?.invoke(s)?.toString()
        v == null || v == "ORIGINAL"
    } catch (e: Throwable) {
        true
    }

    // ── Worker fallback ─────────────────────────────────────────────────

    /**
     * Same two-step chain as the phone app's Worker path: /api/yt-proxy
     * first (the Worker relays the bytes, so IP-bound googlevideo URLs
     * can't 403 on the TV), then /api/yt-stream with a device-side
     * liveness check (that URL was resolved from the Worker's IP).
     */
    private suspend fun resolveViaWorker(videoId: String): ResolvedStream? {
        val proxyUrl = "${AurumApi.WORKER}/api/yt-proxy?id=$videoId"
        val proxyOk = try {
            val probe = Request.Builder().url(proxyUrl).header("Range", "bytes=0-255").build()
            AurumApi.client.newCall(probe).await().use { it.isSuccessful }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (proxyOk) return ResolvedStream(proxyUrl, 0, "")

        val json = AurumApi.getJson("${AurumApi.WORKER}/api/yt-stream?id=$videoId")
        val url = json?.optString("url")?.takeIf { it.startsWith("http") } ?: run {
            lastFailureReason = "worker $videoId: no stream url"
            return null
        }
        val alive = try {
            val probe = Request.Builder().url(url).header("Range", "bytes=0-255").build()
            AurumApi.client.newCall(probe).await().use { it.isSuccessful }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        return if (alive) ResolvedStream(url, 0, "") else null
    }
}
