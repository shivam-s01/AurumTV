package com.aurum.musictv.ui.util

/** Cards use 300px thumbnails (light on RAM). The hero banner and the full
 *  player are shown much larger, so they ask Google's image CDN for a
 *  600px version of the same art — one such bitmap is under 1 MB in RGB_565. */
fun bigArt(url: String?): String? = url?.replace("=w300-h300", "=w600-h600")

/** Full-screen player art on normal devices: a 1000 px cover so the big
 *  picture stays sharp on a TV. Low-end mode keeps using [bigArt] (600 px). */
fun heroArt(url: String?): String? =
    url?.replace("=w300-h300", "=w1000-h1000")?.replace("=w600-h600", "=w1000-h1000")

private val ART_SIZE_TOKEN = Regex("=w\\d+-h\\d+")

/**
 * Art URL for a picture that is [wantedPx] pixels on screen. Snaps to a few
 * fixed CDN sizes (600 / 800 / 1000 / 1200) so the disk cache is reused, and
 * never goes above [maxPx] — that is how a 4K TV gets a sharp cover while a
 * 1 GB box in low-end mode stays small. URLs without a size token (other
 * CDNs) are returned untouched.
 */
fun artForPx(url: String?, wantedPx: Int, maxPx: Int): String? {
    if (url == null) return null
    val target = wantedPx.coerceAtMost(maxPx)
    val snapped = when {
        target <= 600 -> 600
        target <= 800 -> 800
        target <= 1000 -> 1000
        else -> 1200
    }.coerceAtMost(maxPx.coerceAtLeast(600))
    return url.replace(ART_SIZE_TOKEN, "=w$snapped-h$snapped")
}
