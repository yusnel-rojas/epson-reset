package nl.redlabs.epsonreset.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF1B7F9E)
private val InkLight = Color(0xFF5BB8D4)

private val DarkColors = darkColorScheme(
    primary = InkLight,
    onPrimary = Color(0xFF00323F),
    secondary = Color(0xFF8FB8C6),
    background = Color(0xFF10161A),
    surface = Color(0xFF161E23),
    surfaceVariant = Color(0xFF1F2A31),
    onSurfaceVariant = Color(0xFFB9C7CE),
    error = Color(0xFFFF6B6B),
)

private val LightColors = lightColorScheme(
    primary = Ink,
    secondary = Color(0xFF4A6572),
    background = Color(0xFFF6F8F9),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE3EAEE),
    error = Color(0xFFB3261E),
)

/** Status accents used by the log and result banners, tuned per theme for contrast. */
object StatusColors {
    val good: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF6BD98F) else Color(0xFF1B7A3F)
    val warn: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFE8B84B) else Color(0xFF8A6100)
    val bad: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFFF6B6B) else Color(0xFFB3261E)
    val muted: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF7E8F98) else Color(0xFF6B7A82)
}

@Composable
fun EpsonResetTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
