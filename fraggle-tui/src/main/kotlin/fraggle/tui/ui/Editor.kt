package fraggle.tui.ui

import com.jakewharton.mosaic.terminal.KeyboardEvent
import fraggle.tui.core.CURSOR_MARKER
import fraggle.tui.core.Component
import fraggle.tui.core.Focusable
import fraggle.tui.input.isPrintable
import fraggle.tui.input.matches
import fraggle.tui.text.Ansi
import fraggle.tui.text.padRightToWidth
import fraggle.tui.text.truncateToWidth
import fraggle.tui.text.visibleWidth
import fraggle.tui.theme.theme

/**
 * Multi-line interactive text editor.
 *
 * The editor presents a divider row followed by the current buffer contents,
 * prefixed with `"> "` on the first visual row and `"  "` on any wrapped
 * continuation rows. Long logical lines are hard-wrapped to [width] - 3 cells
 * so the prompt + a one-cell caret slack fit inside the viewport without
 * triggering terminal auto-wrap (which would desynchronize the differential
 * renderer's row count from the terminal).
 *
 * State model:
 *  - A [StringBuilder] holds the full text, with `'\n'` as the only logical
 *    line separator. No line array, no column cache — every edit is a
 *    character-level splice, and (row, col) is derived on demand when needed.
 *  - An [Int] offset tracks the cursor as a character position in the buffer.
 *
 * This keeps state transitions trivially correct: a single invariant
 * (`cursor in 0..length`) covers the whole editor. The render path is the
 * only place that cares about visual rows; editing operations never need to
 * resynchronize a derived view.
 *
 * Focus + hardware cursor: when [focused], the renderer emits [CURSOR_MARKER]
 * at the caret position, which the [fraggle.tui.core.TUI] runtime extracts and
 * uses to position the terminal's real cursor. Unfocused editors render no
 * marker; the hardware cursor is hidden anyway, but the contract is that a
 * focused editor is the only source of a cursor.
 *
 * Deferred from v1: autocomplete, undo, kill ring, history scrubbing,
 * grapheme-aware cursor motion (we step by UTF-16 chars for now),
 * and bracketed-paste-aware line-join heuristics.
 */
