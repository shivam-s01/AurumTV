package com.aurum.musictv.ui.util

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput

/**
 * TEMPORARY — phone touch testing only.
 *
 * TV Compose (androidx.tv.material3.Surface) only fires onClick from a
 * D-pad "select" event on a *focused* item. On a phone there's no D-pad,
 * so a raw finger tap never focuses the item first -- the click never
 * fires, even though scrolling (plain LazyRow/LazyColumn) works fine.
 *
 * This modifier requests focus on tap-down and then still lets the
 * underlying Surface's onClick fire normally on tap release. It changes
 * nothing about D-pad/remote behavior: a real remote still just moves
 * focus and clicks as before. This only adds an extra input path for
 * touch.
 *
 * REMOVE THIS FILE (and the .touchClickable() call sites) once you're
 * back to testing on a TV/emulator with a D-pad, or before a Play Store
 * TV submission -- it's a dev-testing shim, not part of the real app.
 */
fun Modifier.touchClickable(focusRequester: FocusRequester): Modifier =
    this
        .focusRequester(focusRequester)
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    focusRequester.requestFocus()
                },
            )
        }

/**
 * Convenience: creates + remembers a FocusRequester for you.
 * Usage in a @Composable:
 *   val focusRequester = rememberTouchFocusRequester()
 *   Surface(onClick = onClick, modifier = Modifier.touchClickable(focusRequester)...)
 */
@androidx.compose.runtime.Composable
fun rememberTouchFocusRequester(): FocusRequester = remember { FocusRequester() }
