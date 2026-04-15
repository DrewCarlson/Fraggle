package fraggle.coding.tui

/**
 * Pure-data editor state for the TUI input.
 *
 * The editor is a simple multi-line text buffer with a cursor. It supports:
 *  - [type]: insert a single character at the cursor
 *  - [backspace]: delete the character behind the cursor
 *  - [newline]: insert a literal newline at the cursor (for Shift+Enter)
 *  - [moveLeft]/[moveRight]/[moveUp]/[moveDown]: cursor navigation
 *  - [moveHome]/[moveEnd]: start/end of current line
 *  - [clear]: wipe the buffer
 *
 * Everything returns a fresh [EditorState] so the state transitions are
 * compositional and trivially testable. The Mosaic Composable reads [text]
 * for rendering and [cursor] for the fake caret position.
 *
 * Design note: the cursor is a *character offset* into [text], not a
 * line/column pair. Lines are derived on demand. This keeps the state flat
 * and avoids resync bugs where line/column diverge from the underlying
 * string after an edit.
 */
data class EditorState(
    val text: String = "",
    val cursor: Int = 0,
) {
    init {
        require(cursor in 0..text.length) {
            "cursor $cursor out of range [0, ${text.length}]"
        }
    }

    /** True when the buffer is completely empty. */
    val isEmpty: Boolean get() = text.isEmpty()

    /** Insert [c] at the cursor position and advance the cursor one column. */
    fun type(c: Char): EditorState = copy(
        text = text.substring(0, cursor) + c + text.substring(cursor),
        cursor = cursor + 1,
    )

    /** Insert a string at the cursor position (e.g. from a bracketed paste). */
    fun typeString(s: String): EditorState {
        if (s.isEmpty()) return this
        return copy(
            text = text.substring(0, cursor) + s + text.substring(cursor),
            cursor = cursor + s.length,
        )
    }

    /** Insert a newline at the cursor. Used for Shift+Enter multi-line input. */
    fun newline(): EditorState = type('\n')

    /** Delete the character before the cursor. No-op if cursor is at 0. */
    fun backspace(): EditorState {
        if (cursor == 0) return this
        return copy(
            text = text.substring(0, cursor - 1) + text.substring(cursor),
            cursor = cursor - 1,
        )
    }

    /** Delete the character at the cursor. No-op if cursor is at the end. */
    fun delete(): EditorState {
        if (cursor == text.length) return this
        return copy(
            text = text.substring(0, cursor) + text.substring(cursor + 1),
            cursor = cursor,
        )
    }

    fun moveLeft(): EditorState =
        if (cursor > 0) copy(cursor = cursor - 1) else this

    fun moveRight(): EditorState =
        if (cursor < text.length) copy(cursor = cursor + 1) else this

    /**
     * Move the cursor up one line, preserving the column if possible. If the
     * line above is shorter, clamp to its end. If already on the first line,
     * stay put.
     */
    fun moveUp(): EditorState {
        val (row, col) = rowColOf(cursor)
        if (row == 0) return this
        val lines = text.split('\n')
        val prevLine = lines[row - 1]
        val newCol = minOf(col, prevLine.length)
        val newCursor = offsetOf(row - 1, newCol, lines)
        return copy(cursor = newCursor)
    }

    /**
     * Move the cursor down one line, preserving the column if possible. If
     * the line below is shorter, clamp to its end. If already on the last
     * line, stay put.
     */
    fun moveDown(): EditorState {
        val (row, col) = rowColOf(cursor)
        val lines = text.split('\n')
        if (row >= lines.size - 1) return this
        val nextLine = lines[row + 1]
        val newCol = minOf(col, nextLine.length)
        val newCursor = offsetOf(row + 1, newCol, lines)
        return copy(cursor = newCursor)
    }

    /** Move the cursor to the start of the current line. */
    fun moveHome(): EditorState {
        val (row, _) = rowColOf(cursor)
        val lines = text.split('\n')
        return copy(cursor = offsetOf(row, 0, lines))
    }

    /** Move the cursor to the end of the current line. */
    fun moveEnd(): EditorState {
        val (row, _) = rowColOf(cursor)
        val lines = text.split('\n')
        return copy(cursor = offsetOf(row, lines[row].length, lines))
    }

    /** Wipe the buffer and reset the cursor. */
    fun clear(): EditorState = EditorState("", 0)

    /**
     * Return the (zero-based row, zero-based column) of [offset] in [text].
     * Used internally for multi-line cursor navigation.
     */
    private fun rowColOf(offset: Int): Pair<Int, Int> {
        var row = 0
        var col = 0
        for (i in 0 until offset) {
            if (text[i] == '\n') {
                row++
                col = 0
            } else {
                col++
            }
        }
        return row to col
    }

    /** Convert a (row, col) position back to a character offset in [text]. */
    private fun offsetOf(row: Int, col: Int, lines: List<String>): Int {
        var offset = 0
        for (r in 0 until row) {
            offset += lines[r].length + 1 // +1 for the '\n'
        }
        return offset + col.coerceAtMost(lines[row].length)
    }
}
