package nl.redlabs.epsonreset.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The application-scoped printer-and-model target, visible whichever tab is open. */
@Composable
fun PrinterChip(vm: ResetViewModel, modifier: Modifier = Modifier) {
    var menuExpanded by remember { mutableStateOf(false) }
    val selected = vm.selectedDevice?.device
    val scanning = vm.scanState is ResetViewModel.ScanState.Scanning
    val model = vm.selectedModel
    val targetReady = selected != null && model != null && vm.pendingClass == null && vm.modelMismatch == null
    val reachable = selected?.reachable == true
    val tone = when {
        selected != null && !reachable -> StatusColors.muted
        targetReady -> StatusColors.good
        else -> StatusColors.warn
    }
    val detail = when {
        selected != null && !reachable -> "Saved · not reached · ${model?.name ?: "choose model"}"
        vm.pendingClass != null -> "${selected?.link?.kind ?: "Printer"} · choose model"
        selected != null && model != null -> "${selected.link.kind} · ${model.name}"
        selected != null -> "${selected.link.kind} · choose model"
        model != null -> "No printer · ${model.name}"
        else -> "Select printer and model"
    }

    Box(modifier) {
        Row(
            Modifier
                .widthIn(max = 190.dp)
                .height(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(tone.copy(alpha = 0.10f))
                .border(1.dp, tone.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                // The menu is also the home of connection progress and results. Keep it readable
                // during an operation; the individual target-changing controls remain disabled.
                .clickable { menuExpanded = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(11.dp))
            if (scanning) {
                CircularProgressIndicator(Modifier.size(15.dp), color = tone, strokeWidth = 2.dp)
            } else {
                Box(Modifier.size(8.dp).clip(CircleShape).background(tone))
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    selected?.displayName ?: "No printer",
                    style = MaterialTheme.typography.labelMedium,
                    color = tone,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusColors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(11.dp))
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            modifier = Modifier.width(380.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                PrinterSelectorContent(
                    vm = vm,
                    modifier = Modifier.fillMaxWidth(),
                    onModelSelected = { menuExpanded = false },
                )
            }
        }
    }
}
