package com.aurum.musictv.ui.util

import androidx.compose.runtime.compositionLocalOf

/** True when "Reduce motion" is on (default on low-RAM TVs): cards skip
 *  their focus-zoom animation, the hero banner stops rotating. */
val LocalReduceMotion = compositionLocalOf { false }
