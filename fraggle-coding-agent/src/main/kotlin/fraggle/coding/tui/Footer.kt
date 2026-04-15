package fraggle.coding.tui

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Spacer
import com.jakewharton.mosaic.ui.Text
import java.nio.file.Path

/**
 * Bottom-of-screen status bar.
 *
 * Row 1 (full width): cwd on the left, session id / tokens / context ratio /
 * status pushed to the right edge.
 * Row 2 (left only): supervision mode.
 */
@Composable
fun Footer(info: FooterInfo) {
    val terminalColumns = LocalTerminalState.current.size.columns.coerceAtLeast(10)
    Column {
        Text("─".repeat(terminalColumns), color = Theme.divider)
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(" ", color = Theme.dim)
            Text(shortPath(info.cwd), color = Theme.dim)
            Spacer(modifier = Modifier.weight(1f))
            Text(info.sessionId.take(8), color = Theme.dim)
            Text("  ", color = Theme.veryDim)
            Text("${info.usedTokens} tok", color = Theme.dim)
            if (info.contextRatio > 0.0) {
                Text("  ", color = Theme.veryDim)
                val pct = (info.contextRatio * 100).toInt()
                val color = when {
                    info.contextRatio >= 0.85 -> Theme.error
                    info.contextRatio >= 0.70 -> Theme.warning
                    else -> Theme.dim
                }
                Text("${pct}% ctx", color = color)
            }
            Text("  ", color = Theme.veryDim)
            Text(info.status.label, color = info.status.color)
            Text(" ", color = Theme.dim)
        }
        if (info.supervisionLabel.isNotBlank()) {
            Row {
                Text(" supervision: ", color = Theme.veryDim)
                Text(info.supervisionLabel, color = Theme.dim)
            }
        }
    }
}

data class FooterInfo(
    val cwd: Path,
    val sessionId: String,
    val usedTokens: Int,
    /** Fraction of the context window in use; 0.0 when unknown. */
    val contextRatio: Double,
    val status: FooterStatus,
    val supervisionLabel: String = "",
)

enum class FooterStatus(val label: String, val color: Color) {
    IDLE("idle", Theme.dim),
    BUSY("thinking...", Theme.accent),
    COMPACTING("compacting...", Theme.warning),
    AWAITING_APPROVAL("awaiting approval", Theme.warning),
    ERROR("error", Theme.error),
}

/**
 * Render a filesystem path compactly for the footer. Shows the last two
 * path components if the full absolute path would be too long.
 */
private fun shortPath(path: Path): String {
    val abs = path.toAbsolutePath().toString()
    if (abs.length <= 40) return abs
    val parts = path.toAbsolutePath()
    val count = parts.nameCount
    return if (count >= 2) {
        ".../${parts.getName(count - 2)}/${parts.getName(count - 1)}"
    } else {
        abs.takeLast(40)
    }
}
