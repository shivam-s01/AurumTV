package com.aurum.musictv.ui.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.aurum.musictv.sync.AuthRepository
import com.aurum.musictv.ui.theme.AurumColors
import com.aurum.musictv.ui.util.adaptiveClickable
import com.aurum.musictv.ui.util.isTouchDevice
import com.aurum.musictv.ui.util.rememberClickFocusRequester
import kotlinx.coroutines.launch

/**
 * Account screen. Signed out → the QR / code pairing screen (phone approves,
 * TV signs in). Signed in → who you are + Sign out. Sign-in is always
 * optional: Back / "Not now" returns to Home and music works without it.
 */
@Composable
fun AuthScreen(onSignedIn: () -> Unit, onDismiss: () -> Unit, onSignedOut: () -> Unit = {}) {
    if (!AuthRepository.isSignedIn) {
        QrPairingScreen(onSignedIn = onSignedIn, onDismiss = onDismiss)
        return
    }

    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        com.aurum.musictv.ui.components.Avatar(
            url = AuthRepository.avatarUrl,
            name = AuthRepository.displayName,
            size = 96.dp,
        )
        Text(
            "Astra",
            color = AurumColors.Gold,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = AuthRepository.displayName ?: "Signed in",
            color = AurumColors.TextPrimary,
            fontSize = 22.sp,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = "Connected to your Astra library",
            color = AurumColors.TextSecondary,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 32.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AccountButton("Sign out") {
                scope.launch {
                    AuthRepository.signOut()
                    onSignedOut()
                    onDismiss()
                }
            }
            AccountButton("Back", onClick = onDismiss)
        }
    }
}

@Composable
private fun AccountButton(label: String, onClick: () -> Unit) {
    val focus = rememberClickFocusRequester()
    val isTouch = isTouchDevice()
    Surface(
        onClick = onClick,
        modifier = Modifier.adaptiveClickable(focus, isTouch, onClick),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AurumColors.AmoledBgSurface,
            focusedContainerColor = AurumColors.AmoledBgElevated,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(border = BorderStroke(2.dp, AurumColors.Gold), shape = RoundedCornerShape(10.dp)),
        ),
    ) {
        Text(
            text = label,
            color = AurumColors.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
        )
    }
}
