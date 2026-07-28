package nl.redlabs.epsonreset.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
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

/** The darkest ink in the palette, for labels sitting on a light fill. */
private val Ink900 = Color(0xFF10161A)

/** Status accents used by the log and result banners, tuned per theme for contrast. */
object StatusColors {
    val good: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF6BD98F) else Color(0xFF1B7A3F)
    val warn: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFE8B84B) else Color(0xFF8A6100)
    val bad: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFFF6B6B) else Color(0xFFB3261E)
    val muted: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF7E8F98) else Color(0xFF6B7A82)

    /**
     * Labels for anything *filled* with the accents above.
     *
     * Which way round they go is a property of the theme rather than of the colour, and the two
     * themes want opposite answers: dark mode tints these light — white on `bad` measures 2.8:1, and
     * on `warn` 1.8:1 — while light mode tints them deep, where white is the only readable choice.
     * A label fixed to one colour is therefore wrong in one theme whichever colour is picked.
     */
    val onBad: Color @Composable get() = if (isSystemInDarkTheme()) Ink900 else Color.White
    val onWarn: Color @Composable get() = if (isSystemInDarkTheme()) Ink900 else Color.White
}

/** A destructive action. Same fill and the same readable label everywhere one appears. */
@Composable
fun dangerButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = StatusColors.bad,
    contentColor = StatusColors.onBad,
)

/** One step short of destructive — a write that is reversible, or a run that will refuse. */
@Composable
fun cautionButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = StatusColors.warn,
    contentColor = StatusColors.onWarn,
)

/** A destructive action that is not the main one on its row, so it is outlined rather than filled. */
@Composable
fun dangerOutline(): ButtonColors = ButtonDefaults.outlinedButtonColors(contentColor = StatusColors.bad)

@Composable
fun EpsonResetTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
