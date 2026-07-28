package nl.redlabs.epsonreset.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** One entry in a [SplitButton]'s dropdown. A [divider] entry renders as a separator line. */
data class SplitAction(
    val label: String,
    val enabled: Boolean = true,
    val divider: Boolean = false,
    val onSelect: () -> Unit = {},
)

/** A separator line between groups of dropdown entries. */
fun splitDivider(): SplitAction = SplitAction(label = "", divider = true)

/** A primary button with an attached chevron listing secondary [actions]. */
@Composable
fun SplitButton(
    label: String,
    actions: List<SplitAction>,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    primaryEnabled: Boolean = true,
) {
    var menu by remember { mutableStateOf(false) }
    val menuEnabled = actions.any { !it.divider && it.enabled }
    val shape = RoundedCornerShape(50)
    val content = MaterialTheme.colorScheme.onPrimary

    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.primary),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(enabled = primaryEnabled, onClick = onPrimary)
                    .alpha(if (primaryEnabled) 1f else 0.4f)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = content,
                )
            }

            VerticalDivider(
                Modifier.fillMaxHeight().width(1.dp),
                color = Color.Black.copy(alpha = 0.22f),
            )

            Box(
                Modifier
                    .width(38.dp)
                    .fillMaxHeight()
                    .clickable(enabled = menuEnabled) { menu = !menu }
                    .alpha(if (menuEnabled) 1f else 0.4f),
                contentAlignment = Alignment.Center,
            ) {
                Chevron(content)
            }
        }

        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            for (action in actions) {
                if (action.divider) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                } else {
                    Box(
                        Modifier
                            .widthIn(min = 200.dp)
                            .height(36.dp)
                            .clickable(enabled = action.enabled) {
                                menu = false
                                action.onSelect()
                            }
                            .alpha(if (action.enabled) 1f else 0.4f)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            action.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** Drawn rather than pulled from material-icons-extended, which this project doesn't depend on. */
@Composable
private fun Chevron(color: Color) {
    Canvas(Modifier.size(12.dp)) {
        val stroke = 1.5.dp.toPx()
        drawLine(
            color,
            Offset(size.width * 0.2f, size.height * 0.38f),
            Offset(size.width * 0.5f, size.height * 0.68f),
            stroke,
            StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(size.width * 0.5f, size.height * 0.68f),
            Offset(size.width * 0.8f, size.height * 0.38f),
            stroke,
            StrokeCap.Round,
        )
    }
}
