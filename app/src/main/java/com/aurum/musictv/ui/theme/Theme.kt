package com.aurum.musictv.ui.theme

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val AurumTvColorScheme = darkColorScheme(
    primary = AurumColors.Gold,
    onPrimary = AurumColors.AmoledBg,
    secondary = AurumColors.GoldDark,
    background = AurumColors.AmoledBg,
    surface = AurumColors.AmoledBgCard,
    onBackground = AurumColors.TextPrimary,
    onSurface = AurumColors.TextPrimary,
)

@Composable
fun AurumTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AurumTvColorScheme,
        content = content,
    )
}
