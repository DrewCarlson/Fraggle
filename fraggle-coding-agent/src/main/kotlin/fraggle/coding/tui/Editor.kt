package fraggle.coding.tui

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.LocalTerminalState
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
 * Long logical lines are explicitly hard-wrapped to the terminal width before
 * rendering. Mosaic's layout is driven by the row count it emits; if we hand
 * it a single `Text` that's wider than the terminal, the terminal itself
 * wraps the glyphs to a new visual row but Mosaic still thinks the row count
 * is 1. On the next repaint Mosaic moves up by its (too-small) row count and
 * overwrites the header area. Pre-wrapping keeps the two views in sync.
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
    val terminalColumns = LocalTerminalState.current.size.columns.coerceAtLeast(10)
    // Prefix is 2 cells ("> " or "  "); leave 1 cell of slack so the caret
    // block at end-of-line doesn't itself trigger a wrap.
    val innerWidth = (terminalColumns - 3).coerceAtLeast(1)

    Column {
        Text("─".repeat(terminalColumns.coerceAtMost(80)), color = Theme.divider)
        if (state.isEmpty) {
            Row {
                Text("> ", color = promptColor)
                Text(placeholder, color = Theme.veryDim)
            }
        } else {
            val (cursorLogicalRow, cursorLogicalCol) = rowColOf(state.text, state.cursor)
            val logicalLines = state.text.split('\n')
            var firstPhysicalRow = true
            for (logicalRow in logicalLines.indices) {
                val line = logicalLines[logicalRow]
                val segments = wrapLine(line, innerWidth)
                val cursorOnThisLogicalRow = enabled && logicalRow == cursorLogicalRow
                // Track which segment the cursor falls into.
                val cursorSegmentIndex = if (cursorOnThisLogicalRow) {
                    (cursorLogicalCol / innerWidth).coerceAtMost(segments.lastIndex)
                } else {
                    -1
                }
                val cursorSegmentCol = if (cursorOnThisLogicalRow) cursorLogicalCol % innerWidth else -1

                for (segIndex in segments.indices) {
                    val segment = segments[segIndex]
                    val prefix = if (firstPhysicalRow) "> " else "  "
                    firstPhysicalRow = false
                    Row {
                        Text(prefix, color = promptColor)
                        if (segIndex == cursorSegmentIndex) {
                            val col = cursorSegmentCol.coerceAtMost(segment.length)
                            val before = segment.substring(0, col)
                            val atCursor = if (col < segment.length) segment.substring(col, col + 1) else " "
                            val after = if (col < segment.length) segment.substring(col + 1) else ""
                            Text(before, color = Theme.foreground)
                            Text(atCursor, color = Theme.cursor, background = Theme.dim)
                            Text(after, color = Theme.foreground)
                        } else {
                            Text(segment, color = Theme.foreground)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Hard-wrap [line] into chunks of at most [width] characters. Always returns
 * at least one element so empty logical lines still render.
 */
private fun wrapLine(line: String, width: Int): List<String> {
    if (line.isEmpty()) return listOf("")
    val result = ArrayList<String>(line.length / width + 1)
    var start = 0
    while (start < line.length) {
        val end = (start + width).coerceAtMost(line.length)
        result.add(line.substring(start, end))
        start = end
    }
    return result
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
