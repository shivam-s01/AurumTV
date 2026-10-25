package com.aurum.musictv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.aurum.musictv.ui.theme.AurumColors

/**
 * Small cover thumbnail for list rows (Up Next, mini player). The request is
 * decoded at the row's own pixel size, so a 70-song queue costs a few
 * hundred KB of bitmap memory instead of 70 full 300 px covers.
 */
@Composable
fun ArtThumb(
    url: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = 8.dp,
) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(16)
    val request = remember(url, px) {
        ImageRequest.Builder(context)
            .data(url)
            .size(px)
            .crossfade(false)
            .build()
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(AurumColors.AmoledBgSurface),
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier.size(size),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
