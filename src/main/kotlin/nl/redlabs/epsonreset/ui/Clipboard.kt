package nl.redlabs.epsonreset.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch
import java.awt.datatransfer.StringSelection

/**
 * Puts text on the system clipboard.
 *
 * The clipboard is a suspending interface now — a copy can wait on the platform — while every
 * caller here is a button's onClick. This bridges the two once, so three panels don't each carry a
 * scope and a launch to copy a string.
 */
// The desktop ClipEntry wraps an AWT Transferable, and that constructor is still experimental.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun rememberClipboardCopy(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    return remember(clipboard, scope) {
        { text ->
            scope.launch { clipboard.setClipEntry(ClipEntry(StringSelection(text))) }
            Unit
        }
    }
}
