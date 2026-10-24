package com.aurum.musictv.data.remote

import android.util.Log
import com.aurum.musictv.data.model.Song
import com.aurum.musictv.data.model.SongSource
import com.aurum.musictv.settings.AudioQuality
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One Home row as it arrives — rows are streamed in so the first one is
 *  on screen after ~1 search round-trip instead of after ALL of them. */
data class HomeRow(val index: Int, val title: String, val songs: List<Song>)

/**
 * TV-side port of the phone app's YouTube-first data layer (api_service.dart):
 * YouTube Music search through the Worker's /api/yt-music-search route, with
 * a native NewPipe search as a raced fallback, plus stream resolution via
 * [YoutubeStreamResolver]. Saavn is gone on purpose — the phone app has it
 * disabled too (_saavnDisabled = true), so every song plays from YouTube.
 *
 * Songs that arrive from the phone's cloud sync with a non-YouTube source
 * (old favorites/history saved while Saavn was active) are matched to their
 * YouTube equivalent by title + artist at play time, see [youtubeIdFor].
 */
object AurumApi {

    // Same Worker as the phone app (AppConstants.apiBase).
    internal const val WORKER = "https://aurum-worker.shivamsharma962122.workers.dev"

    // Home: 6 rows x 15 songs, 3 searches in flight at a time. Enough to
    // fill the screen, light enough for a 1 GB TV box.
    private const val HOME_ROW_COUNT = 6
    private const val HOME_ROW_SONGS = 15
    private const val HOME_CONCURRENCY = 3

    // Search: native NewPipe search is raced in if the Worker is slow.
    private const val SEARCH_HEDGE_MS = 2_500L
    private const val SEARCH_CACHE_TTL_MS = 5 * 60_000L

    /** Last raw diagnostic from a failed network call — surfaced by Home /
     *  Search error states so a failure is debuggable without logcat. */
    @Volatile
    var lastDiagnostic: String = ""
        private set

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Flaky TV-box Wi-Fi: fail fast on connect, bounded overall. Capped
    // dispatcher — a 1 GB box has no use for OkHttp's default 64 requests.
    internal val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(okhttp3.ConnectionPool(4, 60, TimeUnit.SECONDS))
        .dispatcher(
            okhttp3.Dispatcher().apply {
                maxRequests = 12
                maxRequestsPerHost = 12
            }
        )
        .build()

