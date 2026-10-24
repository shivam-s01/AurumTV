package com.aurum.musictv.data.remote

import android.util.Log
import com.aurum.musictv.data.model.Song
import com.aurum.musictv.data.model.SongSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * YouTube Music's own "Up next / Start radio" for a song, straight from
 * Innertube (the same /next call music.youtube.com makes): the automix
 * playlist RDAMVM<videoId>, followed through its continuation tokens until
 * enough songs are collected. These are the real algorithmic picks for THIS
 * song, music-only (no vlogs / reaction videos).
 *
 * Built for a 1 GB TV box: no library, plain org.json, one small request at a
 * time (about 25 songs each, 3-4 requests for ~70), nothing kept afterwards
 * except the resulting Song list.
 */
internal object YtmRadio {

    // Same public web client values the phone app uses (api_service.dart).
    private const val KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    private const val CLIENT_VERSION = "1.20250310.01.00"
    private const val ENDPOINT = "https://music.youtube.com/youtubei/v1/next?key=$KEY&prettyPrint=false"
    private const val MAX_PAGES = 5

    private val JSON = "application/json".toMediaType()
    private val ART_SIZE = Regex("=w\\d+-h\\d+.*$")

    private val http by lazy {
        AurumApi.client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
    }

    private fun context(): JSONObject = JSONObject().put(
        "client",
        JSONObject()
            .put("clientName", "WEB_REMIX")
            .put("clientVersion", CLIENT_VERSION)
            .put("hl", "en")
            .put("gl", "IN"),
    )

    private suspend fun post(body: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(ENDPOINT)
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .post(body.toString().toRequestBody(JSON))
                .build()
            http.newCall(req).await().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val text = resp.body?.string() ?: return@withContext null
                JSONObject(text)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("YtmRadio", "next failed: ${e.message}")
            null
        }
    }

    /** Up to roughly [target] songs of YouTube Music's radio for [videoId], in
     *  YouTube's own order (the seed itself is skipped). Empty on any failure. */
    suspend fun fetch(videoId: String, target: Int): List<Song> {
        val out = ArrayList<Song>(target + 25)
        val seen = HashSet<String>().apply { add(videoId) }

        val first = post(
            JSONObject()
                .put("context", context())
                .put("videoId", videoId)
                .put("playlistId", "RDAMVM$videoId")
                .put("params", "wAEB")
                .put("isAudioOnly", true)
                .put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL"),
        ) ?: return emptyList()

        var panel: JSONObject? = first
            .optJSONObject("contents")
            ?.optJSONObject("singleColumnMusicWatchNextResultsRenderer")
            ?.optJSONObject("tabbedRenderer")
            ?.optJSONObject("watchNextTabbedResultsRenderer")
            ?.optJSONArray("tabs")?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("musicQueueRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("playlistPanelRenderer")

        var pages = 0
        while (panel != null && pages < MAX_PAGES) {
            parseItems(panel.optJSONArray("contents"), out, seen)
            if (out.size >= target) break
            val token = continuationOf(panel) ?: break
            val next = post(JSONObject().put("context", context()).put("continuation", token)) ?: break
            panel = next.optJSONObject("continuationContents")?.optJSONObject("playlistPanelContinuation")
            pages++
        }
        return out
    }

    private fun continuationOf(panel: JSONObject): String? {
        val c = panel.optJSONArray("continuations")?.optJSONObject(0) ?: return null
        return (c.optJSONObject("nextRadioContinuationData") ?: c.optJSONObject("nextContinuationData"))
            ?.optString("continuation")?.takeIf { it.isNotBlank() }
    }

    private fun parseItems(items: JSONArray?, out: MutableList<Song>, seen: MutableSet<String>) {
        if (items == null) return
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val r = o.optJSONObject("playlistPanelVideoRenderer")
                ?: o.optJSONObject("playlistPanelVideoWrapperRenderer")
                    ?.optJSONObject("primaryRenderer")
                    ?.optJSONObject("playlistPanelVideoRenderer")
                ?: continue
            val id = r.optString("videoId")
            if (id.isBlank() || !seen.add(id)) continue

            val title = runs(r.optJSONObject("title")).firstOrNull()?.trim().orEmpty()
            if (title.isBlank()) continue

            // longBylineText runs: "Artist", " & ", "Artist 2", " • ", "Album", " • ", "year"
            val byline = runs(r.optJSONObject("longBylineText"))
            val artist = byline.takeWhile { it.trim() != "•" }.joinToString("").trim()
                .ifBlank { "Unknown Artist" }

            val thumbs = r.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            val art = thumbs?.optJSONObject((thumbs.length() - 1).coerceAtLeast(0))?.optString("url")
                ?.takeIf { it.isNotBlank() }
                ?.replace(ART_SIZE, "=w300-h300-l85-rj")

            out += Song(
                id = id,
                title = title,
                artist = artist,
                albumArtUrl = art,
                durationSec = parseLength(runs(r.optJSONObject("lengthText")).firstOrNull()),
                source = SongSource.YOUTUBE,
            )
        }
    }

    private fun runs(text: JSONObject?): List<String> {
        val arr = text?.optJSONArray("runs") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("text") }
    }

    /** "3:45" / "1:02:10" -> seconds; 0 if unknown. */
    private fun parseLength(s: String?): Int {
        if (s.isNullOrBlank()) return 0
        val parts = s.trim().split(":").mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty() || parts.size > 3) return 0
        return parts.fold(0) { acc, p -> acc * 60 + p }
    }
}
