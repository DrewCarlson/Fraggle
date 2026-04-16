@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package fraggle.tui.core

import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.Event
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.ResizeEvent
import com.jakewharton.mosaic.terminal.Terminal
import fraggle.tui.text.Ansi
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TUITest {

    // ────────────────────────────────────────────────────────────────────────
    // Test fixtures
    // ────────────────────────────────────────────────────────────────────────

    /** Captures writes into a list of strings for assertion-style inspection. */
    private class FakeOutput : TerminalOutput {
        val writes: MutableList<String> = mutableListOf()
        var enableRawModeCalled: Int = 0
        var resetCalled: Int = 0

        override fun write(text: String) {
            writes += text
        }

        override fun enableRawMode() {
            enableRawModeCalled += 1
        }

        override fun reset() {
            resetCalled += 1
        }

        /** Concatenation of all writes — convenient for contains-checks. */
        val combined: String get() = writes.joinToString("")
    }

    /** Minimal in-memory Terminal that exposes a controllable event channel + size. */
    private class FakeTerminal(
        initialColumns: Int = 80,
        initialRows: Int = 24,
        private val syncSupported: Boolean = true,
    ) : Terminal {
        val eventChannel: Channel<Event> = Channel(Channel.UNLIMITED)
        private val sizeFlow = MutableStateFlow(Terminal.Size(initialColumns, initialRows))
        private val focusFlow = MutableStateFlow(true)
        private val themeFlow = MutableStateFlow(Terminal.Theme.Unknown)

        override val name: String? = "fake"
        override val interactive: Boolean = true
        override val state: Terminal.State = object : Terminal.State {
            override val focused: StateFlow<Boolean> get() = focusFlow
            override val theme: StateFlow<Terminal.Theme> get() = themeFlow
            override val size: StateFlow<Terminal.Size> get() = sizeFlow
        }
        override val capabilities: Terminal.Capabilities = object : Terminal.Capabilities {
            override val ansiLevel: AnsiLevel = AnsiLevel.TRUECOLOR
            override val cursorVisibility: Boolean = true
            override val focusEvents: Boolean = false
            override val inBandResizeEvents: Boolean = false
            override val kittyGraphics: Boolean = false
            override val kittyKeyboard: Boolean = false
            override val kittyNotifications: Boolean = false
            override val kittyPointerShape: Boolean = false
            override val kittyTextSizingScale: Boolean = false
            override val kittyTextSizingWidth: Boolean = false
            override val kittyUnderline: Boolean = false
            override val synchronizedOutput: Boolean = syncSupported
            override val themeEvents: Boolean = false
        }
        override val events: ReceiveChannel<Event> get() = eventChannel

        fun resize(columns: Int, rows: Int) {
            sizeFlow.value = Terminal.Size(columns, rows)
        }

        override fun close() {
            eventChannel.close()
        }
    }

    /** A simple component that emits a fixed list of lines. Test-only. */
    private class StaticComponent(var lines: List<String>) : Component {
        override fun render(width: Int): List<String> = lines
    }

    /** Component that returns a line wider than the supplied width — for overflow tests. */
    private class OverflowingComponent : Component {
        override fun render(width: Int): List<String> {
            // Always returns a line 2× wider than the viewport.
            return listOf("x".repeat(width * 2))
        }
    }

    /** Focusable component that records whatever keys it received. */
    private class KeyRecorder(var lines: List<String> = listOf("recorder")) : Component, Focusable {
        override var focused: Boolean = false
        val received: MutableList<KeyboardEvent> = mutableListOf()
        var consume: Boolean = true
        override fun render(width: Int): List<String> = lines
        override fun handleInput(key: KeyboardEvent): Boolean {
            received += key
            return consume
        }
    }

    private fun newTUI(
        terminal: FakeTerminal = FakeTerminal(),
        output: FakeOutput = FakeOutput(),
        scope: CoroutineScope = TestScope(),
    ): Triple<TUI, FakeTerminal, FakeOutput> {
        val tui = TUI(terminal, output, scope, ioDispatcher = Dispatchers.Unconfined)
        return Triple(tui, terminal, output)
    }

    // ────────────────────────────────────────────────────────────────────────
    // Tests
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class FirstRender {
        @Test
        fun `writes expected lines with no cursor-up`() {
            val (tui, _, output) = newTUI()
            tui.addChild(StaticComponent(listOf("hello", "world")))
            tui.renderOnce()

            val combined = output.combined
            // Contains the content.
            assertContains(combined, "hello")
            assertContains(combined, "world")
            // Two lines separated by CRLF.
            assertContains(combined, "hello\r\nworld")
            // No cursor-up (A) in first render.
            assertFalse(combined.contains("\u001b[1A"), "first render must not emit cursor-up")
            assertFalse(combined.contains("\u001b[2A"), "first render must not emit cursor-up")
            // No scrollback clear in first render.
            assertFalse(combined.contains(Ansi.CLEAR_SCROLLBACK), "first render must not clear scrollback")
        }

        @Test
        fun `wraps output in synchronized-output markers when supported`() {
            val (tui, _, output) = newTUI(terminal = FakeTerminal(syncSupported = true))
            tui.addChild(StaticComponent(listOf("hello")))
            tui.renderOnce()

            val combined = output.combined
            assertContains(combined, Ansi.SYNC_BEGIN)
            assertContains(combined, Ansi.SYNC_END)
        }

        @Test
        fun `omits synchronized-output markers when unsupported`() {
            val (tui, _, output) = newTUI(terminal = FakeTerminal(syncSupported = false))
            tui.addChild(StaticComponent(listOf("hello")))
            tui.renderOnce()

            val combined = output.combined
            assertFalse(combined.contains(Ansi.SYNC_BEGIN), "SYNC_BEGIN must be absent when unsupported")
            assertFalse(combined.contains(Ansi.SYNC_END), "SYNC_END must be absent when unsupported")
        }
    }

    @Nested
    inner class IdenticalRerender {
        @Test
        fun `emits no change sequences when content is unchanged`() {
            val (tui, _, output) = newTUI()
            tui.addChild(StaticComponent(listOf("hello", "world")))
            tui.renderOnce()
            val firstWrites = output.writes.size
            output.writes.clear()

            tui.renderOnce()

            // The diff path may still emit the sync markers + a cursor-position move
            // (CSI G), but must NOT re-emit the content itself.
            val second = output.combined
            assertFalse(
                second.contains("hello") || second.contains("world"),
                "re-render of identical frame should not re-emit content, got: ${second.toDebug()}",
            )
            assertTrue(firstWrites > 0, "sanity: first render wrote something")
        }
    }

    @Nested
    inner class MiddleLineChange {
        @Test
        fun `only rewrites the changed row with clear-line prefix`() {
            val (tui, _, output) = newTUI()
            val content = StaticComponent(listOf("top", "middle", "bottom"))
            tui.addChild(content)
            tui.renderOnce()
            output.writes.clear()

            content.lines = listOf("top", "MIDDLE", "bottom")
            tui.renderOnce()

            val second = output.combined
            // The new content for the changed row must appear.
            assertContains(second, "MIDDLE")
            // CLEAR_LINE is used before rewriting.
            assertContains(second, Ansi.CLEAR_LINE)
            // Unchanged lines are NOT re-emitted.
            assertFalse(second.contains("top"), "unchanged 'top' should not be re-emitted")
            assertFalse(second.contains("bottom"), "unchanged 'bottom' should not be re-emitted")
        }
    }

    @Nested
    inner class AppendLine {
        @Test
        fun `appending a line scrolls with CRLF only`() {
            val (tui, _, output) = newTUI()
            val content = StaticComponent(listOf("a", "b"))
            tui.addChild(content)
            tui.renderOnce()
            output.writes.clear()

            content.lines = listOf("a", "b", "c")
            tui.renderOnce()

            val second = output.combined
            assertContains(second, "c")
            assertContains(second, "\r\n")
            // No cursor-up instructions — the append scrolls naturally.
            assertFalse(
                second.contains("\u001b[1A") || second.contains("\u001b[2A"),
                "append path must not emit cursor-up",
            )
            // Nothing else (original content) is re-emitted.
            assertFalse(second.startsWith("a"), "original content should not be re-emitted")
        }
    }

    @Nested
    inner class RemoveLastLine {
        @Test
        fun `clears trailing row with CLEAR_LINE`() {
            val (tui, _, output) = newTUI()
            val content = StaticComponent(listOf("a", "b", "c"))
            tui.addChild(content)
            tui.renderOnce()
            output.writes.clear()

            content.lines = listOf("a", "b")
            tui.renderOnce()

            val second = output.combined
            // A clear-line must be emitted for the removed row.
            assertContains(second, Ansi.CLEAR_LINE)
            // The "c" content is not present as new output.
            assertFalse(second.contains("c"), "removed row content must not be re-emitted")
        }
    }

    @Nested
    inner class ResizeTriggersFullRedraw {
        @Test
        fun `ResizeEvent clears viewport and re-renders but preserves scrollback`() {
            val (tui, terminal, output) = newTUI()
            tui.addChild(StaticComponent(listOf("alpha", "beta")))
            tui.renderOnce()
            output.writes.clear()

            // Simulate terminal resize: size flow changes + event dispatched.
            terminal.resize(120, 30)
            tui.handleEvent(ResizeEvent(columns = 120, rows = 30, width = 0, height = 0))
            // The resize handler schedules a force render; drive it synchronously.
            tui.renderOnce(force = true)

            val second = output.combined
            // Must home the cursor and clear the visible viewport.
            assertContains(second, Ansi.CURSOR_HOME)
            assertContains(second, Ansi.CLEAR_DISPLAY)
            // MUST NOT clear scrollback — that would wipe the user's pre-TUI
            // shell history and all older TUI frames.
            assertFalse(
                second.contains(Ansi.CLEAR_SCROLLBACK),
                "resize must preserve scrollback so pre-TUI shell history survives",
            )
            // Must re-emit the content from scratch.
            assertContains(second, "alpha")
            assertContains(second, "beta")
        }
    }

    @Nested
    inner class WidthOverflow {
        @Test
        fun `throws with component name and dimensions on overflow`() {
            val (tui, _, _) = newTUI()
            tui.addChild(OverflowingComponent())

            val ex = assertFailsWith<TUIRenderOverflowException> {
                tui.renderOnce()
            }
            assertEquals("OverflowingComponent", ex.componentName)
            assertEquals(80, ex.allowedWidth)
            assertTrue(ex.measuredWidth > ex.allowedWidth)
            // Message contains helpful detail.
            assertContains(ex.message ?: "", "OverflowingComponent")
            assertContains(ex.message ?: "", "visible width")
        }
    }

    @Nested
    inner class FocusRouting {
        @Test
        fun `focused component receives KeyboardEvent`() {
            val (tui, _, _) = newTUI()
            val recorder = KeyRecorder()
            tui.addChild(recorder)
            tui.setFocus(recorder)

            tui.handleEvent(KeyboardEvent(codepoint = 'a'.code))

            assertEquals(1, recorder.received.size)
            assertEquals('a'.code, recorder.received[0].codepoint)
        }

        @Test
        fun `non-focused component does not receive KeyboardEvent`() {
            val (tui, _, _) = newTUI()
            val focused = KeyRecorder(lines = listOf("focused"))
            val other = KeyRecorder(lines = listOf("other"))
            tui.addChild(focused)
            tui.addChild(other)
            tui.setFocus(focused)

            tui.handleEvent(KeyboardEvent(codepoint = 'a'.code))

            assertEquals(1, focused.received.size)
            assertEquals(0, other.received.size, "non-focused component must not receive key")
        }

        @Test
        fun `no events are dispatched when nothing is focused`() {
            val (tui, _, _) = newTUI()
            val recorder = KeyRecorder()
            tui.addChild(recorder)
            // No setFocus call.

            tui.handleEvent(KeyboardEvent(codepoint = 'x'.code))

            assertEquals(0, recorder.received.size, "without focus, no keys are dispatched")
        }

        @Test
        fun `setFocus updates focused flag on Focusable components`() {
            val (tui, _, _) = newTUI()
            val a = KeyRecorder()
            val b = KeyRecorder()

            tui.setFocus(a)
            assertTrue(a.focused)
            assertFalse(b.focused)

            tui.setFocus(b)
            assertFalse(a.focused, "losing focus must clear the flag")
            assertTrue(b.focused)

            tui.setFocus(null)
            assertFalse(b.focused, "clearing focus must clear the flag")
            assertNull(tui.focused)
        }
    }

    @Nested
    inner class Debouncing {
        @Test
        fun `rapid requestRender calls collapse into a single pending flag`() {
            // The debouncing contract: `requestRender` is idempotent — calling it 10
            // times in a row is indistinguishable from calling it once. The render
            // loop consumes the flag exactly once per render window, regardless of
            // how many requests were made in that window.
            val (tui, _, _) = newTUI()
            tui.addChild(StaticComponent(listOf("hello")))

            // Initially no render is pending.
            assertFalse(tui.renderRequested, "fresh TUI must not have pending render")

            // 10 rapid calls all set the same flag — no counter to leak.
            repeat(10) { tui.requestRender() }
            assertTrue(tui.renderRequested, "any request must set the flag")

            // Even requesting hundreds of times doesn't create multiple renders,
            // because the flag is a single boolean — there is no queue to drain.
            repeat(100) { tui.requestRender() }
            assertTrue(tui.renderRequested, "flag is still just set once")
        }

        @Test
        fun `virtual-time scheduler coalesces 10 requests into one render`() = runTest {
            val output = FakeOutput()
            val terminal = FakeTerminal()
            // Use the Unconfined test dispatcher so launched coroutines run
            // inline — our `start()` launches jobs that need to enter their
            // initial `delay(…)` before we can meaningfully advance time.
            val tui = TUI(
                terminal = terminal,
                output = output,
                scope = this,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            )
            tui.addChild(StaticComponent(listOf("hello")))
            tui.start()

            // Let the render loop complete its initial frame.
            advanceTimeBy(MIN_RENDER_INTERVAL_MS * 4)
            runCurrent()
            val baselineCount = tui.renderCount

            // 10 rapid calls within the same test tick.
            repeat(10) { tui.requestRender() }

            // Advance past enough frame windows for the burst to either
            // coalesce (expected: +1 render) or leak (multiple).
            advanceTimeBy(MIN_RENDER_INTERVAL_MS * 4)
            runCurrent()

            val renders = tui.renderCount - baselineCount
            tui.stop()

            assertTrue(baselineCount >= 1, "start() must produce initial render, got $baselineCount")
            assertEquals(
                1,
                renders,
                "10 rapid requestRender() calls should coalesce into 1 render, got $renders (baseline=$baselineCount, total=${tui.renderCount})",
            )
        }
    }

    @Nested
    inner class StartStopLifecycle {
        @Test
        fun `start enables raw mode and shows no cursor`() {
            val output = FakeOutput()
            val scope = TestScope()
            val tui = TUI(FakeTerminal(), output, scope, ioDispatcher = Dispatchers.Unconfined)

            tui.start()

            assertEquals(1, output.enableRawModeCalled, "start() must enable raw mode")
            val combined = output.combined
            assertContains(combined, Ansi.CURSOR_HIDE)
            assertContains(combined, Ansi.BRACKETED_PASTE_ON)

            tui.stop()
        }

        @Test
        fun `stop is idempotent`() {
            val output = FakeOutput()
            val scope = TestScope()
            val tui = TUI(FakeTerminal(), output, scope, ioDispatcher = Dispatchers.Unconfined)
            tui.start()

            tui.stop()
            val resetAfterFirstStop = output.resetCalled
            tui.stop()
            tui.stop()

            // Reset should not be called again on repeated stops.
            assertEquals(resetAfterFirstStop, output.resetCalled, "stop is idempotent")
        }

        @Test
        fun `stop restores terminal state`() {
            val output = FakeOutput()
            val scope = TestScope()
            val tui = TUI(FakeTerminal(), output, scope, ioDispatcher = Dispatchers.Unconfined)
            tui.addChild(StaticComponent(listOf("hello")))
            tui.start()
            tui.renderOnce()
            output.writes.clear()

            tui.stop()

            assertEquals(1, output.resetCalled, "stop must reset the terminal")
            val combined = output.combined
            assertContains(combined, Ansi.CURSOR_SHOW)
            assertContains(combined, Ansi.BRACKETED_PASTE_OFF)
        }
    }

    @Nested
    inner class ForceFullRedraw {
        @Test
        fun `forceFullRedraw clears viewport and re-emits content without touching scrollback`() {
            val (tui, _, output) = newTUI()
            tui.addChild(StaticComponent(listOf("hello")))
            tui.renderOnce()
            output.writes.clear()

            tui.renderOnce(force = true)

            val combined = output.combined
            assertContains(combined, Ansi.CURSOR_HOME)
            assertContains(combined, Ansi.CLEAR_DISPLAY)
            // Explicitly NOT clearing scrollback — preserving Claude-style
            // terminal history across force redraws.
            assertFalse(
                combined.contains(Ansi.CLEAR_SCROLLBACK),
                "force redraw must preserve scrollback",
            )
            assertContains(combined, "hello", message = "force redraw must re-emit content")
        }
    }

    companion object {
        /** Mirrors the constant in TUI for test time math. */
        private const val MIN_RENDER_INTERVAL_MS: Long = 16L

        /** Render control chars printable for error messages. */
        private fun String.toDebug(): String = buildString {
            for (ch in this@toDebug) {
                when (ch) {
                    '\r' -> append("\\r")
                    '\n' -> append("\\n")
                    '\u001b' -> append("\\x1b")
                    else -> append(ch)
                }
            }
        }
    }
}
