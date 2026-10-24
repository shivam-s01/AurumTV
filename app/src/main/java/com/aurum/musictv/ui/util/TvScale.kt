package com.aurum.musictv.ui.util

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** The layout is designed on a 1280 x 720 dp canvas (what a 1080p TV reports
 *  at the usual tvdpi setting). */
private const val DESIGN_W_DP = 1280f
private const val DESIGN_H_DP = 720f

/**
 * Makes the UI look the same on every TV: 720p, 1080p, 4K, 32" or 65", and
 * on boxes that report a odd system density (some report 160dpi, some 320dpi,
 * which without this makes the app tiny on one TV and huge on another, or
 * leaves an empty strip on the right/bottom).
 *
 * The window's real pixel size is divided by the design canvas, so the app
 * always fills the whole screen edge to edge. No bitmaps, no extra layout
 * pass: it only swaps the Density value once per window size.
 */
@Composable
fun TvScale(content: @Composable () -> Unit) {
    val system = LocalDensity.current
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val density =
            if (wPx > 0f && hPx > 0f) minOf(wPx / DESIGN_W_DP, hPx / DESIGN_H_DP).coerceIn(0.5f, 4f)
            else system.density
        val scaled = remember(density, system.fontScale) {
            Density(density = density, fontScale = system.fontScale.coerceIn(0.85f, 1.15f))
        }
        CompositionLocalProvider(LocalDensity provides scaled) { content() }
    }
}
