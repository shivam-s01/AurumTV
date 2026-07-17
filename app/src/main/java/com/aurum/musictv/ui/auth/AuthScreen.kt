package com.aurum.musictv.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.aurum.musictv.sync.AuthRepository
import com.aurum.musictv.ui.theme.AurumColors
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(onSignedIn: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

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
        Text(
            text = "Sign in with the same Google account you use on your phone.\nYour library, playlists, and playback pick up right where you left off.",
            color = AurumColors.TextSecondary,
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 16.dp, bottom = 40.dp),
        )

        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(AurumColors.Gold)
                .clickable(enabled = !isLoading) {
                    isLoading = true
                    errorMessage = null
                    scope.launch {
                        val error = AuthRepository.signInWithGoogle(context)
                        isLoading = false
                        if (error == null) onSignedIn() else errorMessage = error
                    }
                }
                .padding(horizontal = 32.dp, vertical = 14.dp),
        ) {
            Text(
                text = if (isLoading) "Signing in..." else "Sign in with Google",
                color = AurumColors.AmoledBg,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
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
    }
}
