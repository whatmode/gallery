package ua.volodymyr.nanocrew.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import ua.volodymyr.nanocrew.core.Role

// "Mission control" palette: near-black slate, a mint accent for the chip, one colour per agent.
private val Mint = Color(0xFF5FD3A8)

private val Dark = darkColorScheme(
    primary = Mint,
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF13261F),
    onPrimaryContainer = Color(0xFFB7F2DA),
    secondary = Color(0xFF9DB4C8),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE3E7EC),
    surface = Color(0xFF0E1116),
    onSurface = Color(0xFFE3E7EC),
    surfaceVariant = Color(0xFF1A1F27),
    onSurfaceVariant = Color(0xFF9AA4B0),
    surfaceContainer = Color(0xFF151A21),
    surfaceContainerHigh = Color(0xFF1C222B),
    surfaceContainerHighest = Color(0xFF232A34),
    outline = Color(0xFF3A434F),
    outlineVariant = Color(0xFF262D37),
    error = Color(0xFFFF8A80),
)

private val Light = lightColorScheme(
    primary = Color(0xFF0E7A57),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F5E6),
    onPrimaryContainer = Color(0xFF00382A),
    background = Color(0xFFF6F7F9),
    surface = Color(0xFFF6F7F9),
    surfaceVariant = Color(0xFFE8ECF0),
    surfaceContainer = Color(0xFFEFF2F5),
    surfaceContainerHigh = Color(0xFFE8ECF0),
    surfaceContainerHighest = Color(0xFFE0E5EA),
    outlineVariant = Color(0xFFD5DBE1),
)

@Composable
fun NanoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

/** Each agent gets its own colour in the timeline. */
@Composable
fun roleColor(role: Role): Color {
    val dark = isSystemInDarkTheme()
    return when (role) {
        Role.READER -> if (dark) Color(0xFFFFB86B) else Color(0xFFA24E00)
        Role.ROUTER -> if (dark) Color(0xFF9DB4C8) else Color(0xFF4F6578)
        Role.PLANNER -> if (dark) Color(0xFFF2C14E) else Color(0xFF9A6B00)
        Role.WORKER -> if (dark) Color(0xFF7FB2FF) else Color(0xFF2F62B8)
        Role.TOOL -> if (dark) Color(0xFF5FD3A8) else Color(0xFF0E7A57)
        Role.CRITIC -> if (dark) Color(0xFFFF8A80) else Color(0xFFB3261E)
        Role.EDITOR -> if (dark) Color(0xFF6FE3E1) else Color(0xFF00696B)
        Role.WRITER -> if (dark) Color(0xFFC9A7FF) else Color(0xFF6B3FB8)
    }
}

val MonoSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp)
