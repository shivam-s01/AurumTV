package com.aurum.musictv.data.remote

import com.aurum.musictv.data.model.Song
import com.aurum.musictv.data.model.SongSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * TV-side port of lib/services/api_service.dart, kept intentionally small:
 * only search, home sections, and stream resolution. No lyrics, no
 * recommendation-engine calls, no offline queue splicing — those stay
 * phone-only for now. Same Worker backend, so zero backend changes needed.
 */
object AurumApi {

    // Same Worker URL as the phone app (lib/services/api_service.dart:209).
    private const val WORKER = "https://aurum-worker.shivamsharma962122.workers.dev"

    /** Last raw diagnostic captured from a search() call — surfaced by
     *  HomeBrowseFragment's Toast so on-device failures are debuggable
     *  without logcat access. Not thread-safe by design; this is a
     *  single-user single-screen TV app, last-write-wins is fine here. */
    var lastDiagnostic: String = ""
        private set

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(okhttp3.ConnectionPool(2, 30, TimeUnit.SECONDS))
        .build()

    private suspend fun getJson(url: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                JSONObject(body)
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun search(query: String, limit: Int = 25): List<Song> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        // Worker's real route is /result/?query=&limit= (see aurum-worker
        // src/index.js router) — NOT /api/search/songs, which 404s.
        val results = getJsonArrayOrObjectData(
            "$WORKER/result/?query=$encoded&limit=$limit"
        )
        return parseSongs(results)
    }

    /** Worker responses come back in one of two shapes depending on
     *  endpoint/route: {"data": [...]} or a bare [...] array. This also
     *  records exactly what happened (HTTP code, body snippet, or
     *  exception) into lastDiagnostic instead of silently swallowing
     *  failures — the previous version returned an empty JSONArray() on
     *  every failure path with no way to tell network error apart from
     *  empty-but-successful response apart from parse failure. */
    private suspend fun getJsonArrayOrObjectData(url: String): JSONArray {
        return withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    val code = resp.code
                    if (!resp.isSuccessful) {
                        lastDiagnostic = "HTTP $code from $url"
                        return@withContext JSONArray()
                    }
                    val body = resp.body?.string()
                    if (body.isNullOrBlank()) {
                        lastDiagnostic = "Empty body (HTTP $code) from $url"
                        return@withContext JSONArray()
                    }
                    val trimmed = body.trimStart()
                    val array = if (trimmed.startsWith("[")) {
                        JSONArray(body)
                    } else {
                        val obj = JSONObject(body)
                        // Worker's /result/ route replies as
                        // {"success":true,"data":{"results":[...]}}, so check
                        // data.results before falling back to a bare
                        // data:[...] shape (used by other worker routes).
                        obj.optJSONObject("data")?.optJSONArray("results")
                            ?: obj.optJSONArray("data")
                            ?: JSONArray()
                    }
                    if (array.length() == 0) {
                        lastDiagnostic = "Parsed 0 items. Body snippet: " +
                            body.take(200)
                    } else {
                        lastDiagnostic = "OK: ${array.length()} items"
                    }
                    array
                }
            } catch (e: Exception) {
                lastDiagnostic = "Exception: ${e.javaClass.simpleName}: ${e.message}"
                JSONArray()
            }
        }
    }

    suspend fun homeSections(): List<Pair<String, List<Song>>> {
        // Reuses the same "new releases" + trending search-backed endpoints
        // your phone home screen uses, just fewer of them for a lighter
        // TV landing page. Extend this once the base app is confirmed
        // working smoothly on-device.
        val trending = search("trending 2026", limit = 15)
        val bollywood = search("bollywood hits", limit = 15)
        return listOfNotNull(
            "Trending Now".takeIf { trending.isNotEmpty() }?.let { it to trending },
            "Bollywood Hits".takeIf { bollywood.isNotEmpty() }?.let { it to bollywood },
        )
    }

    /** Resolves a playable stream URL right before playback, same
     *  yt-proxy-first pattern as api_service.dart's YouTube resolve chain. */
    suspend fun resolveStreamUrl(song: Song): String? = withContext(Dispatchers.IO) {
        when (song.source) {
            SongSource.YOUTUBE -> {
                val proxyUrl = "$WORKER/api/yt-proxy?id=${song.id}"
                try {
                    val probe = Request.Builder()
                        .url(proxyUrl)
                        .header("Range", "bytes=0-255")
                        .build()
                    client.newCall(probe).execute().use { r ->
                        if (r.isSuccessful || r.code == 206) return@withContext proxyUrl
                    }
                } catch (_: Exception) { /* fall through */ }
                val streamJson = getJson("$WORKER/api/yt-stream?id=${song.id}")
                streamJson?.optString("url")?.takeIf { it.isNotBlank() }
            }
            SongSource.SAAVN -> {
                val json = getJson("$WORKER/api/songs?ids=${song.id}")
                val data = json?.optJSONArray("data")?.optJSONObject(0)
                data?.optString("downloadUrl")?.takeIf { it.isNotBlank() }
                    ?: song.streamUrl
            }
            SongSource.LOCAL -> song.streamUrl
        }
    }

    private fun parseSongs(arr: JSONArray): List<Song> {
        val out = mutableListOf<Song>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            val title = o.optString("song")
                .ifBlank { o.optString("name") }
                .ifBlank { o.optString("title", "Unknown") }
            val artist = extractArtist(o)
            val artUrl = extractArtwork(o)
            val duration = o.optInt("duration", 0)
            out.add(
                Song(
                    id = id,
                    title = title,
                    artist = artist,
                    albumArtUrl = artUrl,
                    durationSec = duration,
                    source = SongSource.SAAVN,
                )
            )
        }
        return out
    }

    private fun extractArtist(o: JSONObject): String {
        val artistsField = o.optJSONObject("artists")
        val primary = artistsField?.optJSONArray("primary")
        if (primary != null && primary.length() > 0) {
            val names = mutableListOf<String>()
            for (i in 0 until primary.length()) {
                val a = primary.optJSONObject(i)
                val name = a?.optString("name")?.takeIf { it.isNotBlank() }
                if (name != null) names.add(name)
            }
            if (names.isNotEmpty()) return names.joinToString(", ")
        }
        return o.optString("primary_artists")
            .ifBlank { o.optString("singers") }
            .ifBlank { o.optString("artist") }
            .ifBlank { "Unknown Artist" }
    }

    private fun extractArtwork(o: JSONObject): String? {
        val imageField = o.opt("image")
        if (imageField is JSONArray && imageField.length() > 0) {
            val last = imageField.optJSONObject(imageField.length() - 1)
            return last?.optString("url") ?: last?.optString("link")
        }
        // aurum-worker's /result/ route sends "image" as a plain string
        // (already resized to 500x500), not the phone app's array-of-sizes
        // shape — handle that directly instead of falling through to the
        // artwork/thumbnail fields, which the worker never sets.
        if (imageField is String && imageField.isNotBlank()) return imageField
        return o.optString("artwork").ifBlank { null }
            ?: o.optString("thumbnail").ifBlank { null }
    }
}
