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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.redlabs.epsonreset.protocol.CounterReader

/** Read-only decoded counters and raw bytes nested inside Overview. */
@Composable
internal fun CounterDetailsContent(vm: ResetViewModel) {
    val model = vm.selectedModel

    Column {
        Text(
            "Current values and EEPROM bytes. This view is read-only; reset actions remain under Maintenance.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (model == null) {
            Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No counter layout selected",
                    style = MaterialTheme.typography.titleMedium,
                    color = StatusColors.muted,
                )
            }
            return@Column
        }

        // A simulated reset report must never appear as current printer data. Prefer the latest
        // real read, then the session Overview sample, and finally the model's address layout.
        val report = vm.readReport?.takeIf { !vm.readWasSimulated }
            ?: vm.overviewReading?.counters
            ?: CounterReader.layout(model, vm.specsFor(model))
        val counters = CounterReader.decode(report.readings, vm.specsFor(model))

        Spacer(Modifier.height(12.dp))
        CounterOverview(
            counters = counters,
            report = report,
            plan = WritePlan.None,
            simulated = false,
            onCalibrate = if (report.answered > 0 && !vm.reading) vm.calibration::open else null,
        )
        CalibrationDialog(vm)

        Spacer(Modifier.height(14.dp))
        CounterSnapshotsFooter(vm, model.name)
    }
}

@Composable
private fun CounterSnapshotsFooter(vm: ResetViewModel, modelName: String) {
    val count = vm.snapshot.snapshotsForSelectedModel.size
    val noun = if (count == 1) "snapshot" else "snapshots"

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .clickable { vm.snapshot.openSelectedModelSnapshots() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "$count saved $noun for $modelName",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (count == 0) {
                    "Open Snapshots to capture the current bytes or inspect other models."
                } else {
                    "Open the snapshots for this model to compare or restore saved bytes."
                },
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.muted,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            "View snapshots →",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
