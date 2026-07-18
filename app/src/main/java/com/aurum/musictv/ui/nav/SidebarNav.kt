package com.aurum.musictv.ui.nav

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.aurum.musictv.ui.theme.AurumColors

enum class NavDestination(
    val label: String,
    val filledIcon: ImageVector,
    val outlinedIcon: ImageVector,
) {
    HOME("Home", Icons.Filled.Home, Icons.Outlined.Home),
    SEARCH("Search", Icons.Filled.Search, Icons.Outlined.Search),
    LIBRARY("Library", Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic),
}

/**
 * Persistent left rail, Spotify-TV style — always visible next to Home/
 * Search/Library content instead of those being full-screen swaps reached
 * only via a top-bar icon. Narrow icon-only rail (not a full labeled
 * sidebar): TV screens are landscape and wide, but D-pad users still
 * benefit from a tight, low-travel-distance rail rather than a wide panel
 * eating into content width.
 *
 * Uses real Material icon glyphs (filled when selected, outlined
 * otherwise — same visual language as Spotify/YouTube Music's own nav)
 * instead of text/emoji glyphs, which is what made this rail read as
 * "placeholder" rather than a finished nav element.
 *
 * NOT shown on the Player or Settings screens (see MainActivity) —
 * matches Spotify TV, where full-screen "now playing" and settings both
 * intentionally drop the rail to maximize their own content.
 */
@Composable
fun SidebarNav(
    current: NavDestination,
    onNavigate: (NavDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(96.dp)
            .background(AurumColors.AmoledBgCard)
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(AurumColors.Gold, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "A",
                color = AurumColors.AmoledBg,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
            )
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NavDestination.entries.forEach { destination ->
                SidebarIcon(
                    destination = destination,
                    selected = destination == current,
                    onClick = { onNavigate(destination) },
                )
            }
        }
    }
}

@Composable
private fun SidebarIcon(destination: NavDestination, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(
            // Selected state gets a real filled pill (subtle gold-tinted
            // surface, not just a color change on the glyph) so "which
            // screen am I on" is legible at a glance from a couch's
            // distance — a lone colored icon is too small a signal on a
            // TV-sized display.
            containerColor = if (selected) AurumColors.GoldDark.copy(alpha = 0.18f) else Color.Transparent,
            focusedContainerColor = AurumColors.AmoledBgElevated,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, AurumColors.Gold),
                shape = RoundedCornerShape(16.dp),
            ),
        ),
        modifier = Modifier.width(76.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = if (selected) destination.filledIcon else destination.outlinedIcon,
                contentDescription = destination.label,
                tint = if (selected) AurumColors.Gold else AurumColors.TextSecondary,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = destination.label,
                fontSize = 11.sp,
                color = if (selected) AurumColors.Gold else AurumColors.TextMuted,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}
