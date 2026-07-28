package nl.redlabs.epsonreset

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import nl.redlabs.epsonreset.prefs.Preferences
import nl.redlabs.epsonreset.prefs.PreferencesStore
import nl.redlabs.epsonreset.prefs.ScreenFit
import nl.redlabs.epsonreset.ui.App

/** How long the window has to hold still before its geometry is worth a disk write. */
private const val GEOMETRY_SETTLE_MS = 600L

fun main() = application {
    val prefs = remember { PreferencesStore.current() }
    val state = rememberWindowState(
        size = DpSize(prefs.windowWidth.dp, prefs.windowHeight.dp),
        position = remember {
            ScreenFit.positionFor(prefs)
                ?.let { (x, y) -> WindowPosition(x.dp, y.dp) }
                ?: WindowPosition.PlatformDefault
        },
        placement = if (prefs.maximized) WindowPlacement.Maximized else WindowPlacement.Floating,
    )

    // A drag emits a position every frame.
    LaunchedEffect(state) {
        snapshotFlow { Geometry.of(state) }.collectLatest { geometry ->
            delay(GEOMETRY_SETTLE_MS)
            PreferencesStore.update { geometry.applyTo(it) }
        }
    }

    Window(
        onCloseRequest = {
            // The debounce above may still be holding the last move. Closing is the one moment
            // where there is no next chance to write it.
            PreferencesStore.update { Geometry.of(state).applyTo(it) }
            exitApplication()
        },
        title = "Epson Reset",
        state = state,
    ) {
        App()
    }
}

/** The part of the window state worth remembering. */
private data class Geometry(val width: Int?, val height: Int?, val x: Int?, val y: Int?, val maximized: Boolean) {
    fun applyTo(prefs: Preferences): Preferences = prefs.copy(
        windowWidth = width ?: prefs.windowWidth,
        windowHeight = height ?: prefs.windowHeight,
        windowX = x ?: prefs.windowX,
        windowY = y ?: prefs.windowY,
        maximized = maximized,
    )

    companion object {
        fun of(state: WindowState): Geometry {
            val floating = state.placement == WindowPlacement.Floating && !state.isMinimized
            val size = state.size.takeIf { floating && it.isSpecified }
            // Unspecified until the platform has actually placed the window — PlatformDefault
            // reports Dp.Unspecified for both coordinates.
            val position = state.position.takeIf { floating && it.isSpecified }

            return Geometry(
                width = size?.width?.value?.toInt(),
                height = size?.height?.value?.toInt(),
                x = position?.x?.value?.toInt(),
                y = position?.y?.value?.toInt(),
                maximized = state.placement == WindowPlacement.Maximized,
            )
        }
    }
}
