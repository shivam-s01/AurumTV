package com.aurum.musictv.data

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

    // Tuned for TV-box WiFi, which is often the weakest link in the chain
    // on budget hardware — a small bounded connection pool avoids holding
    // idle sockets that cost memory for no benefit on a single-screen app,
    // and retryOnConnectionFailure smooths over the brief drops that cheap
    // WiFi chipsets are prone to.
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
        val results = getJsonArrayOrObjectData(
            "$WORKER/api/search/songs?query=$encoded&limit=$limit"
        )
        return parseSongs(results)
    }

    /** Worker responses come back in one of two shapes depending on
     *  endpoint/route: {"data": [...]} or a bare [...] array. The phone
     *  app's api_service.dart handles both the same way (see its
     *  `data is Map ? data['data'] as List : data is List ? data : null`
     *  pattern) — this mirrors that exactly rather than assuming a nested
     *  {"data": {"results": [...]}} shape, which the Worker doesn't use. */
    private suspend fun getJsonArrayOrObjectData(url: String): JSONArray {
        return withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext JSONArray()
                    val body = resp.body?.string() ?: return@withContext JSONArray()
                    val trimmed = body.trimStart()
                    if (trimmed.startsWith("[")) {
                        JSONArray(body)
                    } else {
                        val obj = JSONObject(body)
                        obj.optJSONArray("data") ?: JSONArray()
                    }
                }
            } catch (e: Exception) {
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
                // Probe first (matches phone app's Range-request probe pattern)
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
            // JioSaavn/Worker response uses "song" as the title field, not
            // "title" or "name" — matches api_service.dart's _songFromSaavn
            // exactly (title = j['song'] ?? j['name'] ?? j['title']).
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

    /** Artist is a nested object — {"artists": {"primary": [{"name": "..."}]}}
     *  — not a flat string field. Falls back to primary_artists/singers/
     *  artist string fields for older/alternate response shapes, same as
     *  api_service.dart's _songFromSaavn. */
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
            // Same "take last/highest-quality" pattern as song.dart:79-85
            val last = imageField.optJSONObject(imageField.length() - 1)
            return last?.optString("url") ?: last?.optString("link")
        }
        return o.optString("artwork").ifBlank { null }
            ?: o.optString("thumbnail").ifBlank { null }
    }
}
