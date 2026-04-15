package fraggle.coding.tui

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text

/**
 * Multi-line input editor. Renders the current [state] as text with a
 * visible caret at [EditorState.cursor].
 *
 * Key handling lives at the `CodingApp` root in a single `Modifier.onKeyEvent`
 * block — this composable is pure render. That's intentional: the editor
 * state is shared with the orchestrator (for slash command dispatch, submit,
 * etc.) and having a single-source key handler avoids "where does this key go"
 * confusion when the approval overlay or message list is focused.
 *
 * [enabled] controls the prompt color — when the agent is busy we dim the
 * prompt to cue the user that input is queued, not sent immediately.
 * [placeholder] shows when the buffer is empty and the editor has focus.
 */
@Composable
fun Editor(
    state: EditorState,
    enabled: Boolean,
    placeholder: String = "type a message, /command, or press Esc to cancel",
) {
    val promptColor = if (enabled) Theme.accent else Theme.veryDim
    Column {
        Text("─────────────────────────────────────────────────────────", color = Theme.divider)
        if (state.isEmpty) {
            Row {
                Text("> ", color = promptColor)
                Text(placeholder, color = Theme.veryDim)
            }
        } else {
            // Render lines with the cursor overlayed on whichever line it's on.
            val lines = state.text.split('\n')
            val (cursorRow, cursorCol) = rowColOf(state.text, state.cursor)
            for (row in lines.indices) {
                val line = lines[row]
                val prefix = if (row == 0) "> " else "  "
                Row {
                    Text(prefix, color = promptColor)
                    if (row == cursorRow && enabled) {
                        // Split the line at the cursor and insert a caret marker.
                        val before = line.substring(0, cursorCol.coerceAtMost(line.length))
                        val atCursor = if (cursorCol < line.length) line.substring(cursorCol, cursorCol + 1) else " "
                        val after = if (cursorCol < line.length) line.substring(cursorCol + 1) else ""
                        Text(before, color = Theme.foreground)
                        Text(atCursor, color = Theme.cursor, background = Theme.dim)
                        Text(after, color = Theme.foreground)
                    } else {
                        Text(line, color = Theme.foreground)
                    }
                }
            }
        }
    }
}

/**
 * Compute (row, col) for [offset] inside [text]. Mirror of the logic inside
 * [EditorState] — factored out here so the Composable doesn't have to touch
 * the private methods on the state class.
 */
private fun rowColOf(text: String, offset: Int): Pair<Int, Int> {
    var row = 0
    var col = 0
    for (i in 0 until offset.coerceAtMost(text.length)) {
        if (text[i] == '\n') {
            row++
            col = 0
        } else {
            col++
        }
    }
    return row to col
}
