package com.aurum.musictv.ui.util

import android.content.pm.PackageManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext

/**
 * PERMANENT — this is the app's real touch/TV input strategy, not a
 * dev-only shim.
 *
 * TV Compose (androidx.tv.material3.Surface) only fires onClick from a
 * D-pad "select" event on a *focused* item. On a real TV that's correct
 * and desired. On a touchscreen device there's no D-pad, so a raw finger
 * tap never focuses the item first — the click never fires, even though
 * scrolling (plain LazyRow/LazyColumn) works fine on both.
 *
 * [isTouchDevice] checks the actual hardware once per Activity via
 * PackageManager.FEATURE_TOUCHSCREEN — the same signal Android itself
 * uses to decide whether a device is a phone/tablet vs a TV box. This is
 * NOT a build flavor or debug flag: the same APK installed on a TV
 * reports no touchscreen feature and keeps pure D-pad behavior; the same
 * APK on a phone reports a touchscreen and gets tap-to-click. Nothing to
 * remember to strip out before a TV Play Store submission.
 */
@Composable
fun isTouchDevice(): Boolean {
    val context = LocalContext.current
    return remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }
}

/**
 * Modifier for any TV-material `Surface(onClick = ...)`/clickable row.
 * On a touchscreen device, requests focus on tap-down so the underlying
 * Surface's onClick still fires normally on tap release. On a TV
 * (isTouchDevice == false) this is a no-op — D-pad focus/select behaves
 * exactly as it always has.
 *
 * Uses awaitEachGesture + awaitFirstDown(requireUnconsumed = false)
 * instead of detectTapGestures: detectTapGestures' onPress consumes the
 * initial down event, which competes with the parent LazyRow/LazyColumn's
 * own drag-to-scroll gesture detector and can make horizontal rows feel
 * sticky/janky under a finger. This version only requests focus — it
 * never consumes the pointer event, so scrolling a row by dragging
 * through a card still wins exactly as it did before this modifier
 * existed. Surface's own built-in clickable (from onClick=) is still what
 * actually fires the click on tap-release; this only makes sure the item
 * is focused first so that click can fire at all on a touchscreen.
 *
 * Usage in a @Composable:
 *   val focusRequester = rememberClickFocusRequester()
 *   Surface(onClick = onClick, modifier = Modifier.adaptiveClickable(focusRequester, isTouch)...)
 */
fun Modifier.adaptiveClickable(focusRequester: FocusRequester, isTouch: Boolean): Modifier =
    if (!isTouch) {
        this
    } else {
        this
            .focusRequester(focusRequester)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    focusRequester.requestFocus()
                }
            }
    }

/** Convenience: creates + remembers a FocusRequester for adaptiveClickable(). */
@Composable
fun rememberClickFocusRequester(): FocusRequester = remember { FocusRequester() }