class Editor(
    placeholder: String = "type a message, /command, or press Esc to cancel",
    paddingX: Int = 0,
    onSubmit: (String) -> Unit = {},
) : Component, Focusable {

    override var focused: Boolean = false

    private val buffer: StringBuilder = StringBuilder()
    private var cursor: Int = 0

    private var placeholder: String = placeholder
    private val paddingX: Int = paddingX.coerceAtLeast(0)
    private var onSubmit: (String) -> Unit = onSubmit
    private var enabled: Boolean = true

    /** True while a bracketed-paste block is being received. */
    private var inPaste: Boolean = false

    // ─── Autocomplete ──────────────────────────────────────────────────────

    private var autocompleteProvider: AutocompleteProvider? = null
    private var autocompleteState: AutocompleteState? = null

    /**
     * Maximum rows shown in the autocomplete popup. The provider may return
     * more entries than this; we truncate to fit. Also used as the `limit`
     * argument passed to the provider.
     */
    private var autocompleteCap: Int = 8

    /**
     * Snapshot of an active autocomplete session.
     *
     * @property trigger The character that opened the popup — typically '@'.
     * @property anchor Position of [trigger] within the buffer. The prefix
     *   being completed is always `buffer.substring(anchor + 1, cursor)`.
     *   When the buffer is edited such that this window is no longer valid
     *   (cursor moves left of anchor, trigger char is deleted, whitespace
     *   enters the prefix), the state is dismissed.
     * @property prefix Text after the trigger up to the cursor.
     * @property completions Provider-supplied completions for this prefix.
     * @property selectedIndex Which completion is currently highlighted.
     */
    private data class AutocompleteState(
        val trigger: Char,
        val anchor: Int,
        val prefix: String,
        val completions: List<Autocompletion>,
        val selectedIndex: Int,
    )

    // ─── Public API ────────────────────────────────────────────────────────

    /** Current buffer content. */
    fun text(): String = buffer.toString()

    /** Replace the buffer; cursor moves to end. */
    fun setText(text: String) {
        buffer.setLength(0)
        buffer.append(text)
        cursor = buffer.length
    }

    /** Replace with empty buffer. */
    fun clear() {
        buffer.setLength(0)
        cursor = 0
    }

    /** True when the buffer has no characters. */
    fun isEmpty(): Boolean = buffer.isEmpty()

    /** Dim the prompt when the editor is waiting on an out-of-band task. */
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    fun setOnSubmit(handler: (String) -> Unit) {
        this.onSubmit = handler
    }

    fun setPlaceholder(placeholder: String) {
        this.placeholder = placeholder
    }

    /**
     * Called by the runtime when a `BracketedPasteEvent` is seen. During a
     * paste block, Enter inserts a `'\n'` character instead of submitting.
     * If the runtime does not forward paste events, this method is simply
     * never called — paste content then arrives as a flurry of printable
     * [KeyboardEvent]s, which is still correct.
     */
    fun setPasteActive(active: Boolean) {
        inPaste = active
    }

    /**
     * Install an [AutocompleteProvider]. When the user types a trigger
     * character (say `@`) the provider is queried and a popup appears under
     * the editor. Passing null disables autocomplete.
     */
    fun setAutocompleteProvider(provider: AutocompleteProvider?) {
        autocompleteProvider = provider
        autocompleteState = null
    }

    /** Visible row cap for the autocomplete popup. Defaults to 8. */
    fun setAutocompleteCap(cap: Int) {
        autocompleteCap = cap.coerceAtLeast(1)
    }

    /** True while the autocomplete popup is visible. Exposed for tests. */
    internal fun isAutocompleteActive(): Boolean = autocompleteState != null

    /** The currently-highlighted completion, or null when inactive. */
    internal fun autocompleteSelection(): Autocompletion? {
        val state = autocompleteState ?: return null
        return state.completions.getOrNull(state.selectedIndex)
    }

    /** Buffer length — exposed for tests. */
    internal fun cursorPos(): Int = cursor

    override fun invalidate() {
        // Nothing to invalidate: we don't cache anything width-dependent.
    }

    // ─── Input ─────────────────────────────────────────────────────────────

    override fun handleInput(key: KeyboardEvent): Boolean {
        // Autocomplete intercepts navigation + accept keys when active.
        // Other keystrokes fall through to the normal editing handlers
        // below; buffer-mutating handlers end up calling
        // [onEditMaybeAutocomplete] which re-queries the provider.
        if (autocompleteState != null) {
            when (key.codepoint) {
                KeyboardEvent.Up -> { autocompleteMoveBy(-1); return true }
                KeyboardEvent.Down -> { autocompleteMoveBy(+1); return true }
            }
            if (key.matches(9)) { // Tab — accept
                acceptCompletion()
                return true
            }
            if (key.matches(13) && !key.shift && !inPaste) { // Enter — accept, don't submit
                acceptCompletion()
                return true
            }
            if (key.codepoint == 27) { // Esc — dismiss without accepting
                dismissAutocomplete()
                return true
            }
        }

        // Enter + Shift+Enter handling depends on paste state. Check these
        // first so a paste-injected Enter never fires onSubmit.
        if (key.matches(13)) { // Enter / Return
            if (inPaste || key.shift) {
                insertChar('\n')
            } else {
                val content = text()
                clear()
                onSubmit(content)
            }
            return true
        }

        // Named-key codepoints. These are in the Kitty private-use range and
        // aren't "printable" per isPrintable, so they can't collide with
        // text-input handling below.
        when (key.codepoint) {
            KeyboardEvent.Left -> { moveLeft(); return true }
            KeyboardEvent.Right -> { moveRight(); return true }
            KeyboardEvent.Up -> { moveUp(); return true }
            KeyboardEvent.Down -> { moveDown(); return true }
            KeyboardEvent.Home -> { moveHome(); return true }
            KeyboardEvent.End -> { moveEnd(); return true }
            KeyboardEvent.Delete -> { deleteForward(); return true }
        }

        // Backspace (ASCII 127 on most terminals, or 8 / Ctrl+H).
        if (key.matches(127) || key.matches('h', ctrl = true) || key.matches(8)) {
            backspace()
            return true
        }

        // Ctrl bindings. Each is exclusive of other modifiers so we don't
        // swallow e.g. Ctrl+Shift+A intended for a global hotkey.
        if (key.matches('a', ctrl = true)) { moveHome(); return true }
        if (key.matches('e', ctrl = true)) { moveEnd(); return true }
        if (key.matches('k', ctrl = true)) { deleteToLineEnd(); return true }
        if (key.matches('u', ctrl = true)) { deleteToLineStart(); return true }
        if (key.matches('w', ctrl = true)) { deleteWordBackward(); return true }

        // Printable character insertion. This covers bracketed-paste-delivered
        // characters as well; the runtime feeds them in as ordinary key events.
        if (key.isPrintable) {
            // Use the pre-parsed text when available (multi-codepoint grapheme
            // clusters, IME composition); otherwise fall back to the codepoint.
            val t = key.text
            if (t != null && t.isNotEmpty()) {
                insertString(t)
            } else {
                val cp = key.codepoint
                if (Character.isBmpCodePoint(cp)) {
                    insertChar(cp.toChar())
                } else {
                    insertString(String(Character.toChars(cp)))
                }
            }
            return true
        }

        return false
    }

    // ─── Buffer mutations ──────────────────────────────────────────────────

    private fun insertChar(c: Char) {
        buffer.insert(cursor, c)
        cursor += 1
        onEditMaybeAutocomplete(triggerCandidate = c)
    }

    private fun insertString(s: String) {
        if (s.isEmpty()) return
        buffer.insert(cursor, s)
        cursor += s.length
        // Multi-char insert (paste or IME commit). We don't activate from
        // here — only single-keystroke triggers count — but we refresh any
        // active popup in case the buffer change invalidates it.
        onEditMaybeAutocomplete(triggerCandidate = null)
    }

    private fun backspace() {
        if (cursor == 0) return
        buffer.deleteCharAt(cursor - 1)
        cursor -= 1
        onEditMaybeAutocomplete(triggerCandidate = null)
    }

    private fun deleteForward() {
        if (cursor >= buffer.length) return
        buffer.deleteCharAt(cursor)
        onEditMaybeAutocomplete(triggerCandidate = null)
    }

    private fun deleteToLineEnd() {
        val end = lineEndOffset(cursor)
        if (end > cursor) buffer.delete(cursor, end)
        onEditMaybeAutocomplete(triggerCandidate = null)
    }

    private fun deleteToLineStart() {
        val start = lineStartOffset(cursor)
        if (cursor > start) {
            buffer.delete(start, cursor)
            cursor = start
        }
        onEditMaybeAutocomplete(triggerCandidate = null)
    }

    private fun deleteWordBackward() {
        if (cursor == 0) return
        var target = cursor
        // Skip whitespace immediately before the cursor.
        while (target > 0 && buffer[target - 1].isBufferWhitespace()) target--
        // Then skip non-whitespace (the "word") until we hit whitespace again.
        while (target > 0 && !buffer[target - 1].isBufferWhitespace()) target--
        if (target < cursor) {
            buffer.delete(target, cursor)
            cursor = target
        }
        onEditMaybeAutocomplete(triggerCandidate = null)
    }

    /** Whitespace classification used by Ctrl+W. Treats `\n` as a word break. */
    private fun Char.isBufferWhitespace(): Boolean =
        this == ' ' || this == '\t' || this == '\n'

    // ─── Cursor movement ───────────────────────────────────────────────────

    private fun moveLeft() {
        if (cursor > 0) {
            cursor -= 1
            onEditMaybeAutocomplete(triggerCandidate = null)
        }
    }

    private fun moveRight() {
        if (cursor < buffer.length) {
            cursor += 1
            onEditMaybeAutocomplete(triggerCandidate = null)
        }
    }

    /** Move to column 0 within the current logical line. */
    private fun moveHome() {
        cursor = lineStartOffset(cursor)
        onEditMaybeAutocomplete(triggerCandidate = null)
    }

    /** Move to the end of the current logical line (just before the next `\n`). */
    private fun moveEnd() {
        cursor = lineEndOffset(cursor)
        onEditMaybeAutocomplete(triggerCandidate = null)
    }

    private fun moveUp() {
        val (row, col) = rowColAt(cursor)
        if (row == 0) return
        cursor = offsetAt(row - 1, col)
        onEditMaybeAutocomplete(triggerCandidate = null)
    }

    private fun moveDown() {
        val (row, col) = rowColAt(cursor)
        val lastRow = logicalLineCount() - 1
        if (row >= lastRow) return
        cursor = offsetAt(row + 1, col)
        onEditMaybeAutocomplete(triggerCandidate = null)
    }

    // ─── Autocomplete logic ────────────────────────────────────────────────

    /**
     * Called after every buffer or cursor change. Routes to one of three
     * states:
     *  - No provider installed: do nothing.
     *  - Already active: re-query the provider with the updated prefix, or
     *    dismiss if the prefix is no longer valid (cursor jumped before the
     *    trigger, trigger was deleted, whitespace entered the prefix).
     *  - Inactive + [triggerCandidate] is a handled trigger in a valid
     *    position (start of buffer or after whitespace): activate.
     */
    private fun onEditMaybeAutocomplete(triggerCandidate: Char?) {
        val provider = autocompleteProvider ?: return
        if (autocompleteState != null) {
            refreshAutocomplete()
            return
        }
        if (triggerCandidate != null &&
            provider.handlesTrigger(triggerCandidate) &&
            isAtValidTriggerPosition()
        ) {
            activateAutocomplete(triggerCandidate)
        }
    }

    /**
     * True if the char at `cursor - 1` (the just-inserted trigger) sits at
     * the start of the buffer or immediately after whitespace. Mid-word
     * triggers (`foo@bar`) don't activate autocomplete.
     */
    private fun isAtValidTriggerPosition(): Boolean {
        val triggerPos = cursor - 1
        if (triggerPos < 0) return false
        if (triggerPos == 0) return true
        return buffer[triggerPos - 1].isWhitespace()
    }

    private fun activateAutocomplete(trigger: Char) {
        val provider = autocompleteProvider ?: return
        val anchor = cursor - 1
        val completions = provider.suggest(trigger, "", autocompleteCap)
        if (completions.isEmpty()) return
        autocompleteState = AutocompleteState(
            trigger = trigger,
            anchor = anchor,
            prefix = "",
            completions = completions,
            selectedIndex = 0,
        )
    }

    private fun refreshAutocomplete() {
        val state = autocompleteState ?: return
        val provider = autocompleteProvider ?: return

        // Anchor is gone (buffer shrank), trigger char deleted, or cursor
        // moved left of anchor — dismiss.
        if (state.anchor >= buffer.length ||
            buffer[state.anchor] != state.trigger ||
            cursor <= state.anchor
        ) {
            dismissAutocomplete()
            return
        }

        val prefix = buffer.substring(state.anchor + 1, cursor)
        // Whitespace in the prefix ends the token — dismiss.
        if (prefix.any { it.isWhitespace() }) {
            dismissAutocomplete()
            return
        }

        val completions = provider.suggest(state.trigger, prefix, autocompleteCap)
        if (completions.isEmpty()) {
            dismissAutocomplete()
            return
        }

        autocompleteState = state.copy(
            prefix = prefix,
            completions = completions,
            selectedIndex = state.selectedIndex.coerceIn(0, completions.lastIndex),
        )
    }

    private fun dismissAutocomplete() {
        autocompleteState = null
    }

    private fun autocompleteMoveBy(delta: Int) {
        val state = autocompleteState ?: return
        if (state.completions.isEmpty()) return
        val newIdx = (state.selectedIndex + delta).coerceIn(0, state.completions.lastIndex)
        if (newIdx == state.selectedIndex) return
        autocompleteState = state.copy(selectedIndex = newIdx)
    }

    /**
     * Apply the currently-highlighted completion: replace the buffer range
     * `[anchor + 1, cursor)` with the completion's replacement (plus optional
     * trailing space), then either dismiss the popup or continue completing
     * (directory entries set `continueCompletion = true` so the popup stays
     * open with the extended prefix).
     */
    private fun acceptCompletion() {
        val state = autocompleteState ?: return
        val choice = state.completions.getOrNull(state.selectedIndex) ?: return

        val insertion = choice.replacement + if (choice.trailingSpace) " " else ""
        val start = state.anchor + 1
        val end = cursor.coerceAtLeast(start)
        buffer.delete(start, end)
        buffer.insert(start, insertion)
        cursor = start + insertion.length

        if (choice.continueCompletion && !choice.trailingSpace) {
            // Re-query with the new prefix still anchored at the same trigger
            // position. Typical case: selecting a directory like `src/` so the
            // user can drill in without retyping the `@` trigger.
            refreshAutocomplete()
        } else {
            dismissAutocomplete()
        }
    }

    /** Offset of the start of the line containing [offset]. */
    private fun lineStartOffset(offset: Int): Int {
        var i = offset
        while (i > 0 && buffer[i - 1] != '\n') i--
        return i
    }

    /** Offset of the end of the line containing [offset] — the `\n` or EOB. */
    private fun lineEndOffset(offset: Int): Int {
        var i = offset
        while (i < buffer.length && buffer[i] != '\n') i++
        return i
    }

    /** Count of logical lines (always >= 1 even for an empty buffer). */
    private fun logicalLineCount(): Int {
        var n = 1
        for (i in 0 until buffer.length) if (buffer[i] == '\n') n++
        return n
    }

    /** (row, col) for the given buffer offset. */
    private fun rowColAt(offset: Int): Pair<Int, Int> {
        var row = 0
        var col = 0
        val limit = offset.coerceAtMost(buffer.length)
        for (i in 0 until limit) {
            if (buffer[i] == '\n') {
                row += 1
                col = 0
            } else {
                col += 1
            }
        }
        return row to col
    }

    /**
     * Turn a (row, col) position into a buffer offset, clamping [col] to the
     * length of [row]. [row] is assumed in `0 until logicalLineCount()`.
     */
    private fun offsetAt(row: Int, col: Int): Int {
        var offset = 0
        var currentRow = 0
        while (currentRow < row && offset < buffer.length) {
            if (buffer[offset] == '\n') currentRow += 1
            offset += 1
        }
        val rowStart = offset
        val rowEnd = lineEndOffset(rowStart)
        val rowLen = rowEnd - rowStart
        return rowStart + col.coerceAtMost(rowLen)
    }

    // ─── Render ────────────────────────────────────────────────────────────

    override fun render(width: Int): List<String> {
        if (width <= 0) return emptyList()

        val divider = renderDivider(width)
        val body = renderBody(width)
        val popup = renderAutocompletePopup(width)
        return buildList(body.size + popup.size + 1) {
            add(divider)
            addAll(body)
            addAll(popup)
        }
    }

    /**
     * Render the autocomplete popup, or an empty list when inactive.
     *
     * One row per visible completion, capped at [autocompleteCap]. The
     * selected row gets a ▸ marker and inverse-video background. Label is
     * rendered first; the optional description trails in dim color. If the
     * combined line exceeds [width], the description is truncated (label
     * never is — that would hide the completion target).
     */
    private fun renderAutocompletePopup(width: Int): List<String> {
        val state = autocompleteState ?: return emptyList()
        if (state.completions.isEmpty()) return emptyList()
        val t = theme
        val visible = state.completions.take(autocompleteCap)
        val out = ArrayList<String>(visible.size)

        for ((i, completion) in visible.withIndex()) {
            val selected = i == state.selectedIndex
            val marker = if (selected) "▸ " else "  "
            val markerColor = if (selected) t.accent else t.veryDim
            val labelColor = if (selected) t.foreground else t.dim
            val descColor = t.veryDim

            // Fixed left indent (2 cells) to align under the editor prompt,
            // then marker, then label, then optional description. Everything
            // in the pad column so highlight stripes extend edge-to-edge.
            val leftPad = "  "
            val labelCells = visibleWidth(completion.label)
            val markerCells = visibleWidth(marker)
            val headCells = leftPad.length + markerCells + labelCells
            val descBudget = (width - headCells - 2).coerceAtLeast(0)

            val descPart = completion.description?.let { desc ->
                if (descBudget <= 0) ""
                else {
                    val truncated = truncateToWidth(desc, descBudget)
                    "  $descColor$truncated${Ansi.RESET}"
                }
            } ?: ""

            val row = "$leftPad$markerColor$marker${Ansi.RESET}$labelColor${completion.label}${Ansi.RESET}$descPart"
            val clipped = if (visibleWidth(row) > width) truncateToWidth(row, width) else row
            val padded = padRightToWidth(clipped, width)
            out += if (selected) "${Ansi.INVERSE}$padded${Ansi.RESET}" else padded
        }
        return out
    }

    /**
     * Divider row: fill [width] cells with the theme's divider glyph, then
     * wrap in the divider color + RESET. Uses [truncateToWidth] to guarantee
     * the returned string has `visibleWidth == width` even if the repeated
     * glyph somehow over-measures.
     */
    private fun renderDivider(width: Int): String {
        val raw = "─".repeat(width)
        val clipped = if (visibleWidth(raw) > width) truncateToWidth(raw, width, ellipsis = "") else raw
        val padded = padRightToWidth(clipped, width)
        val t = theme
        return "${t.divider}$padded${Ansi.RESET}"
    }

    private fun renderBody(width: Int): List<String> {
        // Reserve 2 cells for the prompt prefix and 1 more as caret slack so
        // a cursor placed at end-of-line doesn't itself induce a terminal wrap.
        val innerWidth = (width - 2 - paddingX * 2 - 1).coerceAtLeast(1)
        val leftPad = if (paddingX > 0) " ".repeat(paddingX) else ""

        val promptColor = if (enabled) theme.accent else theme.veryDim
        val t = theme

        // Empty + focused → placeholder.
        if (buffer.isEmpty() && focused) {
            val prompt = "${promptColor}> ${Ansi.RESET}"
            // Placeholder uses the reserved inner width so the prompt + one cell
            // of trailing space still fit into `width` without overflow.
            val ph = truncateToWidth(placeholder, innerWidth, ellipsis = "")
            val rendered = "$leftPad$prompt${t.veryDim}$ph${Ansi.RESET}$CURSOR_MARKER"
            return listOf(padRightToWidth(rendered, width))
        }

        // Empty + unfocused → just the prompt, no placeholder, no cursor.
        if (buffer.isEmpty()) {
            val prompt = "${promptColor}> ${Ansi.RESET}"
            return listOf(padRightToWidth("$leftPad$prompt", width))
        }

        // Where does the cursor live, in logical (row, col)?
        val (cursorRow, cursorCol) = rowColAt(cursor)

        val logicalLines = buffer.toString().split('\n')
        val out = mutableListOf<String>()
        var firstVisualRow = true

        for (logicalRow in logicalLines.indices) {
            val line = logicalLines[logicalRow]
            val segments = hardWrapByCells(line, innerWidth)
            val cursorOnThisRow = focused && logicalRow == cursorRow

            // Which wrapped segment does the cursor land in, and at what col?
            val cursorSegIdx: Int
            val cursorSegCol: Int
            if (cursorOnThisRow) {
                cursorSegIdx = (cursorCol / innerWidth).coerceAtMost(segments.lastIndex)
                cursorSegCol = cursorCol - cursorSegIdx * innerWidth
            } else {
                cursorSegIdx = -1
                cursorSegCol = -1
            }

            for (segIdx in segments.indices) {
                val segment = segments[segIdx]
                val prefixMark = if (firstVisualRow) "> " else "  "
                firstVisualRow = false

                val prefixStyled = "${promptColor}${prefixMark}${Ansi.RESET}"

                val bodyStyled = if (segIdx == cursorSegIdx) {
                    renderWithCursor(segment, cursorSegCol, t.foreground)
                } else {
                    "${t.foreground}${segment}${Ansi.RESET}"
                }

                val rendered = "$leftPad$prefixStyled$bodyStyled"
                out += padRightToWidth(rendered, width)
            }
        }

        return out
    }

    /**
     * Splice a cursor marker into [segment] at visual column [col].
     * Emits ANSI sequences styled with [fg] and the theme's RESET at the end.
     * The cursor itself is emitted as [CURSOR_MARKER] — the runtime handles
     * hardware cursor positioning.
     */
    private fun renderWithCursor(segment: String, col: Int, fg: String): String {
        // `segment` here is a plain substring — no embedded ANSI — because we
        // wrap the raw buffer text, which is guaranteed ANSI-free.
        val safeCol = col.coerceIn(0, segment.length)
        val before = segment.substring(0, safeCol)
        val after = segment.substring(safeCol)
        return buildString {
            append(fg)
            append(before)
            append(CURSOR_MARKER)
            append(after)
            append(Ansi.RESET)
        }
    }

    /**
     * Chunk [line] into segments of at most [width] cells. Falls back to
     * returning `listOf("")` for empty input so empty logical lines still
     * contribute a visual row.
     *
     * Uses character positions (not grapheme clusters) for v1 — matches the
     * editor's own character-level cursor model. Wide-char support can be
     * added when the editor grows grapheme-aware cursor motion.
     */
    private fun hardWrapByCells(line: String, width: Int): List<String> {
        if (line.isEmpty()) return listOf("")
        if (width <= 0) return listOf("")
        val result = ArrayList<String>(line.length / width + 1)
        var start = 0
        while (start < line.length) {
            val end = (start + width).coerceAtMost(line.length)
            result += line.substring(start, end)
            start = end
        }
        return result
    }
}
