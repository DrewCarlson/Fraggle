package fraggle.coding.tui

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text

/**
 * Inline approval panel shown when the `ask` supervisor has a pending
 * tool call waiting for the user's Y/N.
 *
 * Mosaic's layout model doesn't have true floating overlays, so we render
 * this as a prominent block placed above the editor when
 * [TuiToolPermissionHandler.pending] is non-null. The CodingApp's key
 * handler intercepts Y/N while the overlay is active and routes the answer
 * back to the handler; everything else is ignored.
 *
 * The display is intentionally loud — warning color, clear borders — so
 * users never accidentally fire off a tool call without realizing.
 */
@Composable
fun ApprovalOverlay(pending: PendingApproval) {
    Column {
        Text("╭─── tool approval ───────────────────────────────────────╮", color = Theme.warning)
        Row {
            Text("│ ", color = Theme.warning)
            Text("tool: ", color = Theme.veryDim)
            Text(pending.toolName, color = Theme.toolCall)
        }
        Row {
            Text("│ ", color = Theme.warning)
            Text("args: ", color = Theme.veryDim)
            Text(compactArgs(pending.argsJson), color = Theme.dim)
        }
        Row {
            Text("│ ", color = Theme.warning)
            Text("approve? ", color = Theme.foreground)
            Text("[y]", color = Theme.success)
            Text(" yes   ", color = Theme.dim)
            Text("[n]", color = Theme.error)
            Text(" no", color = Theme.dim)
        }
        Text("╰─────────────────────────────────────────────────────────╯", color = Theme.warning)
    }
}

private fun compactArgs(json: String): String {
    val compact = json.replace(Regex("\\s+"), " ").trim()
    return if (compact.length <= 100) compact else compact.substring(0, 99) + "…"
}
