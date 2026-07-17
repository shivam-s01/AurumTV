package com.aurum.musictv.ui.theme

import androidx.compose.ui.graphics.Color

// Matches lib/theme/*.dart exactly, so TV feels like the same brand as
// mobile, not a reskin. Note: "gold" is the mobile app's own naming for
// this accent even though the hex is actually a violet/purple — kept the
// name for consistency with the mobile codebase's vocabulary.
object AurumColors {
    val Gold = Color(0xFF9B7EDE)
    val GoldLight = Color(0xFFB69FEE)
    val GoldDark = Color(0xFF7A5FC4)

    // AMOLED palette — TV requirement calls for true AMOLED black, and
    // TVs are almost always OLED/QLED where this saves real power too.
    val AmoledBg = Color(0xFF000000)
    val AmoledBgCard = Color(0xFF0A0A0A)
    val AmoledBgElevated = Color(0xFF0F0F0F)
    val AmoledBgSurface = Color(0xFF141414)
    val AmoledDivider = Color(0xFF1A1A1A)

    val TextPrimary = Color(0xFFF0EBD8)
    val TextSecondary = Color(0xFF8A8A9A)
    val TextMuted = Color(0xFF4A4A5E)
}
