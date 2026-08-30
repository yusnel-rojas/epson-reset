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
import nl.redlabs.epsonreset.resources.Res
import nl.redlabs.epsonreset.resources.details_intro
import nl.redlabs.epsonreset.resources.details_no_layout
import nl.redlabs.epsonreset.resources.details_open_model_snapshots
import nl.redlabs.epsonreset.resources.details_open_snapshots
import nl.redlabs.epsonreset.resources.details_saved_snapshots
import nl.redlabs.epsonreset.resources.details_view_snapshots
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Read-only decoded counters and raw bytes nested inside Overview. */
@Composable
internal fun CounterDetailsContent(vm: ResetViewModel) {
    val model = vm.selectedModel

    Column {
        Text(
            stringResource(Res.string.details_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (model == null) {
            Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(Res.string.details_no_layout),
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
                pluralStringResource(Res.plurals.details_saved_snapshots, count, count, modelName),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (count == 0) {
                    stringResource(Res.string.details_open_snapshots)
                } else {
                    stringResource(Res.string.details_open_model_snapshots)
                },
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.muted,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(Res.string.details_view_snapshots),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
