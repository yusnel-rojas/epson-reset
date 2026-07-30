package nl.redlabs.epsonreset.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Height of the log when open; collapsed it keeps only its header bar. */
private val EXPANDED_HEIGHT = 200.dp
private val COLLAPSED_HEIGHT = 34.dp

/**
 * Activity log and hardware trace. The trace is the diagnostic that matters when a reset fails, so
 * it's copyable rather than buried in a file next to the binary.
 */
@Composable
fun LogPanel(vm: ResetViewModel, modifier: Modifier = Modifier) {
    var showTrace by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val collapsed = vm.logCollapsed
    val operationLabel = vm.globalProgressLabel
    val operationProgress = vm.globalProgressValue

    val visible = remember(vm.log.size, showTrace) {
        if (showTrace) {
            vm.log.toList()
        } else {
            vm.log.filter { it.level != ResetViewModel.Level.TRACE }
        }
    }

    LaunchedEffect(visible.size, collapsed) {
        if (!collapsed && visible.isNotEmpty()) listState.animateScrollToItem(visible.lastIndex)
    }

    Box(
        modifier
            .height(if (collapsed) COLLAPSED_HEIGHT else EXPANDED_HEIGHT)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { vm.logCollapsed = !collapsed }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The whole bar is the hit target; the chevron is just what says so.
                Text(
                    if (collapsed) "▸" else "▾",
                    style = MaterialTheme.typography.labelLarge,
                    color = StatusColors.muted,
                    modifier = Modifier.width(16.dp),
                )
                Text(
                    "Log",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )

                if (collapsed) {
                    // Collapsed, the header is the status line. An active operation is more useful
                    // than the last completed log entry and disappears without changing its height.
                    Spacer(Modifier.width(12.dp))
                    Text(
                        operationLabel ?: visible.lastOrNull()?.text.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = operationLabel?.let { MaterialTheme.colorScheme.primary }
                            ?: visible.lastOrNull()?.let { levelColour(it.level) }
                            ?: StatusColors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.logCollapsed = false }) { Text("Show") }
                } else {
                    Spacer(Modifier.width(12.dp))
                    if (operationLabel != null) {
                        Text(
                            operationLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    TextButton(onClick = { showTrace = !showTrace }) {
                        Text(if (showTrace) "Hide packet trace" else "Show packet trace")
                    }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(vm.exportLog())) }) {
                        Text("Copy")
                    }
                    TextButton(onClick = { vm.clearLog() }) { Text("Clear") }
                    TextButton(onClick = { vm.logCollapsed = true }) { Text("Hide") }
                }
            }

            if (!collapsed) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                ) {
                    items(visible) { line ->
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            Text(
                                line.time,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = StatusColors.muted,
                                modifier = Modifier.width(64.dp),
                            )
                            Text(
                                line.text,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = levelColour(line.level),
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
        }

        if (operationLabel != null) {
            if (operationProgress == null || operationProgress <= 0f) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter),
                )
            } else {
                LinearProgressIndicator(
                    progress = { operationProgress },
                    modifier = Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter),
                )
            }
        }
    }
}

@Composable
private fun levelColour(level: ResetViewModel.Level) = when (level) {
    ResetViewModel.Level.GOOD -> StatusColors.good
    ResetViewModel.Level.WARN -> StatusColors.warn
    ResetViewModel.Level.BAD -> StatusColors.bad
    ResetViewModel.Level.TRACE -> StatusColors.muted
    ResetViewModel.Level.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
}
