package com.aurum.musictv.ui.util

/** Cards use 300px thumbnails (light on RAM). The hero banner and the full
 *  player are shown much larger, so they ask Google's image CDN for a 600px
 *  version of the same art — one such bitmap is under 1 MB in RGB_565. */
fun bigArt(url: String?): String? = url?.replace("=w300-h300", "=w600-h600")