    // Search/browse calls: tight 5 s budget (same as the phone app) so a
    // slow Worker hands over to the native fallback quickly.
    private val fastClient: OkHttpClient = client.newBuilder()
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    internal suspend fun getJson(url: String, http: OkHttpClient = client): JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(url).build()
                http.newCall(req).await().use { resp ->
                    if (!resp.isSuccessful) {
                        lastDiagnostic = "HTTP ${resp.code} from ${url.substringBefore('?')}"
                        return@withContext null
                    }
                    val body = resp.body?.string()
                    if (body.isNullOrBlank()) {
                        lastDiagnostic = "Empty body from ${url.substringBefore('?')}"
                        return@withContext null
                    }
                    JSONObject(body)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastDiagnostic = "${e.javaClass.simpleName}: ${e.message}"
                null
            }
        }

    // ── Search ──────────────────────────────────────────────────────────

    /** YouTube Music search (Worker) raced against native NewPipe search.
     *  Results are cached briefly so re-typing a query is instant. */
    suspend fun search(query: String, limit: Int = 25): List<Song> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val cacheKey = "search:${q.lowercase()}:$limit"
        NetworkResilience.EdgeCache.get<List<Song>>(cacheKey)?.let { return it }

        val songs = raceSearch(q, limit).distinctBy { it.id }
        if (songs.isNotEmpty()) {
            NetworkResilience.EdgeCache.put(cacheKey, songs, ttlMs = SEARCH_CACHE_TTL_MS)
        }
        return songs
    }

    private suspend fun raceSearch(query: String, limit: Int): List<Song> {
        val winner = CompletableDeferred<List<Song>>()
        val primary = ioScope.launch {
            val r = searchYtMusic(query, limit)
            if (r.isNotEmpty()) winner.complete(r)
        }
        val secondary = ioScope.launch {
            // Give the Worker a head start; go native at once if it already failed.
            withTimeoutOrNull(SEARCH_HEDGE_MS) { primary.join() }
            if (winner.isCompleted) return@launch
            val r = searchNative(query, limit)
            if (r.isNotEmpty()) winner.complete(r)
        }
        ioScope.launch {
            primary.join()
            secondary.join()
            winner.complete(emptyList())
        }
        try {
            return winner.await()
        } finally {
            primary.cancel()
            secondary.cancel()
        }
    }

    /** Worker route the phone app uses as its primary search source. Shape:
     *  {success, data:{results:[{videoId,title,artist,album,image,duration}]}} */
    private suspend fun searchYtMusic(query: String, limit: Int): List<Song> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val json = getJson("$WORKER/api/yt-music-search?query=$encoded&limit=$limit", fastClient)
            ?: return emptyList()
        if (!json.optBoolean("success", false)) {
            lastDiagnostic = "yt-music-search success=false"
            return emptyList()
        }
        val arr = json.optJSONObject("data")?.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Song>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("videoId")
            val title = clean(o.optString("title"))
            if (id.isBlank() || title.isBlank()) continue
            out.add(
                Song(
                    id = id,
                    title = title,
                    artist = clean(o.optString("artist")).ifBlank { "Unknown Artist" },
                    albumArtUrl = thumb(o.optString("image")),
                    durationSec = o.optInt("duration", 0),
                    source = SongSource.YOUTUBE,
                )
            )
        }
        return out
    }

    /** Fallback: NewPipe's own YouTube search, no Worker involved. */
    private suspend fun searchNative(query: String, limit: Int): List<Song> =
        withContext(Dispatchers.IO) {
            try {
                YoutubeStreamResolver.ensureInit()
                val extractor = ServiceList.YouTube.getSearchExtractor(query)
                extractor.fetchPage()
                val songs = extractor.initialPage.items
                    .filterIsInstance<StreamInfoItem>()
                    .mapNotNull { item ->
                        val id = videoIdOf(item.url)
                        if (id.isBlank()) return@mapNotNull null
                        Song(
                            id = id,
                            title = clean(item.name ?: ""),
                            artist = clean(item.uploaderName ?: "").removeSuffix(" - Topic")
                                .ifBlank { "Unknown Artist" },
                            albumArtUrl = thumb(item.thumbnails.lastOrNull()?.url ?: ""),
                            durationSec = item.duration.toInt().coerceAtLeast(0),
                            source = SongSource.YOUTUBE,
                        )
                    }
                    .filter { it.title.isNotBlank() }
                val filtered = songs.filter { isLikelySong(it) }
                (if (filtered.isNotEmpty()) filtered else songs).take(limit)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("AurumApi", "native search failed: ${e.message}")
                emptyList()
            }
        }

    private fun videoIdOf(url: String): String = try {
        android.net.Uri.parse(url).getQueryParameter("v") ?: ""
    } catch (_: Exception) {
        ""
    }

    /** Drops obvious non-song uploads. Only used on native-search results —
     *  plain YouTube search has no concept of "is this a song". */
    private fun isLikelySong(song: Song): Boolean {
        val d = song.durationSec
        if (d in 1..44 || d > 720) return false
        val t = song.title.lowercase()
        return JUNK_PHRASES.none { t.contains(it) }
    }

    private val JUNK_PHRASES = listOf(
        "full movie", "full video song", "jukebox", "live concert", "reaction",
        "full episode", "trailer", "interview", "making of", "behind the scenes",
        "compilation", "1 hour", "one hour", "unboxing", "vlog",
    )

    private fun clean(s: String): String = s
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .trim()

    /** Same idea as the phone's _searchThumb: ask Google's image CDN for a
     *  card-sized thumbnail instead of the tiny default. */
    private fun thumb(url: String): String? {
        if (url.isBlank()) return null
        return url.replace(Regex("=w\\d+-h\\d+.*$"), "=w300-h300-l85-rj")
    }

    // ── Home ────────────────────────────────────────────────────────────

    // Each section has a POOL of queries — every load picks a different one
    // per section, so the rows aren't the literal same songs every time.
    private val sectionPools: List<Pair<String, List<String>>> = listOf(
        "Trending Now" to listOf(
            "trending songs this week", "viral songs 2026", "top trending hindi", "trending songs 2026",
        ),
        "New Releases" to listOf(
            "new songs 2026", "latest bollywood releases", "new hindi songs this month", "fresh releases 2026",
        ),
        "Made For You" to listOf(
            "top hits playlist", "popular hindi songs", "most played songs", "hit songs collection",
        ),
        "Top Charts" to listOf(
            "top charts", "top 50 bollywood", "chartbusters 2026", "top hindi songs 2026",
        ),
        "Bollywood Hits" to listOf(
            "bollywood hits", "bollywood blockbuster songs", "bollywood dance hits", "bollywood romantic hits",
        ),
        "Love Songs" to listOf(
            "love songs hits", "romantic hindi songs", "unplugged love songs", "90s romantic hits",
        ),
        "Party Mix" to listOf(
            "party dance hits", "wedding dance songs", "club hits hindi", "bollywood party songs",
        ),
        "Punjabi Beats" to listOf(
            "punjabi songs 2026", "punjabi bhangra hits", "new punjabi songs",
        ),
        "Retro Classics" to listOf(
            "90s bollywood classics", "old is gold hindi", "kishore kumar hits", "lata mangeshkar hits",
        ),
        "Sad & Soulful" to listOf(
            "sad songs hindi", "heartbreak songs", "soulful hindi songs",
        ),
    )

    /** Streams Home rows as each search finishes (3 at a time). Which
     *  sections appear and which query each uses is reshuffled every call.
     *  A row that fails is simply skipped; an empty flow means everything
     *  failed and the UI shows its error/retry state. */
    fun homeRows(): Flow<HomeRow> = channelFlow {
        val chosen = sectionPools.shuffled().take(HOME_ROW_COUNT)
        val gate = Semaphore(HOME_CONCURRENCY)
        chosen.forEachIndexed { index, (title, pool) ->
            launch {
                gate.withPermit {
                    val songs = runCatching { search(pool.random(), HOME_ROW_SONGS) }
                        .getOrDefault(emptyList())
                    if (songs.isNotEmpty()) send(HomeRow(index, title, songs))
                }
            }
        }
    }

    // ── Stream resolution ───────────────────────────────────────────────

    // songId -> YouTube video id, for songs whose own id isn't one.
    private val matchedVideoIds = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > 200
    }

    /** The YouTube video id that plays [song], or null if it can't be played
     *  on TV (e.g. a file that only exists on the phone). */
    suspend fun youtubeIdFor(song: Song): String? {
        return when (song.source) {
            SongSource.YOUTUBE -> song.id.takeIf { it.isNotBlank() }
            SongSource.LOCAL -> null
            SongSource.SAAVN -> {
                synchronized(matchedVideoIds) { matchedVideoIds[song.id] }
                    ?: matchOnYoutube(song)?.also {
                        synchronized(matchedVideoIds) { matchedVideoIds[song.id] = it }
                    }
            }
        }
    }

    private suspend fun matchOnYoutube(song: Song): String? {
        val artist = song.artist.takeUnless { it.equals("Unknown", true) || it.equals("Unknown Artist", true) } ?: ""
        val results = search("${song.title} $artist".trim(), limit = 6)
        if (results.isEmpty()) return null
        val sameLength = if (song.durationSec > 0) {
            results.firstOrNull { it.durationSec > 0 && kotlin.math.abs(it.durationSec - song.durationSec) <= 12 }
        } else null
        return (sameLength ?: results.first()).id
    }

    /** Playable URL for [song] at [quality], or null if every path failed. */
    suspend fun resolveStream(
        song: Song,
        quality: AudioQuality,
        preferAac: Boolean = false,
        forceRefresh: Boolean = false,
    ): ResolvedStream? {
        val videoId = youtubeIdFor(song) ?: return null
        return YoutubeStreamResolver.resolve(videoId, quality, preferAac, forceRefresh)
    }

    /** Drops the cached URL for [song] (dead/expired link recovery). */
    suspend fun invalidateStream(song: Song) {
        youtubeIdFor(song)?.let { YoutubeStreamResolver.invalidate(it) }
    }

    /** Warms the URL cache for a song that will probably play next. */
    fun prefetch(song: Song, quality: AudioQuality) {
        ioScope.launch {
            runCatching {
                youtubeIdFor(song)?.let { YoutubeStreamResolver.prefetch(it, quality) }
            }
        }
    }

    // ── Recommendations (YouTube's own radio) ───────────────────────────
    //
    // "Up next" comes from YouTube itself: the Mix / radio playlist of the
    // song that is playing (RD<videoId>, the same list YouTube Music builds
    // for "Start radio"), topped up with the watch-page "related" feed. That
    // is YouTube's real recommendation output for THIS song — we then only
    // clean it (songs only, no duplicate versions, no 5-songs-in-a-row from
    // one artist) so the queue sounds like a playlist, not a search page.

    private const val RELATED_CACHE_TTL_MS = 15 * 60_000L
    private const val MAX_PER_ARTIST = 5

    private val BRACKETS = Regex("[\\(\\[\\{].*?[\\)\\]\\}]")
    private val TITLE_NOISE = Regex("\\b(official|video|audio|lyrical|lyrics|full song|song|hd|4k|music)\\b")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")

    // Alternate versions are skipped unless the playing song is one itself.
    private val VARIANT_PHRASES = listOf(
        "slowed", "reverb", "8d audio", "nightcore", "sped up", "mashup", "bass boosted", "karaoke", "status",
    )

    /** Songs to play after [seed], best first. Never contains [seed] or any id
     *  in [exclude]. Empty only if every source failed. */
    suspend fun related(seed: Song, exclude: Set<String> = emptySet(), limit: Int = 25): List<Song> {
        val videoId = youtubeIdFor(seed)
        var pool: List<Song> = emptyList()
        if (videoId != null) {
            val key = "related:$videoId"
            pool = NetworkResilience.EdgeCache.get<List<Song>>(key)
                ?: withContext(Dispatchers.IO) { fetchRadioBlocking(videoId) }.also {
                    if (it.isNotEmpty()) NetworkResilience.EdgeCache.put(key, it, RELATED_CACHE_TTL_MS)
                }
        }
        val ranked = rankRelated(seed, pool, exclude)
        if (ranked.size >= 6) return ranked.take(limit)

        // Radio unavailable/too thin: same-artist search keeps music going.
        val artist = seed.artist.takeUnless { it.isBlank() || it.equals("Unknown Artist", true) }
        val extra = runCatching { search(if (artist != null) "$artist songs" else seed.title, 20) }
            .getOrDefault(emptyList())
        return rankRelated(seed, pool + extra, exclude).take(limit)
    }

    private fun fetchRadioBlocking(videoId: String): List<Song> {
        YoutubeStreamResolver.ensureInit()
        val out = ArrayList<Song>(40)
        try {
            val mix = ServiceList.YouTube.getPlaylistExtractor(
                "https://www.youtube.com/watch?v=$videoId&list=RD$videoId"
            )
            mix.fetchPage()
            mix.initialPage.items.filterIsInstance<StreamInfoItem>()
                .mapNotNullTo(out) { streamItemToSong(it) }
        } catch (e: Exception) {
            Log.w("AurumApi", "mix failed: ${e.message}")
        }
        if (out.size < 12) {
            try {
                val ex = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
                ex.fetchPage()
                ex.relatedItems?.items?.filterIsInstance<StreamInfoItem>()
                    ?.mapNotNullTo(out) { streamItemToSong(it) }
            } catch (e: Exception) {
                Log.w("AurumApi", "related failed: ${e.message}")
            }
        }
        return out.distinctBy { it.id }
    }

    private fun streamItemToSong(item: StreamInfoItem): Song? {
        val id = videoIdOf(item.url)
        val title = clean(item.name ?: "")
        if (id.isBlank() || title.isBlank()) return null
        return Song(
            id = id,
            title = title,
            artist = clean(item.uploaderName ?: "").removeSuffix(" - Topic").ifBlank { "Unknown Artist" },
            albumArtUrl = thumb(cardThumb(item.thumbnails)),
            durationSec = item.duration.toInt().coerceAtLeast(0),
            source = SongSource.YOUTUBE,
        )
    }

    /** Smallest thumbnail that is still sharp on a card (>= 240 px tall) —
     *  the biggest one is a 1280px image nobody needs on a 1 GB box. */
    private fun cardThumb(images: List<org.schabi.newpipe.extractor.Image>?): String {
        if (images.isNullOrEmpty()) return ""
        val pick = images.filter { it.height >= 240 }.minByOrNull { it.height } ?: images.last()
        return pick.url ?: ""
    }

    private fun normTitle(title: String, fallback: String): String {
        val n = title.lowercase()
            .replace(BRACKETS, " ")
            .replace(TITLE_NOISE, " ")
            .replace(NON_ALNUM, " ")
            .trim()
        return n.ifBlank { fallback }
    }

    /** YouTube's order is kept (that IS the algorithm); this only drops what
     *  would make the queue worse: non-songs, alternate versions, the same
     *  song twice from different channels, and one artist hogging the list. */
    private fun rankRelated(seed: Song, pool: List<Song>, exclude: Set<String>): List<Song> {
        val seenTitles = HashSet<String>()
        seenTitles += normTitle(seed.title, seed.id)
        val perArtist = HashMap<String, Int>()
        val seedTitle = seed.title.lowercase()
        val out = ArrayList<Song>(pool.size)
        for (s in pool) {
            if (s.id == seed.id || s.id in exclude) continue
            if (!isLikelySong(s)) continue
            val t = s.title.lowercase()
            if (VARIANT_PHRASES.any { t.contains(it) && !seedTitle.contains(it) }) continue
            if (!seenTitles.add(normTitle(s.title, s.id))) continue
            val artist = s.artist.lowercase()
            if (artist != "unknown artist") {
                val n = perArtist[artist] ?: 0
                if (n >= MAX_PER_ARTIST) continue
                perArtist[artist] = n + 1
            }
            out += s
        }
        return out
    }

    // ── TV <-> phone pairing (unchanged) ────────────────────────────────

    data class PairingSession(val code: String, val confirmUrl: String, val expiresInSeconds: Int)

    /** Starts a new TV<->phone pairing session. Worker generates a short
     *  code + stores it in KV; TV shows it as a QR of confirmUrl. */
    suspend fun createPairingSession(): PairingSession? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$WORKER/api/pair/create")
                .post(ByteArray(0).toRequestBody(null))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                if (!json.optBoolean("success")) return@withContext null
                PairingSession(
                    code = json.optString("code"),
                    confirmUrl = json.optString("confirmUrl"),
                    expiresInSeconds = json.optInt("expiresInSeconds", 120),
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Polls pairing status. Returns the Google idToken once the phone has
     *  confirmed sign-in, null while still pending/expired. */
    suspend fun pollPairingStatus(code: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$WORKER/api/pair/$code/status").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                if (json.optString("status") == "approved") {
                    json.optString("idToken").takeIf { it.isNotBlank() }
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }
}

/** Cancellable OkHttp call: cancelling the coroutine cancels the HTTP call,
 *  so a superseded search/resolve stops using the radio immediately. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}
