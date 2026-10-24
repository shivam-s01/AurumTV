package com.aurum.musictv.ui.home

import com.aurum.musictv.data.model.Song
import java.util.Locale

/** One hero slide: the song, the small caption above the title, and the
 *  queue that Play starts (the row it came from, so playback continues
 *  with related songs instead of a random mix). */
data class HeroItem(
    val song: Song,
    val label: String,
    val queue: List<Song>,
)

/**
 * Deterministic hero selection. No randomness here: the rows are already
 * shuffled per load (AurumApi.homeRows), and picking by priority keeps the
 * banner stable and in a sensible order no matter which row's network call
 * happens to finish first.
 *
 * Order: Continue Listening -> one personal pick ("Because you played X")
 * -> best song of Trending / New / Charts / ... by fixed priority.
 * Rules: max [MAX_SLIDES]; never the same song or the same artist twice;
 * only real songs with artwork (no shorts, mixes, lyric loops, 'Unknown').
 */
object HeroPicker {

    const val MAX_SLIDES = 6

    private val SECTION_PRIORITY = listOf(
        "Trending Now", "New Releases", "Top Charts", "Made For You", "Bollywood Hits",
        "Punjabi Beats", "Love Songs", "Party Mix", "Retro Classics", "Sad & Soulful",
    )

    private val SECTION_LABEL = mapOf(
        "Trending Now" to "TRENDING NOW",
        "New Releases" to "NEW RELEASE",
        "Top Charts" to "TOP CHART",
        "Made For You" to "MADE FOR YOU",
        "Bollywood Hits" to "BOLLYWOOD HIT",
        "Punjabi Beats" to "PUNJABI BEATS",
        "Love Songs" to "LOVE SONGS",
        "Party Mix" to "PARTY MIX",
        "Retro Classics" to "RETRO CLASSIC",
        "Sad & Soulful" to "SAD & SOULFUL",
    )

    private val JUNK = listOf(
        "full movie", "full video song", "jukebox", "live concert", "reaction", "full episode",
        "trailer", "interview", "making of", "behind the scenes", "compilation", "1 hour",
        "one hour", "unboxing", "vlog", "mashup", "slowed", "reverb", "8d audio", "nonstop",
        "non stop", "podcast", "teaser", "status video", "karaoke", "cover by", "shorts",
    )

    /** Bracketed / piped video noise that looks bad as a 38 sp headline. */
    private val NOISE_BRACKETS = Regex(
        """\s*[\(\[]\s*(official|lyric|lyrics|full|hd|4k|audio|video|music video|visualizer|song)[^\)\]]*[\)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val TRAILING_PIPE = Regex("""\s*[|｜].*$""")

    /** Title as shown on the hero. Falls back to the original if cleaning
     *  would leave it empty. */
    fun displayTitle(raw: String): String {
        val cleaned = raw.replace(NOISE_BRACKETS, "").replace(TRAILING_PIPE, "").trim()
        return cleaned.ifBlank { raw.trim() }
    }

    fun displayArtist(raw: String): String =
        raw.split(" - Topic").first().trim().ifBlank { raw }

    fun isHeroWorthy(song: Song): Boolean {
        if (song.albumArtUrl.isNullOrBlank()) return false
        if (song.title.isBlank()) return false
        val artist = song.artist.trim()
        if (artist.isEmpty() || artist.equals("Unknown", true) || artist.equals("Unknown Artist", true)) return false
        val d = song.durationSec
        // Unknown length (0) is allowed; shorts and long mixes are not.
        if (d in 1..59 || d > 600) return false
        val t = song.title.lowercase(Locale.ROOT)
        return JUNK.none { t.contains(it) }
    }

    private fun artistKey(song: Song): String =
        displayArtist(song.artist).lowercase(Locale.ROOT).substringBefore(',').substringBefore('&').trim()

    private fun titleKey(song: Song): String =
        displayTitle(song.title).lowercase(Locale.ROOT).replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    fun build(
        continueListening: Song?,
        recentlyPlayed: List<Song>,
        personalRows: List<Pair<String, List<Song>>>,
        sections: List<Pair<String, List<Song>>>,
    ): List<HeroItem> {
        val out = ArrayList<HeroItem>(MAX_SLIDES)
        val ids = HashSet<String>()
        val artists = HashSet<String>()
        val titles = HashSet<String>()

        fun tryAdd(song: Song, label: String, queue: List<Song>, force: Boolean = false): Boolean {
            if (out.size >= MAX_SLIDES) return false
            if (!force && !isHeroWorthy(song)) return false
            if (song.id in ids || titleKey(song) in titles) return false
            if (!force && artistKey(song) in artists) return false
            ids += song.id
            titles += titleKey(song)
            artists += artistKey(song)
            out += HeroItem(song, label, queue)
            return true
        }

        // 1) Where the person left off (always allowed: it is THEIR song).
        if (continueListening != null) {
            val queue = recentlyPlayed.ifEmpty { listOf(continueListening) }
            tryAdd(continueListening, "CONTINUE LISTENING", queue, force = true)
        }

        // 2) One personalised pick from the first "Because you played" row.
        personalRows.firstOrNull()?.let { (title, songs) ->
            val label = title.uppercase(Locale.ROOT).let { if (it.length > 34) it.take(33).trimEnd() + "…" else it }
            songs.firstOrNull { it.id !in ids && isHeroWorthy(it) && artistKey(it) !in artists }
                ?.let { tryAdd(it, label, songs) }
        }

        // 3) Browse rows in fixed priority order, best (first valid) song each.
        val ordered = sections.sortedBy { (title, _) ->
            SECTION_PRIORITY.indexOf(title).let { if (it < 0) SECTION_PRIORITY.size else it }
        }
        for ((title, songs) in ordered) {
            if (out.size >= MAX_SLIDES) break
            val label = SECTION_LABEL[title] ?: title.uppercase(Locale.ROOT)
            songs.firstOrNull { it.id !in ids && isHeroWorthy(it) && artistKey(it) !in artists && titleKey(it) !in titles }
                ?.let { tryAdd(it, label, songs) }
        }
        return out
    }
}
