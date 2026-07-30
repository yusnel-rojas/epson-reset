package nl.redlabs.epsonreset.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState

/** One consistent final gate for every action that writes EEPROM. */
@Composable
internal fun EepromWriteConfirmation(
    title: String,
    headline: String,
    metadata: String,
    paragraphs: List<String>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    warning: String? = null,
    confirmLabel: String = "Yes, write EEPROM",
) {
    DialogWindow(
        onCloseRequest = onDismiss,
        state = rememberDialogState(size = DpSize(560.dp, 460.dp)),
        title = title,
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Text(headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    metadata,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = StatusColors.muted,
                )

                warning?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = StatusColors.warn)
                }

                Spacer(Modifier.height(16.dp))
                paragraphs.forEachIndexed { index, paragraph ->
                    if (index > 0) Spacer(Modifier.height(10.dp))
                    Text(
                        paragraph,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onDismiss) { Text("Back") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onConfirm, colors = dangerButtonColors()) { Text(confirmLabel) }
                }
            }
        }
    }
}
