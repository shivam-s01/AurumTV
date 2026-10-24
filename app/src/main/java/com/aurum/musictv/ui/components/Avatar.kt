package com.aurum.musictv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.aurum.musictv.ui.theme.AurumColors

/**
 * The signed-in account's photo (the same Google photo the phone app shows —
 * both read it from the one shared Supabase user). While it loads, or when
 * the account has none, the first letter of the name is shown instead of an
 * empty circle; signed out shows a plain person glyph. One small AsyncImage,
 * decoded at the circle's own size.
 */
@Composable
fun Avatar(url: String?, name: String?, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(AurumColors.GoldDark.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        val initial = name?.trim()?.firstOrNull()?.uppercaseChar()?.toString()
        Text(
            text = initial ?: "\uD83D\uDC64",
            color = AurumColors.TextPrimary,
            fontSize = (size.value * 0.45f).sp,
            fontWeight = FontWeight.Bold,
        )
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = "Profile",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
