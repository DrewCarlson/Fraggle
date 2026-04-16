package fraggle.tui.ui

import com.jakewharton.mosaic.terminal.KeyboardEvent
import fraggle.tui.core.CURSOR_MARKER
import fraggle.tui.text.stripAnsi
import fraggle.tui.text.visibleWidth
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class EditorTest {

    // ── Key builders ───────────────────────────────────────────────────────
    //
    // Mosaic's KeyboardEvent is a Poko data class with a large parameter list;
    // these helpers make test bodies readable.

    private fun key(cp: Int, ctrl: Boolean = false, shift: Boolean = false): KeyboardEvent {
        var modifiers = 0
        if (ctrl) modifiers = modifiers or KeyboardEvent.ModifierCtrl
        if (shift) modifiers = modifiers or KeyboardEvent.ModifierShift
        return KeyboardEvent(codepoint = cp, modifiers = modifiers)
    }

    private fun char(c: Char): KeyboardEvent = key(c.code)
    private fun ctrl(c: Char): KeyboardEvent = key(c.code, ctrl = true)
    private fun enter(shift: Boolean = false): KeyboardEvent = key(13, shift = shift)
    private fun backspace(): KeyboardEvent = key(127)
    private val arrowLeft: KeyboardEvent = key(KeyboardEvent.Left)
    private val arrowRight: KeyboardEvent = key(KeyboardEvent.Right)
    private val arrowUp: KeyboardEvent = key(KeyboardEvent.Up)
    private val arrowDown: KeyboardEvent = key(KeyboardEvent.Down)
    private val home: KeyboardEvent = key(KeyboardEvent.Home)
    private val end: KeyboardEvent = key(KeyboardEvent.End)
    private val del: KeyboardEvent = key(KeyboardEvent.Delete)

    private fun Editor.type(s: String) {
        for (ch in s) handleInput(char(ch))
    }

    private fun Editor.render40(): List<String> = render(40)

    // ── Placeholder / empty state ──────────────────────────────────────────

    @Nested
    inner class Placeholder {
        @Test
        fun `empty editor shows placeholder when focused`() {
            val e = Editor(placeholder = "type here")
            e.focused = true
            val lines = stripAnsi(e.render40().joinToString("\n"))
            assertContains(lines, "type here")
        }

        @Test
        fun `empty editor does not show placeholder when unfocused`() {
            val e = Editor(placeholder = "type here")
            e.focused = false
            val stripped = stripAnsi(e.render40().joinToString("\n"))
            assertFalse(stripped.contains("type here"), "unfocused editor must not render placeholder")
        }

        @Test
        fun `placeholder disappears once a character is typed`() {
            val e = Editor(placeholder = "hint")
            e.focused = true
            e.type("a")
            val stripped = stripAnsi(e.render40().joinToString("\n"))
            assertFalse(stripped.contains("hint"), "placeholder must not render when buffer has content")
            assertContains(stripped, "a")
        }

        @Test
        fun `setPlaceholder updates text shown`() {
            val e = Editor(placeholder = "old")
            e.focused = true
            e.setPlaceholder("new")
            val stripped = stripAnsi(e.render40().joinToString("\n"))
            assertContains(stripped, "new")
            assertFalse(stripped.contains("old"))
        }
    }

    // ── Insertion + cursor advance ─────────────────────────────────────────

    @Nested
    inner class Insertion {
        @Test
        fun `printable chars append to buffer`() {
            val e = Editor()
            e.focused = true
            e.type("hello")
            assertEquals("hello", e.text())
            assertEquals(5, e.cursorPos())
        }

        @Test
        fun `handleInput returns true for printable chars`() {
            val e = Editor()
            assertTrue(e.handleInput(char('x')))
            assertEquals("x", e.text())
        }

        @Test
        fun `non-printable non-editing key returns false`() {
            val e = Editor()
            // F5 is in the Kitty private-use range; no binding → false.
            assertFalse(e.handleInput(key(KeyboardEvent.F5)))
            assertEquals("", e.text())
        }

        @Test
        fun `insertion at cursor splits surrounding text`() {
            val e = Editor()
            e.type("ac")
            // Move cursor back by 1: between 'a' and 'c'.
            e.handleInput(arrowLeft)
            e.handleInput(char('b'))
            assertEquals("abc", e.text())
            assertEquals(2, e.cursorPos())
        }
    }

    // ── Backspace / Delete ─────────────────────────────────────────────────

    @Nested
    inner class DeleteKeys {
        @Test
        fun `backspace removes char before cursor`() {
            val e = Editor()
            e.type("abc")
            e.handleInput(backspace())
            assertEquals("ab", e.text())
            assertEquals(2, e.cursorPos())
        }

        @Test
        fun `backspace on empty buffer is no-op`() {
            val e = Editor()
            e.handleInput(backspace())
            assertEquals("", e.text())
            assertEquals(0, e.cursorPos())
        }

        @Test
        fun `Ctrl-H is an alias for backspace`() {
            val e = Editor()
            e.type("abc")
            e.handleInput(ctrl('h'))
            assertEquals("ab", e.text())
        }

        @Test
        fun `Delete removes char at cursor`() {
            val e = Editor()
            e.type("abc")
            // Move to start.
            e.handleInput(home)
            e.handleInput(del)
            assertEquals("bc", e.text())
            assertEquals(0, e.cursorPos())
        }

        @Test
        fun `Delete at end of buffer is no-op`() {
            val e = Editor()
            e.type("abc")
            e.handleInput(del)
            assertEquals("abc", e.text())
        }

        @Test
        fun `backspace at start of second line joins lines`() {
            val e = Editor()
            e.type("a")
            e.handleInput(enter(shift = true))
            e.type("b")
            // Cursor after 'b', at (row=1, col=1). Move home then backspace.
            e.handleInput(home)
            e.handleInput(backspace())
            // Newline should be gone.
            assertEquals("ab", e.text())
        }
    }

    // ── Submit ─────────────────────────────────────────────────────────────

    @Nested
    inner class Submit {
        @Test
        fun `Enter calls onSubmit with current text and clears buffer`() {
            val submitted = mutableListOf<String>()
            val e = Editor(onSubmit = { submitted += it })
            e.type("hi there")
            e.handleInput(enter())
            assertEquals(listOf("hi there"), submitted)
            assertTrue(e.isEmpty())
            assertEquals(0, e.cursorPos())
        }

        @Test
        fun `setOnSubmit swaps the handler`() {
            var captured = ""
            val e = Editor()
            e.setOnSubmit { captured = it }
            e.type("x")
            e.handleInput(enter())
            assertEquals("x", captured)
        }

        @Test
        fun `ShiftEnter inserts newline and moves cursor down`() {
            val e = Editor()
            e.type("a")
            e.handleInput(enter(shift = true))
            e.type("b")
            assertEquals("a\nb", e.text())
        }
    }

    // ── Arrow keys and boundaries ──────────────────────────────────────────

    @Nested
    inner class Navigation {
        @Test
        fun `left at start is no-op`() {
            val e = Editor()
            e.handleInput(arrowLeft)
            assertEquals(0, e.cursorPos())
        }

        @Test
        fun `right at end is no-op`() {
            val e = Editor()
            e.type("abc")
            e.handleInput(arrowRight)
            assertEquals(3, e.cursorPos())
        }

        @Test
        fun `left and right move by 1 char`() {
            val e = Editor()
            e.type("abc")
            assertEquals(3, e.cursorPos())
            e.handleInput(arrowLeft)
            assertEquals(2, e.cursorPos())
            e.handleInput(arrowRight)
            assertEquals(3, e.cursorPos())
        }

        @Test
        fun `Up on first row is no-op`() {
            val e = Editor()
            e.type("hi")
            e.handleInput(arrowUp)
            assertEquals(2, e.cursorPos())
        }

        @Test
        fun `Down on last row is no-op`() {
            val e = Editor()
            e.type("hi")
            e.handleInput(arrowDown)
            assertEquals(2, e.cursorPos())
        }

        @Test
        fun `Up preserves column when target line is long enough`() {
            val e = Editor()
            // Two lines, both 5 chars, cursor at column 3 of line 2.
            e.setText("hello\nworld")
            // cursor is at end (11). Walk back to line 2 col 3:
            e.handleInput(home) // → offset 6 (start of "world")
            e.handleInput(arrowRight) // 7
            e.handleInput(arrowRight) // 8
            e.handleInput(arrowRight) // 9 (col 3 of "world")
            e.handleInput(arrowUp)
            // Expect cursor at line 1 col 3 → offset 3.
            assertEquals(3, e.cursorPos())
        }

        @Test
        fun `Up clamps column to previous line length`() {
            val e = Editor()
            e.setText("hi\nlonger")
            // Cursor at end of "longer" → offset 9, col 6.
            e.handleInput(arrowUp)
            // "hi" is only 2 chars long → clamp col to 2 → offset 2.
            assertEquals(2, e.cursorPos())
        }

        @Test
        fun `Down preserves column`() {
            val e = Editor()
            e.setText("hello\nworld")
            // Cursor at col 3 of "hello" → offset 3.
            e.handleInput(home) // offset 0
            e.handleInput(arrowRight) // 1
            e.handleInput(arrowRight) // 2
            e.handleInput(arrowRight) // 3
            e.handleInput(arrowDown)
            assertEquals(9, e.cursorPos()) // line 2, col 3 → offset 6 + 3.
        }
    }

    // ── Home / End ─────────────────────────────────────────────────────────

    @Nested
    inner class HomeAndEnd {
        @Test
        fun `Home moves to start of current logical line only`() {
            val e = Editor()
            e.setText("abc\ndef")
            // Cursor at end (7). Home should land at 4 (start of "def"), not 0.
            e.handleInput(home)
            assertEquals(4, e.cursorPos())
        }

        @Test
        fun `End moves to end of current logical line only`() {
            val e = Editor()
            e.setText("abc\ndef")
            // Put cursor at start of "abc".
            e.handleInput(home) // line 2 start
            e.handleInput(arrowUp) // line 1 col 0
            e.handleInput(end)
            assertEquals(3, e.cursorPos()) // end of "abc", not of whole buffer
        }

        @Test
        fun `Ctrl-A aliases Home`() {
            val e = Editor()
            e.type("abc")
            e.handleInput(ctrl('a'))
            assertEquals(0, e.cursorPos())
        }

        @Test
        fun `Ctrl-E aliases End`() {
            val e = Editor()
            e.type("abc")
            e.handleInput(ctrl('a'))
            e.handleInput(ctrl('e'))
            assertEquals(3, e.cursorPos())
        }
    }

    // ── Kill / line editing ────────────────────────────────────────────────

    @Nested
    inner class LineEditing {
        @Test
        fun `Ctrl-K deletes from cursor to end of line`() {
            val e = Editor()
            e.setText("hello world")
            // Cursor at end. Move home then right by 5 → between "hello" and " world".
            e.handleInput(home)
            repeat(5) { e.handleInput(arrowRight) }
            e.handleInput(ctrl('k'))
            assertEquals("hello", e.text())
            assertEquals(5, e.cursorPos())
        }

        @Test
        fun `Ctrl-K does not cross newline`() {
            val e = Editor()
            e.setText("abc\ndef")
            // Place cursor at col 1 of line 1.
            e.handleInput(home) // start of line 2
            e.handleInput(arrowUp)
            e.handleInput(arrowRight) // col 1 of line 1
            e.handleInput(ctrl('k'))
            assertEquals("a\ndef", e.text())
        }

        @Test
        fun `Ctrl-U deletes from cursor to start of line`() {
            val e = Editor()
            e.setText("hello world")
            e.handleInput(ctrl('u'))
            assertEquals("", e.text())
            assertEquals(0, e.cursorPos())
        }

        @Test
        fun `Ctrl-U does not cross newline`() {
            val e = Editor()
            e.setText("abc\ndef")
            // Cursor at end of line 2 ("def"), after 'f'.
            e.handleInput(ctrl('u'))
            assertEquals("abc\n", e.text())
            assertEquals(4, e.cursorPos())
        }

        @Test
        fun `Ctrl-W deletes the preceding word`() {
            val e = Editor()
            e.type("hello world")
            e.handleInput(ctrl('w'))
            assertEquals("hello ", e.text())
        }

        @Test
        fun `Ctrl-W skips trailing spaces before the word`() {
            val e = Editor()
            e.type("foo   ")
            e.handleInput(ctrl('w'))
            assertEquals("", e.text())
        }

        @Test
        fun `Ctrl-W on empty buffer is no-op`() {
            val e = Editor()
            e.handleInput(ctrl('w'))
            assertEquals("", e.text())
        }

        @Test
        fun `Ctrl-W stops at whitespace boundary`() {
            val e = Editor()
            e.type("foo bar")
            e.handleInput(ctrl('w'))
            assertEquals("foo ", e.text())
        }
    }

    // ── Render: wrap + width contract ──────────────────────────────────────

    @Nested
    inner class Rendering {
        @Test
        fun `long single line wraps at width minus three`() {
            val e = Editor()
            e.focused = true
            e.setText("a".repeat(80))
            val lines = e.render(20)
            // Header (divider) + body rows.
            val bodyRows = lines.drop(1)
            // Each body row shows innerWidth = 20 - 2 - 1 = 17 of 'a' at most.
            assertTrue(bodyRows.size >= 2, "expected multi-line wrap, got ${bodyRows.size}")
        }

        @Test
        fun `every rendered line has visibleWidth equal to width`() {
            val e = Editor()
            e.focused = true
            e.setText("short\n${"x".repeat(200)}")
            for (width in listOf(10, 20, 40, 80)) {
                val rendered = e.render(width)
                for ((idx, line) in rendered.withIndex()) {
                    // CURSOR_MARKER is an APC sequence handled by our ANSI stripper
                    // via the OSC/APC path (ESC _ ... BEL). It has zero visible
                    // width, so visibleWidth ignores it correctly.
                    assertEquals(
                        width,
                        visibleWidth(line),
                        "row $idx (width=$width) should equal $width but got ${visibleWidth(line)}: ${line.toVisible()}",
                    )
                }
            }
        }

        @Test
        fun `divider appears as the first rendered row`() {
            val e = Editor()
            val lines = e.render(20)
            assertTrue(lines.isNotEmpty())
            val divider = stripAnsi(lines[0])
            assertTrue(divider.startsWith("─"), "row 0 should be the divider, got: $divider")
        }

        @Test
        fun `logical newlines create separate visual rows`() {
            val e = Editor()
            e.focused = true
            e.setText("a\nb\nc")
            val lines = e.render(20)
            // Header (divider) + 3 body rows.
            assertEquals(4, lines.size, "expected 1 divider + 3 body rows, got: $lines")
        }

        @Test
        fun `continuation rows use the two-space prefix`() {
            val e = Editor()
            e.focused = true
            // Force wrap by exceeding innerWidth.
            e.setText("x".repeat(40))
            val lines = e.render(15)
            val body = lines.drop(1)
            assertTrue(body.size >= 2)
            val first = stripAnsi(body[0])
            val continuation = stripAnsi(body[1])
            assertTrue(first.startsWith("> "), "first row should start with '> ', got: $first")
            assertTrue(
                continuation.startsWith("  "),
                "continuation row should start with two spaces, got: $continuation",
            )
        }

        @Test
        fun `cursor marker is emitted when focused`() {
            val e = Editor()
            e.focused = true
            e.type("abc")
            val rendered = e.render(20).joinToString("\n")
            assertContains(rendered, CURSOR_MARKER)
        }

        @Test
        fun `no cursor marker when unfocused`() {
            val e = Editor()
            e.focused = false
            e.type("abc")
            val rendered = e.render(20).joinToString("\n")
            assertFalse(
                rendered.contains(CURSOR_MARKER),
                "unfocused editor must not emit CURSOR_MARKER",
            )
        }

        @Test
        fun `cursor marker at end of inserted text`() {
            val e = Editor()
            e.focused = true
            e.type("abc")
            val body = e.render(20).drop(1).joinToString("\n")
            // Cursor at position 3 — should appear AFTER "abc".
            val idxC = body.indexOf('c')
            val idxMarker = body.indexOf(CURSOR_MARKER)
            assertTrue(idxC in 0..<idxMarker, "cursor marker must follow the text")
        }

        @Test
        fun `cursor marker splits text when cursor is in the middle`() {
            val e = Editor()
            e.focused = true
            e.type("abc")
            e.handleInput(arrowLeft) // cursor at col 2
            val body = e.render(20).drop(1).joinToString("")
            val idxMarker = body.indexOf(CURSOR_MARKER)
            assertTrue(idxMarker > 0, "marker missing")
            val stripped = stripAnsi(body)
            // Content before marker should end in "ab"; content after should start with "c".
            // stripAnsi removes CURSOR_MARKER (APC) too, so reconstruct positions from raw.
            val before = body.substring(0, idxMarker)
            val after = body.substring(idxMarker + CURSOR_MARKER.length)
            assertTrue(stripAnsi(before).endsWith("ab"), "before-marker must end with ab, got: $before")
            assertTrue(
                stripAnsi(after).startsWith("c"),
                "after-marker must start with c, got: ${stripAnsi(after)}",
            )
            assertTrue(stripped.contains("abc"), "text must still be intact")
        }
    }

    // ── Bracketed paste ────────────────────────────────────────────────────

    @Nested
    inner class BracketedPaste {
        @Test
        fun `Enter during paste inserts newline instead of submitting`() {
            val submitted = mutableListOf<String>()
            val e = Editor(onSubmit = { submitted += it })
            e.type("a")
            e.setPasteActive(true)
            e.handleInput(enter())
            e.type("b")
            e.setPasteActive(false)
            assertEquals("a\nb", e.text())
            assertTrue(submitted.isEmpty(), "submit must not fire during paste")
        }

        @Test
        fun `Enter after paste ends submits normally`() {
            val submitted = mutableListOf<String>()
            val e = Editor(onSubmit = { submitted += it })
            e.setPasteActive(true)
            e.type("abc")
            e.setPasteActive(false)
            e.handleInput(enter())
            assertEquals(listOf("abc"), submitted)
        }
    }

    // ── API shape ──────────────────────────────────────────────────────────

    @Nested
    inner class ApiShape {
        @Test
        fun `setText replaces buffer and moves cursor to end`() {
            val e = Editor()
            e.setText("hello")
            assertEquals("hello", e.text())
            assertEquals(5, e.cursorPos())
        }

        @Test
        fun `clear empties buffer and resets cursor`() {
            val e = Editor()
            e.setText("junk")
            e.clear()
            assertTrue(e.isEmpty())
            assertEquals(0, e.cursorPos())
        }

        @Test
        fun `isEmpty reflects buffer state`() {
            val e = Editor()
            assertTrue(e.isEmpty())
            e.type("x")
            assertFalse(e.isEmpty())
        }

        @Test
        fun `setEnabled does not throw and still focusable`() {
            val e = Editor()
            e.setEnabled(false)
            e.focused = true
            // Must still render without crashing.
            val lines = e.render(20)
            assertTrue(lines.isNotEmpty())
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** Render non-printing chars as `\xNN` for easier test-failure output. */
    private fun String.toVisible(): String = buildString {
        for (ch in this@toVisible) {
            when (ch) {
                '\r' -> append("\\r")
                '\n' -> append("\\n")
                '\u001b' -> append("\\x1b")
                '\u0007' -> append("\\x07")
                else -> append(ch)
            }
        }
    }
}
