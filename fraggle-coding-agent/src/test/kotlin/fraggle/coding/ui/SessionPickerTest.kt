package fraggle.coding.ui

import com.jakewharton.mosaic.terminal.KeyboardEvent
import fraggle.coding.session.SessionPreview
import fraggle.coding.session.SessionSummary
import fraggle.tui.text.stripAnsi
import fraggle.tui.text.visibleWidth
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SessionPickerTest {

    private fun preview(
        id: String,
        lastModifiedMs: Long,
        firstUserMessage: String? = "msg for $id",
        messageCount: Int = 3,
        model: String? = "test-model",
    ): SessionPreview = SessionPreview(
        summary = SessionSummary(
            id = id,
            file = Paths.get("/tmp/$id.jsonl"),
            lastModifiedMs = lastModifiedMs,
        ),
        firstUserMessage = firstUserMessage,
        messageCount = messageCount,
        model = model,
    )

    private fun arrow(codepoint: Int): KeyboardEvent = KeyboardEvent(codepoint = codepoint)
    private fun char(c: Char, ctrl: Boolean = false): KeyboardEvent =
        KeyboardEvent(
            codepoint = c.code,
            modifiers = if (ctrl) KeyboardEvent.ModifierCtrl else 0,
        )

    private fun <T> captured(): Pair<(T) -> Unit, () -> T?> {
        var slot: T? = null
        return { t: T -> slot = t } to { slot }
    }

    @Nested
    inner class Rendering {
        @Test
        fun `renders one row per preview and pads to width`() {
            val previews = listOf(
                preview("a", lastModifiedMs = 1000),
                preview("b", lastModifiedMs = 2000),
            )
            val picker = SessionPicker(
                previews = previews,
                visibleRowCap = 10,
                now = { 10_000 },
                onComplete = {},
            )

            val lines = picker.render(80)
            assertEquals(2, lines.size)
            for (line in lines) {
                assertEquals(80, visibleWidth(line), "every row must fill the width exactly")
            }
        }

        @Test
        fun `selected row shows marker and inverse styling`() {
            val previews = listOf(
                preview("a", lastModifiedMs = 0L, firstUserMessage = "hello"),
                preview("b", lastModifiedMs = 0L, firstUserMessage = "world"),
            )
            val picker = SessionPicker(
                previews = previews,
                visibleRowCap = 10,
                now = { 0L },
                onComplete = {},
            )

            val lines = picker.render(80)
            // Default selection is index 0.
            assertTrue(lines[0].contains("▸"), "selected row must show the marker")
            assertTrue(lines[0].contains("\u001b[7m"), "selected row must be inverse-styled")
            assertTrue(!lines[1].contains("▸"), "non-selected rows must not show the marker")
        }

        @Test
        fun `scrolling keeps selection visible when selecting past the visible cap`() {
            val previews = (1..20).map { preview("s$it", lastModifiedMs = it.toLong()) }
            val picker = SessionPicker(
                previews = previews,
                visibleRowCap = 5,
                now = { 1000L },
                onComplete = {},
            )

            // Jump to the last row; scroll should have advanced.
            picker.handleInput(arrow(KeyboardEvent.End))
            val lines = picker.render(100)

            assertEquals(5, lines.size)
            // The last entry's message should be on screen somewhere.
            val combined = lines.joinToString("") { stripAnsi(it) }
            assertTrue(combined.contains("msg for s20"), "bottom of list should be visible after End")
        }

        @Test
        fun `long preview is truncated with ellipsis`() {
            val longMsg = "a".repeat(500)
            val previews = listOf(preview("a", lastModifiedMs = 0L, firstUserMessage = longMsg))
            val picker = SessionPicker(
                previews = previews,
                visibleRowCap = 10,
                now = { 0L },
                onComplete = {},
            )

            val lines = picker.render(40)
            assertEquals(40, visibleWidth(lines[0]))
            val plain = stripAnsi(lines[0])
            // Truncation appends a single-cell ellipsis.
            assertTrue(plain.contains("…"), "long preview should be ellipsis-truncated")
        }

        @Test
        fun `message count is pluralised when not one`() {
            val previews = listOf(
                preview("a", lastModifiedMs = 0L, messageCount = 1),
                preview("b", lastModifiedMs = 0L, messageCount = 7),
            )
            val picker = SessionPicker(previews, visibleRowCap = 10, now = { 0L }, onComplete = {})
            val lines = picker.render(100).map { stripAnsi(it) }
            assertTrue(lines[0].contains("1 msg "), "singular form for count 1")
            assertTrue(lines[1].contains("7 msgs"), "plural form for count > 1")
        }

        @Test
        fun `missing first user message falls back to sentinel`() {
            val previews = listOf(preview("a", lastModifiedMs = 0L, firstUserMessage = null))
            val picker = SessionPicker(previews, visibleRowCap = 10, now = { 0L }, onComplete = {})
            val line = stripAnsi(picker.render(100).first())
            assertTrue(line.contains("(empty session)"), "fallback for sessions with no user turn")
        }
    }

    @Nested
    inner class KeyboardNavigation {
        @Test
        fun `arrow down advances selection`() {
            val previews = listOf(
                preview("a", lastModifiedMs = 0L),
                preview("b", lastModifiedMs = 0L),
                preview("c", lastModifiedMs = 0L),
            )
            val picker = SessionPicker(previews, visibleRowCap = 10, now = { 0L }, onComplete = {})
            picker.handleInput(arrow(KeyboardEvent.Down))
            assertEquals("b", picker.selection?.summary?.id)
        }

        @Test
        fun `arrow up respects top boundary`() {
            val previews = listOf(preview("a", lastModifiedMs = 0L))
            val picker = SessionPicker(previews, visibleRowCap = 10, now = { 0L }, onComplete = {})
            picker.handleInput(arrow(KeyboardEvent.Up))
            assertEquals("a", picker.selection?.summary?.id, "cannot move above first entry")
        }

        @Test
        fun `arrow down respects bottom boundary`() {
            val previews = listOf(
                preview("a", lastModifiedMs = 0L),
                preview("b", lastModifiedMs = 0L),
            )
            val picker = SessionPicker(previews, visibleRowCap = 10, now = { 0L }, onComplete = {})
            picker.handleInput(arrow(KeyboardEvent.Down))
            picker.handleInput(arrow(KeyboardEvent.Down))
            picker.handleInput(arrow(KeyboardEvent.Down))
            assertEquals("b", picker.selection?.summary?.id, "cannot move past last entry")
        }

        @Test
        fun `Home and End jump to boundaries`() {
            val previews = (1..5).map { preview("s$it", lastModifiedMs = 0L) }
            val picker = SessionPicker(previews, visibleRowCap = 10, now = { 0L }, onComplete = {})
            picker.handleInput(arrow(KeyboardEvent.End))
            assertEquals("s5", picker.selection?.summary?.id)
            picker.handleInput(arrow(KeyboardEvent.Home))
            assertEquals("s1", picker.selection?.summary?.id)
        }

        @Test
        fun `vim k and j keys navigate`() {
            val previews = (1..3).map { preview("s$it", lastModifiedMs = 0L) }
            val picker = SessionPicker(previews, visibleRowCap = 10, now = { 0L }, onComplete = {})
            picker.handleInput(char('j'))
            assertEquals("s2", picker.selection?.summary?.id)
            picker.handleInput(char('k'))
            assertEquals("s1", picker.selection?.summary?.id)
        }

        @Test
        fun `PageDown and PageUp jump by half visibleRowCap`() {
            val previews = (1..20).map { preview("s$it", lastModifiedMs = 0L) }
            val picker = SessionPicker(previews, visibleRowCap = 10, now = { 0L }, onComplete = {})
            picker.handleInput(arrow(KeyboardEvent.PageDown))
            // half of 10 is 5
            assertEquals("s6", picker.selection?.summary?.id)
            picker.handleInput(arrow(KeyboardEvent.PageUp))
            assertEquals("s1", picker.selection?.summary?.id)
        }
    }

    @Nested
    inner class Completion {
        @Test
        fun `Enter completes with the selected preview`() {
            val (callback, get) = captured<SessionPickerResult>()
            val previews = listOf(
                preview("a", lastModifiedMs = 0L),
                preview("b", lastModifiedMs = 0L),
            )
            val picker = SessionPicker(previews, visibleRowCap = 10, now = { 0L }, onComplete = callback)
            picker.handleInput(arrow(KeyboardEvent.Down))
            picker.handleInput(arrow(13)) // Enter

            val result = get()
            assertIs<SessionPickerResult.Selected>(result)
            assertEquals("b", result.summary.id)
        }

        @Test
        fun `pressing n completes with NewSession`() {
            val (callback, get) = captured<SessionPickerResult>()
            val previews = listOf(preview("a", lastModifiedMs = 0L))
            val picker = SessionPicker(previews, visibleRowCap = 10, now = { 0L }, onComplete = callback)
            picker.handleInput(char('n'))
            assertEquals(SessionPickerResult.NewSession, get())
        }

        @Test
        fun `pressing Esc completes with Cancelled`() {
            val (callback, get) = captured<SessionPickerResult>()
            val previews = listOf(preview("a", lastModifiedMs = 0L))
            val picker = SessionPicker(previews, visibleRowCap = 10, now = { 0L }, onComplete = callback)
            picker.handleInput(arrow(27))
            assertEquals(SessionPickerResult.Cancelled, get())
        }

        @Test
        fun `Ctrl+C also cancels`() {
            val (callback, get) = captured<SessionPickerResult>()
            val previews = listOf(preview("a", lastModifiedMs = 0L))
            val picker = SessionPicker(previews, visibleRowCap = 10, now = { 0L }, onComplete = callback)
            picker.handleInput(char('c', ctrl = true))
            assertEquals(SessionPickerResult.Cancelled, get())
        }

        @Test
        fun `q also cancels`() {
            val (callback, get) = captured<SessionPickerResult>()
            val previews = listOf(preview("a", lastModifiedMs = 0L))
            val picker = SessionPicker(previews, visibleRowCap = 10, now = { 0L }, onComplete = callback)
            picker.handleInput(char('q'))
            assertEquals(SessionPickerResult.Cancelled, get())
        }

        @Test
        fun `callback fires only once even on repeat input`() {
            var count = 0
            val previews = listOf(preview("a", lastModifiedMs = 0L))
            val picker = SessionPicker(
                previews,
                visibleRowCap = 10,
                now = { 0L },
                onComplete = { count++ },
            )
            picker.handleInput(arrow(13))
            picker.handleInput(arrow(13))
            picker.handleInput(char('q'))
            assertEquals(1, count, "subsequent completions must be ignored")
        }

        @Test
        fun `Enter on an empty list is a no-op`() {
            val (callback, get) = captured<SessionPickerResult>()
            val picker = SessionPicker(
                previews = emptyList(),
                visibleRowCap = 10,
                now = { 0L },
                onComplete = callback,
            )
            picker.handleInput(arrow(13))
            assertNull(get())
        }
    }

    @Nested
    inner class RelativeTime {
        @Test
        fun `under 45 seconds reads as just now`() {
            assertEquals("just now", SessionPicker.formatRelativeTime(10_000L))
        }

        @Test
        fun `negative delta clamps to just now`() {
            assertEquals("just now", SessionPicker.formatRelativeTime(-1000L))
        }

        @Test
        fun `minutes hours days weeks months years render in the short form`() {
            val m = 60_000L
            val h = 60 * m
            val d = 24 * h
            assertEquals("5m ago", SessionPicker.formatRelativeTime(5 * m))
            assertEquals("3h ago", SessionPicker.formatRelativeTime(3 * h))
            assertEquals("yesterday", SessionPicker.formatRelativeTime(30 * h))
            assertEquals("3d ago", SessionPicker.formatRelativeTime(3 * d))
            assertEquals("2w ago", SessionPicker.formatRelativeTime(14 * d))
            assertEquals("3mo ago", SessionPicker.formatRelativeTime(90 * d))
            assertEquals("1y ago", SessionPicker.formatRelativeTime(400 * d))
        }
    }
}
