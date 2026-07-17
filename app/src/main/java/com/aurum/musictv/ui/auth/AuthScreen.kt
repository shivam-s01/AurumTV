package com.aurum.musictv.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.aurum.musictv.sync.AuthRepository
import com.aurum.musictv.ui.theme.AurumColors
import kotlinx.coroutines.launch

/**
 * Sign-in is entirely optional (Spotify-TV style) — this screen is only
 * ever reached via the profile icon, never blocks the app. Two ways in:
 * on-device Google Sign-In (Credential Manager), or scan-a-QR-with-your-
 * phone (no TV account picker needed, works even when the TV box has no
 * Google account configured at the OS level). "Not now" / back always
 * just returns to Home unchanged.
 */
@Composable
fun AuthScreen(onSignedIn: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showQr by remember { mutableStateOf(false) }
    val alreadySignedIn = AuthRepository.isSignedIn

    if (showQr) {
        QrPairingScreen(
            onSignedIn = onSignedIn,
            onBack = { showQr = false },
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Aurum",
            color = AurumColors.Gold,
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
        )

        if (alreadySignedIn) {
            Text(
                text = AuthRepository.displayName ?: "Signed in",
                color = AurumColors.TextPrimary,
                fontSize = 20.sp,
                modifier = Modifier.padding(top = 16.dp, bottom = 32.dp),
            )
            PillButton(
                label = "Sign out",
                onClick = {
                    scope.launch {
                        AuthRepository.signOut()
                        onDismiss()
                    }
                },
            )
        } else {
            Text(
                text = "Sign in with the same Google account you use on your phone.\nYour library, playlists, and playback pick up right where you left off.",
                color = AurumColors.TextSecondary,
                fontSize = 16.sp,
                modifier = Modifier.padding(top = 16.dp, bottom = 40.dp),
            )

            PillButton(
                label = if (isLoading) "Signing in..." else "Sign in with Google",
                enabled = !isLoading,
                onClick = {
                    isLoading = true
                    errorMessage = null
                    scope.launch {
                        val error = AuthRepository.signInWithGoogle(context)
                        isLoading = false
                        if (error == null) onSignedIn() else errorMessage = error
                    }
                },
            )

            Row(modifier = Modifier.padding(top = 16.dp)) {
                Text(
                    text = "or",
                    color = AurumColors.TextSecondary,
                    fontSize = 14.sp,
                )
            }

            Row(modifier = Modifier.padding(top = 16.dp)) {
                PillButton(
                    label = "Sign in with QR code",
                    filled = false,
                    onClick = { showQr = true },
                )
            }

            errorMessage?.let {
                Text(
                    text = it,
                    color = Color(0xFFE05252),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }

        Row(modifier = Modifier.padding(top = 32.dp)) {
            Text(
                text = "Not now",
                color = AurumColors.TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun PillButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    filled: Boolean = true,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .let {
                if (filled) it.background(AurumColors.Gold)
                else it.background(AurumColors.AmoledBgSurface)
            }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 32.dp, vertical = 14.dp),
    ) {
        Text(
            text = label,
            color = if (filled) AurumColors.AmoledBg else AurumColors.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
