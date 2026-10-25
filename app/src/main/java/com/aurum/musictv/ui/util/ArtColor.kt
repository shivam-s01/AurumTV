package com.aurum.musictv.ui.util

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A bright accent colour taken from the cover art, so the full player glows
 * in the colours of the song that is playing. The art is decoded at 32x32 px
 * (a few KB, straight from Coil's disk cache), averaged once per song, and
 * the colour eases from the previous one — no Palette library, no per-frame
 * work. Falls back to [fallback] for grey / unreadable art.
 */
@Composable
fun rememberArtAccent(url: String?, fallback: Color): Color {
    val context = LocalContext.current.applicationContext
    // Not keyed on url: the old colour stays until the new one is ready, so
    // the glow eases between songs instead of flashing back to the default.
    var target by remember { mutableStateOf(fallback) }
    LaunchedEffect(url) {
        if (url.isNullOrBlank()) return@LaunchedEffect
        val c = withContext(Dispatchers.IO) { runCatching { dominantColor(context, url) }.getOrNull() }
        target = c ?: fallback
    }
    val animated by animateColorAsState(target, tween(700), label = "artAccent")
    return animated
}

private suspend fun dominantColor(context: Context, url: String): Color? {
    val request = ImageRequest.Builder(context)
        .data(url)
        .size(32, 32)
        .allowHardware(false)
        .build()
    val result = context.imageLoader.execute(request)
    val bmp = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap ?: return null
    val w = bmp.width
    val h = bmp.height
    if (w <= 0 || h <= 0) return null
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)

    val hsv = FloatArray(3)
    var r = 0f
    var g = 0f
    var b = 0f
    var total = 0f
    for (p in px) {
        android.graphics.Color.colorToHSV(p, hsv)
        // Colourful, reasonably bright pixels count most.
        val weight = hsv[1] * hsv[2] + 0.02f
        r += android.graphics.Color.red(p) * weight
        g += android.graphics.Color.green(p) * weight
        b += android.graphics.Color.blue(p) * weight
        total += weight
    }
    if (total <= 0f) return null
    val avg = android.graphics.Color.rgb((r / total).toInt(), (g / total).toInt(), (b / total).toInt())
    android.graphics.Color.colorToHSV(avg, hsv)
    if (hsv[1] < 0.12f) return null // grey art: keep the brand colour
    hsv[1] = hsv[1].coerceIn(0.45f, 0.85f)
    hsv[2] = hsv[2].coerceIn(0.80f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}
