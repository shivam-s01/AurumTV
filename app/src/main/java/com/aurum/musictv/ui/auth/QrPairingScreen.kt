package com.aurum.musictv.ui.auth

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.aurum.musictv.sync.AuthRepository
import com.aurum.musictv.sync.PairingRepository
import com.aurum.musictv.ui.theme.AurumColors
import com.aurum.musictv.ui.util.adaptiveClickable
import com.aurum.musictv.ui.util.isTouchDevice
import com.aurum.musictv.ui.util.rememberClickFocusRequester
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The signed-out screen: QR + code on the left, steps on the right. The
 * phone (already signed in to Astra) approves, the TV signs in by itself —
 * no email, no password, no typing on the remote. Codes renew on their own,
 * so the screen can sit open as long as needed.
 */
@Composable
fun QrPairingScreen(onSignedIn: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var session by remember { mutableStateOf<PairingRepository.Session?>(null) }
    var deadline by remember { mutableStateOf(0L) }
    var secondsLeft by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var signedInName by remember { mutableStateOf<String?>(null) }
    var googleBusy by remember { mutableStateOf(false) }

    // Pair loop: get a code, poll every 2 s, renew when it expires.
    LaunchedEffect(Unit) {
        while (isActive) {
            val s = PairingRepository.create()
            if (s == null) {
                session = null
                error = "Can't reach the server — retrying…"
                delay(4_000)
                continue
            }
            error = null
            session = s
            deadline = SystemClock.elapsedRealtime() + PairingRepository.CODE_TTL_SECONDS * 1_000L
            var renew = false
            while (isActive && !renew && SystemClock.elapsedRealtime() < deadline) {
                when (val r = PairingRepository.poll(s.code)) {
                    is PairingRepository.Poll.Approved -> {
                        val err = AuthRepository.signInWithIdToken(r.idToken)
                        if (err == null) {
                            signedInName = AuthRepository.displayName ?: "your account"
                            delay(1_400)
                            onSignedIn()
                            return@LaunchedEffect
                        }
                        error = err
                        renew = true
                    }
                    PairingRepository.Poll.Expired -> renew = true
                    else -> Unit
                }
                if (!renew) delay(2_000)
            }
        }
    }

    // Countdown shown to the user (display only).
    LaunchedEffect(session?.code) {
        while (isActive) {
            secondsLeft = ((deadline - SystemClock.elapsedRealtime()) / 1_000L).toInt().coerceAtLeast(0)
            delay(1_000)
        }
    }

    val isTouch = isTouchDevice()
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!isTouch) runCatching { firstFocus.requestFocus() }
    }

    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(64.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ── Left: QR + code ─────────────────────────────────────────────
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val s = session
            if (s != null) {
                QrCode(text = s.qrText)
                Text(
                    text = s.code.chunked(4).joinToString("-"),
                    color = AurumColors.TextPrimary,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 18.dp),
                )
                Text(
                    text = if (signedInName != null) "Connected" else "Code refreshes automatically · ${secondsLeft / 60}:%02d".format(secondsLeft % 60),
                    color = AurumColors.TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                Text("Getting your code…", color = AurumColors.TextSecondary, fontSize = 16.sp)
            }
        }

        // ── Right: instructions + alternatives ──────────────────────────
        Column(modifier = Modifier.widthIn(max = 460.dp)) {
            Text("Astra", color = AurumColors.Gold, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text(
                text = if (signedInName != null) "Signed in as $signedInName" else "Sign in with your phone",
                color = AurumColors.TextPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = "Your library, playlists and history from the Astra app show up here — no email or password on the TV.",
                color = AurumColors.TextSecondary,
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            )

            Step("1", "Open Astra on your phone")
            Step("2", "Settings → Link TV")
            Step("3", "Scan this code (or type it) and tap Approve")

            error?.let {
                Text(it, color = Color(0xFFE05252), fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            }

            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TvButton(
                    label = if (googleBusy) "Opening Google…" else "Use Google on this TV",
                    primary = false,
                    modifier = Modifier.focusRequester(firstFocus),
                    enabled = !googleBusy && signedInName == null,
                    onClick = {
                        googleBusy = true
                        error = null
                        scope.launch {
                            val err = AuthRepository.signInWithGoogle(context)
                            googleBusy = false
                            if (err == null) onSignedIn() else error = friendlyGoogleError(err)
                        }
                    },
                )
                TvButton(label = "Not now", primary = false, onClick = onDismiss)
            }
        }
    }
}

private fun friendlyGoogleError(raw: String): String =
    if (raw.contains("credential", ignoreCase = true) || raw.contains("cancel", ignoreCase = true)) {
        "No Google account on this TV — scan the code with your phone instead."
    } else raw

@Composable
private fun Step(number: String, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = number,
            color = AurumColors.AmoledBg,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(AurumColors.Gold, RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
        Text(text, color = AurumColors.TextPrimary, fontSize = 16.sp)
    }
}

@Composable
private fun TvButton(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val focus = rememberClickFocusRequester()
    val isTouch = isTouchDevice()
    Surface(
        onClick = { if (enabled) onClick() },
        modifier = modifier.adaptiveClickable(focus, isTouch) { if (enabled) onClick() },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) AurumColors.Gold else AurumColors.AmoledBgSurface,
            focusedContainerColor = if (primary) AurumColors.GoldLight else AurumColors.AmoledBgElevated,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(border = BorderStroke(2.dp, AurumColors.Gold), shape = RoundedCornerShape(10.dp)),
        ),
    ) {
        Text(
            text = label,
            color = if (primary) AurumColors.AmoledBg else AurumColors.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}
