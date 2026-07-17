package com.aurum.musictv.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.aurum.musictv.data.remote.AurumApi
import com.aurum.musictv.sync.AuthRepository
import com.aurum.musictv.ui.theme.AurumColors
import kotlinx.coroutines.delay

/**
 * Netflix/Spotify-TV-style pairing: TV shows a QR + short numeric code
 * (fallback if the camera can't scan), user scans with their phone, taps
 * through a plain Google Sign-In on that page, and the TV picks up the
 * result via polling — no typing anything on the remote.
 */
@Composable
fun QrPairingScreen(onSignedIn: () -> Unit, onBack: () -> Unit) {
    var session by remember { mutableStateOf<AurumApi.PairingSession?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var secondsLeft by remember { mutableStateOf(0) }

    suspend fun startSession() {
        errorMessage = null
        val newSession = AurumApi.createPairingSession()
        if (newSession == null) {
            errorMessage = "Couldn't start QR sign-in. Check your connection."
            return
        }
        session = newSession
        secondsLeft = newSession.expiresInSeconds
    }

    LaunchedEffect(Unit) { startSession() }

    // Poll every 2s while a session is active; also count down and
    // auto-refresh a fresh code once the old one expires.
    LaunchedEffect(session?.code) {
        val current = session ?: return@LaunchedEffect
        while (secondsLeft > 0) {
            delay(2000)
            secondsLeft -= 2
            val idToken = AurumApi.pollPairingStatus(current.code)
            if (idToken != null) {
                val error = AuthRepository.signInWithIdToken(idToken)
                if (error == null) {
                    onSignedIn()
                } else {
                    errorMessage = error
                }
                return@LaunchedEffect
            }
        }
        // Expired without approval — fetch a new code automatically.
        startSession()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Scan to sign in",
            color = AurumColors.TextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Open your phone's camera and point it at the code below.",
            color = AurumColors.TextSecondary,
            fontSize = 15.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
        )

        session?.let { s ->
            val qrImageUrl =
                "https://api.qrserver.com/v1/create-qr-code/?size=280x280&data=" +
                    java.net.URLEncoder.encode(s.confirmUrl, "UTF-8")

            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(androidx.compose.ui.graphics.Color.White)
                    .padding(16.dp),
            ) {
                AsyncImage(
                    model = qrImageUrl,
                    contentDescription = "QR code",
                    modifier = Modifier.size(280.dp),
                )
            }

            Text(
                text = "Or enter this code on your phone: ${s.code}",
                color = AurumColors.TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 20.dp),
            )
            Text(
                text = "Expires in ${secondsLeft}s",
                color = AurumColors.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        errorMessage?.let {
            Text(
                text = it,
                color = androidx.compose.ui.graphics.Color(0xFFE05252),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        Row(modifier = Modifier.padding(top = 32.dp)) {
            Text(
                text = "Back",
                color = AurumColors.TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onBack)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}
