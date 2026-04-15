package fraggle.coding.tui

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text

/**
 * Top-of-screen banner. Renders a single-line strip with the app name, a
 * hotkeys hint, and optional metadata (context file count, model). Kept
 * intentionally minimal — a big header eats message rows.
 */
@Composable
fun Header(info: HeaderInfo) {
    Column {
        Text("── fraggle code ─────────────────────────────────────────", color = Theme.accent)
        Row {
            Text("  ", color = Theme.dim)
            Text("?: /hotkeys", color = Theme.dim)
            if (info.contextFileCount > 0) {
                Text("  •  ", color = Theme.veryDim)
                Text("${info.contextFileCount} context file${if (info.contextFileCount == 1) "" else "s"}", color = Theme.dim)
            }
            if (info.model.isNotBlank()) {
                Text("  •  ", color = Theme.veryDim)
                Text(info.model, color = Theme.dim)
            }
            if (info.supervisionLabel.isNotBlank()) {
                Text("  •  ", color = Theme.veryDim)
                Text("supervision: ${info.supervisionLabel}", color = Theme.dim)
            }
        }
        Text("", color = Theme.foreground) // spacer
    }
}

/**
 * Immutable data bundle for the header. Constructed by the CLI command at
 * startup; the header doesn't know how to compute any of it.
 */
data class HeaderInfo(
    val model: String,
    val contextFileCount: Int,
    val supervisionLabel: String,
)
