package fraggle.tui.core

import com.jakewharton.mosaic.terminal.Event
import com.jakewharton.mosaic.terminal.FocusEvent
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.ResizeEvent
import com.jakewharton.mosaic.terminal.Terminal
import com.jakewharton.mosaic.tty.Tty
import fraggle.tui.text.Ansi
import fraggle.tui.text.visibleWidth
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Abstraction over the write + lifecycle side of a terminal.
 *
 * In production this is backed by a [Tty] ([TtyOutput]); tests inject a fake
 * implementation that captures writes into a buffer without touching native
 * resources. This decouples the runtime from the native Tty binary so the
 * event-loop logic is unit-testable.
 */
interface TerminalOutput {
    /** Write UTF-8 encoded [text] to the terminal. Must be atomic per call. */
    fun write(text: String)

    /** Enable raw mode. Idempotent safe. Called once from [TUI.start]. */
    fun enableRawMode()

    /** Restore terminal state (undo raw mode etc.). Called from [TUI.stop]. */
    fun reset()

    /**
     * Query the terminal's current size directly. Intended for the first
     * frame, before Mosaic has seen a resize event.
     *
     * Mosaic's [com.jakewharton.mosaic.terminal.Terminal.state] exposes size
     * as a [kotlinx.coroutines.flow.StateFlow] that starts at
     * `Terminal.Size.Default` (80×24) and only updates when a resize event
     * fires. That means the first render draws at 80×24 regardless of the
     * actual terminal size; users with larger terminals see content clipped
     * or mis-wrapped until they resize manually.
     *
     * Implementations that can query the OS directly (e.g. via `ioctl
     * TIOCGWINSZ` inside [Tty.currentSize]) should return the real size here.
     * Return null when unavailable — callers fall back to
     * `terminal.state.size.value`.
     *
     * Default: null (unavailable).
     */
    fun currentSize(): Terminal.Size? = null
}

/** [TerminalOutput] backed by a real [Tty]. */
class TtyOutput(private val tty: Tty) : TerminalOutput {
    override fun write(text: String) {
        val bytes = text.encodeToByteArray()
        var offset = 0
        while (offset < bytes.size) {
            val written = tty.write(bytes, offset, bytes.size - offset)
            if (written <= 0) break
            offset += written
        }
    }

    override fun enableRawMode() {
        tty.enableRawMode()
    }

    override fun reset() {
        tty.reset()
    }

    /**
     * Queries `Tty.currentSize()` which is implemented via `ioctl TIOCGWINSZ`
     * on POSIX and `GetConsoleScreenBufferInfo` on Windows. Cheap (microseconds)
     * — safe to call once per render without measurable overhead. Any thrown
     * exception or malformed response collapses to null so callers can fall
     * back to the state-flow value.
     */
    override fun currentSize(): Terminal.Size? {
        val arr = try {
            tty.currentSize()
        } catch (_: Throwable) {
            return null
        }
        if (arr.size < 2) return null
        val columns = arr[0]
        val rows = arr[1]
        if (columns <= 0 || rows <= 0) return null
        val width = if (arr.size >= 3) arr[2] else 0
        val height = if (arr.size >= 4) arr[3] else 0
        return Terminal.Size(columns, rows, width, height)
    }
}

/**
 * The TUI runtime: a root [Container] plus a differential renderer wired to a
 * Mosaic [Terminal] for state/events and a [TerminalOutput] for writes.
 *
 * ## Rendering model
 *
 * Each frame, [render] produces a flat [List] of lines from the component tree.
 * These are diffed against the previous frame's lines; only changed lines are
 * rewritten, and the entire write is wrapped in DEC synchronized output mode
 * (CSI ? 2026 h/l) when supported so partial frames never appear.
 *
 * As content grows, new lines are appended with `\r\n` and scroll naturally into
 * the terminal's native scrollback — no cursor-up-and-overwrite of content that
 * has left the viewport.
 *
 * ## Resize handling
 *
 * On [ResizeEvent] the runtime emits `CSI H CSI 2J CSI 3J` (home + clear
 * visible viewport + clear scrollback), invalidates width-dependent caches
 * on every child, and performs a full redraw at the new width.
 *
 * The scrollback clear is load-bearing: because each full redraw re-emits
 * the entire component tree with `\r\n` separators, content scrolls into
 * scrollback storage whenever the frame exceeds the viewport. Without the
 * scrollback clear, every resize would pile up another copy of the session
 * history in scrollback storage (same content, different wrap). The clear
 * keeps scrollback storage in lockstep with the just-emitted frame.
 *
 * Tradeoff: content that was in scrollback before the TUI started (the
 * user's earlier shell prompts + output) is wiped on resize. Session history
 * remains visible by scrolling up — what scrolled off the re-emitted frame
 * naturally repopulates scrollback storage.
 *
 * ## Overflow safety
 *
 * Any rendered line whose visible width exceeds the terminal width aborts the
 * render with a detailed error message. This turns the single biggest source of
 * TUI instability into a loud, fixable crash instead of silent corruption.
 */
class TUI(
    private val terminal: Terminal,
    private val output: TerminalOutput,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Container() {

    // ── Diff state ──────────────────────────────────────────────────────────
    private var previousLines: List<String> = emptyList()
    private var previousWidth: Int = 0
    private var previousHeight: Int = 0
    private var previousViewportTop: Int = 0
    private var hardwareCursorRow: Int = 0
    private var maxLinesRendered: Int = 0

    // ── Focus ───────────────────────────────────────────────────────────────
    private var focusedComponent: Component? = null

    // ── Lifecycle ───────────────────────────────────────────────────────────
    @Volatile private var started: Boolean = false
    @Volatile private var stopped: Boolean = false
    private var eventLoopJob: Job? = null
    private var renderLoopJob: Job? = null
    private val renderMutex: Mutex = Mutex()

    // ── Render scheduling ───────────────────────────────────────────────────
    @Volatile internal var renderRequested: Boolean = false
        private set
    @Volatile internal var forcePending: Boolean = false
        private set
    private var hasRendered: Boolean = false

    // Exposed for tests/metrics.
    @Volatile
    var fullRedrawCount: Int = 0
        private set

    /** Total number of render passes (full + differential + no-op). */
    @Volatile
    var renderCount: Int = 0
        private set

    /** Start the event pump and draw the first frame. Idempotent. */
    fun start() {
        if (started) return
        started = true
        stopped = false

        output.enableRawMode()
        output.write(Ansi.CURSOR_HIDE)
        output.write(Ansi.BRACKETED_PASTE_ON)

        // Kick off the event loop that consumes terminal.events.
        eventLoopJob = scope.launch(ioDispatcher) {
            runEventLoop(terminal.events)
        }
        // Kick off the render scheduler loop.
        renderLoopJob = scope.launch(ioDispatcher) {
            runRenderLoop()
        }

        // Draw the first frame.
        requestRender()
    }

    /** Stop the event pump, restore terminal state, release outputs. Idempotent. */
    fun stop() {
        if (stopped) return
        stopped = true
        started = false

        // Cancel background jobs.
        renderLoopJob?.cancel()
        eventLoopJob?.cancel()
        renderLoopJob = null
        eventLoopJob = null

        // Move cursor to a safe position after the last rendered line.
        try {
            if (previousLines.isNotEmpty()) {
                val targetRow = previousLines.size
                val lineDiff = targetRow - hardwareCursorRow
                val sb = StringBuilder()
                if (lineDiff > 0) sb.append(Ansi.CSI).append(lineDiff).append('B')
                else if (lineDiff < 0) sb.append(Ansi.CSI).append(-lineDiff).append('A')
                sb.append("\r\n")
                output.write(sb.toString())
            }
            output.write(Ansi.BRACKETED_PASTE_OFF)
            output.write(Ansi.CURSOR_SHOW)
        } finally {
            // Always reset raw mode even if a write threw.
            runCatching { output.reset() }
            runCatching {
                (terminal as? AutoCloseable)?.close()
            }
        }
    }

    /**
     * Request the runtime redraw on the next frame. Safe to call from any thread.
     * Debounced to at most ~60 fps ([MIN_RENDER_INTERVAL_MS]).
     *
     * @param force When true, schedules a full scrollback-clear + redraw.
     */
    fun requestRender(force: Boolean = false) {
        if (force) forcePending = true
        renderRequested = true
    }

    /** Force a full redraw with a scrollback clear on the next frame. */
    fun forceFullRedraw() {
        requestRender(force = true)
    }

    /** Set the component that should receive keyboard events. */
    fun setFocus(component: Component?) {
        val prev = focusedComponent
        if (prev === component) return
        if (prev is Focusable) prev.focused = false
        focusedComponent = component
        if (component is Focusable) component.focused = true
        requestRender()
    }

    /** The currently-focused component, or null. */
    val focused: Component?
        get() = focusedComponent

    // ── Event loop ─────────────────────────────────────────────────────────

    private suspend fun runEventLoop(events: ReceiveChannel<Event>) {
        try {
            for (event in events) {
                if (stopped) return
                handleEvent(event)
            }
        } catch (_: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
            // Normal shutdown path.
        }
    }

    internal fun handleEvent(event: Event) {
        when (event) {
            is ResizeEvent -> {
                // Resize = full redraw (scrollback clear + re-render from scratch).
                // Invalidate every component so width-dependent caches drop.
                invalidate()
                requestRender(force = true)
            }
            is KeyboardEvent -> {
                // Only the focused component receives keyboard events.
                // Non-focused children in the tree do not.
                val focused = focusedComponent
                if (focused != null) {
                    focused.handleInput(event)
                    requestRender()
                }
            }
            is FocusEvent -> {
                // No-op for now; hooks could live here later.
            }
            else -> Unit
        }
    }

    // ── Render scheduler ───────────────────────────────────────────────────

    /**
     * Debounce loop:
     *  - Poll every [MIN_RENDER_INTERVAL_MS] for pending work.
     *  - When a render request is pending, sleep for one full interval and
     *    then drain the flag. Any calls to [requestRender] made during the
     *    sleep window coalesce into the same render.
     *
     * We use [delay] exclusively so virtual-time test schedulers can drive
     * the loop deterministically (unlike [TimeSource.Monotonic] which tracks
     * wall-clock time and is invisible to TestScope).
     */
    private suspend fun runRenderLoop() {
        while (scope.isActive && !stopped) {
            // Wait for a frame to be requested.
            if (!renderRequested) {
                delay(MIN_RENDER_INTERVAL_MS)
                continue
            }
            // Coalesce: sleep one frame so bursts of requests in this window
            // merge into a single render.
            delay(MIN_RENDER_INTERVAL_MS)
            if (stopped || !renderRequested) continue

            renderRequested = false
            val force = forcePending
            forcePending = false
            renderMutex.withLock {
                if (!stopped) {
                    try {
                        doRender(force)
                    } catch (t: Throwable) {
                        // Overflow errors (or anything the render path throws) must not
                        // leave the terminal in a broken state. Restore before propagating.
                        runCatching { stop() }
                        throw t
                    }
                }
            }
        }
    }

    /**
     * Render synchronously (for tests). Normally the render loop drives this
     * via [requestRender]; the test entry point lets tests snap a single frame
     * without racing against the scheduler.
     */
    internal fun renderOnce(force: Boolean = false) {
        doRender(force)
    }

    // ── Differential render ────────────────────────────────────────────────

    private fun doRender(forceParam: Boolean) {
        renderCount += 1
        // Prefer the output's direct ioctl-backed size query. Mosaic's
        // state.size flow starts at Size.Default (80x24) and only updates
        // on resize events — using it alone would render the first frame
        // at the wrong dimensions on any non-80x24 terminal. See the
        // [TerminalOutput.currentSize] docs for the full story.
        val size = output.currentSize() ?: terminal.state.size.value
        val width = max(1, size.columns)
        val height = max(1, size.rows)

        val widthChanged = previousWidth != 0 && previousWidth != width
        val heightChanged = previousHeight != 0 && previousHeight != height

        var force = forceParam
        if (force) {
            previousLines = emptyList()
            previousWidth = -1
            previousHeight = -1
            previousViewportTop = 0
            hardwareCursorRow = 0
            maxLinesRendered = 0
        }

        // 1. Render component tree.
        val rawLines = render(width)

        // 2. Extract cursor marker + strip from output lines.
        val (newLines, cursorPos) = extractCursorPosition(rawLines, height)

        // 3. Overflow check — crash loudly if any line exceeds terminal width.
        enforceWidthContract(newLines, width)

        // Determine if this is the first render (no prior state).
        val firstRender = !hasRendered && !widthChanged && !heightChanged && !force

        if (firstRender) {
            writeFullFrame(newLines, width, height, clear = false, cursorPos = cursorPos)
            hasRendered = true
            return
        }

        if (widthChanged || heightChanged || force) {
            writeFullFrame(newLines, width, height, clear = true, cursorPos = cursorPos)
            return
        }

        // Differential path.
        writeDiffFrame(newLines, width, height, cursorPos)
    }

    /**
     * Full render path: clear (optionally scrollback too), then emit every line
     * with `\r\n` separators. Used for the first render, resize, and force.
     */
    private fun writeFullFrame(
        newLines: List<String>,
        width: Int,
        height: Int,
        clear: Boolean,
        cursorPos: CursorPos?,
    ) {
        fullRedrawCount += 1
        val sb = StringBuilder()
        val sync = terminal.capabilities.synchronizedOutput
        if (sync) sb.append(Ansi.SYNC_BEGIN)
        if (clear) {
            // Home + clear viewport + clear scrollback. Without the
            // scrollback clear, the full tree re-emission (via `\r\n`
            // separators) scrolls content into scrollback storage, producing
            // a new copy on every resize — multiple resizes pile up
            // duplicate history.
            sb.append(Ansi.CURSOR_HOME)
            sb.append(Ansi.CLEAR_DISPLAY)
            sb.append(Ansi.CLEAR_SCROLLBACK)
        }
        for (i in newLines.indices) {
            if (i > 0) sb.append("\r\n")
            sb.append(newLines[i])
        }
        if (sync) sb.append(Ansi.SYNC_END)
        output.write(sb.toString())

        val lastRow = max(0, newLines.size - 1)
        hardwareCursorRow = lastRow
        val bufferLength = max(height, newLines.size)
        previousViewportTop = max(0, bufferLength - height)
        maxLinesRendered = if (clear) newLines.size else max(maxLinesRendered, newLines.size)

        positionHardwareCursor(cursorPos, newLines.size)

        previousLines = newLines
        previousWidth = width
        previousHeight = height
    }

    /**
     * Differential render path. Finds the first and last changed rows,
     * moves the cursor there, clears each row with `\x1b[K`, and rewrites.
     * Appended rows go through `\r\n` so they scroll naturally.
     */
    private fun writeDiffFrame(
        newLines: List<String>,
        width: Int,
        height: Int,
        cursorPos: CursorPos?,
    ) {
        // Find bounds of the change.
        var firstChanged = -1
        var lastChanged = -1
        val maxLines = max(newLines.size, previousLines.size)
        for (i in 0 until maxLines) {
            val oldLine = if (i < previousLines.size) previousLines[i] else ""
            val newLine = if (i < newLines.size) newLines[i] else ""
            if (oldLine != newLine) {
                if (firstChanged == -1) firstChanged = i
                lastChanged = i
            }
        }

        val appended = newLines.size > previousLines.size
        if (appended) {
            if (firstChanged == -1) firstChanged = previousLines.size
            lastChanged = newLines.size - 1
        }

        // No changes: still re-position hardware cursor if it may have moved.
        if (firstChanged == -1) {
            positionHardwareCursor(cursorPos, newLines.size)
            previousHeight = height
            return
        }

        // If the only "changes" are deleted trailing lines (firstChanged is beyond
        // the new content), handle that case separately — no lines to rewrite.
        if (firstChanged >= newLines.size) {
            writeDeletionFrame(newLines, height, cursorPos)
            return
        }

        // Detect "append after existing content" vs "modify existing rows":
        // appendStart = first changed row is the first row after the previous content.
        val appendStart = appended &&
            firstChanged == previousLines.size &&
            firstChanged > 0

        val sync = terminal.capabilities.synchronizedOutput
        val sb = StringBuilder()
        if (sync) sb.append(Ansi.SYNC_BEGIN)

        // Move cursor to the first changed row.
        val moveTargetRow = if (appendStart) firstChanged - 1 else firstChanged
        val lineDiff = moveTargetRow - hardwareCursorRow
        if (lineDiff > 0) sb.append(Ansi.CSI).append(lineDiff).append('B')
        else if (lineDiff < 0) sb.append(Ansi.CSI).append(-lineDiff).append('A')
        // Column 0.
        sb.append(if (appendStart) "\r\n" else "\r")

        // Rewrite each changed row from firstChanged..lastChanged (clamped to new size).
        val renderEnd = min(lastChanged, newLines.size - 1)
        for (i in firstChanged..renderEnd) {
            if (i > firstChanged) sb.append("\r\n")
            sb.append(Ansi.CLEAR_LINE)
            sb.append(newLines[i])
        }

        var finalCursorRow = renderEnd

        // If the previous frame had more lines, clear the trailing rows.
        if (previousLines.size > newLines.size) {
            if (renderEnd < newLines.size - 1) {
                val moveDown = newLines.size - 1 - renderEnd
                sb.append(Ansi.CSI).append(moveDown).append('B')
                finalCursorRow = newLines.size - 1
            }
            val extraLines = previousLines.size - newLines.size
            for (j in 0 until extraLines) {
                sb.append("\r\n").append(Ansi.CLEAR_LINE)
            }
            // Move cursor back up to end of new content.
            sb.append(Ansi.CSI).append(extraLines).append('A')
        }

        if (sync) sb.append(Ansi.SYNC_END)
        output.write(sb.toString())

        hardwareCursorRow = finalCursorRow
        maxLinesRendered = max(maxLinesRendered, newLines.size)
        previousViewportTop = max(previousViewportTop, finalCursorRow - height + 1)

        positionHardwareCursor(cursorPos, newLines.size)

        previousLines = newLines
        previousWidth = width
        previousHeight = height
    }

    /**
     * Deletion-only frame: newLines is shorter than previousLines and all changes
     * are in the deleted trailing region. Move to the last valid row, clear the
     * trailing rows.
     */
    private fun writeDeletionFrame(
        newLines: List<String>,
        height: Int,
        cursorPos: CursorPos?,
    ) {
        val sync = terminal.capabilities.synchronizedOutput
        val sb = StringBuilder()
        if (sync) sb.append(Ansi.SYNC_BEGIN)

        val targetRow = max(0, newLines.size - 1)
        val lineDiff = targetRow - hardwareCursorRow
        if (lineDiff > 0) sb.append(Ansi.CSI).append(lineDiff).append('B')
        else if (lineDiff < 0) sb.append(Ansi.CSI).append(-lineDiff).append('A')
        sb.append("\r")

        val extraLines = previousLines.size - newLines.size
        for (j in 0 until extraLines) {
            sb.append("\r\n").append(Ansi.CLEAR_LINE)
        }
        if (extraLines > 0) {
            sb.append(Ansi.CSI).append(extraLines).append('A')
        }

        if (sync) sb.append(Ansi.SYNC_END)
        output.write(sb.toString())

        hardwareCursorRow = targetRow
        positionHardwareCursor(cursorPos, newLines.size)
        previousLines = newLines
        previousHeight = height
    }

    // ── Cursor marker extraction ───────────────────────────────────────────

    private data class CursorPos(val row: Int, val col: Int)

    /**
     * Search [lines] (in bottom-up order, visible viewport only) for
     * [CURSOR_MARKER]. If found, strip it from the line, compute its visible
     * column, and return the position. Otherwise return null + lines unchanged.
     */
    private fun extractCursorPosition(
        lines: List<String>,
        height: Int,
    ): Pair<List<String>, CursorPos?> {
        val viewportTop = max(0, lines.size - height)
        for (row in (lines.size - 1) downTo viewportTop) {
            val line = lines[row]
            val markerIndex = line.indexOf(CURSOR_MARKER)
            if (markerIndex != -1) {
                val before = line.substring(0, markerIndex)
                val col = visibleWidth(before)
                val stripped = before + line.substring(markerIndex + CURSOR_MARKER.length)
                val mutable = lines.toMutableList()
                mutable[row] = stripped
                return mutable to CursorPos(row, col)
            }
        }
        return lines to null
    }

    /**
     * Position the hardware cursor for IME/focused-component rendering. When
     * no [cursorPos] is set, the cursor stays hidden. When set (and the
     * focused component is [Focusable]), move the cursor there and show it.
     */
    private fun positionHardwareCursor(cursorPos: CursorPos?, totalLines: Int) {
        if (cursorPos == null || totalLines <= 0) {
            output.write(Ansi.CURSOR_HIDE)
            return
        }

        val targetRow = cursorPos.row.coerceIn(0, totalLines - 1)
        val targetCol = max(0, cursorPos.col)

        val sb = StringBuilder()
        val rowDelta = targetRow - hardwareCursorRow
        if (rowDelta > 0) sb.append(Ansi.CSI).append(rowDelta).append('B')
        else if (rowDelta < 0) sb.append(Ansi.CSI).append(-rowDelta).append('A')
        // Absolute column (1-indexed).
        sb.append(Ansi.CSI).append(targetCol + 1).append('G')

        val focused = focusedComponent
        if (focused is Focusable) {
            sb.append(Ansi.CURSOR_SHOW)
        } else {
            sb.append(Ansi.CURSOR_HIDE)
        }

        if (sb.isNotEmpty()) output.write(sb.toString())
        hardwareCursorRow = targetRow
    }

    // ── Width-overflow enforcement ─────────────────────────────────────────

    private fun enforceWidthContract(lines: List<String>, width: Int) {
        for (i in lines.indices) {
            val w = visibleWidth(lines[i])
            if (w > width) {
                val componentName = locateOverflowingComponent(i, width)
                val truncated = lines[i].take(200)
                throw TUIRenderOverflowException(
                    componentName = componentName,
                    rowIndex = i,
                    measuredWidth = w,
                    allowedWidth = width,
                    offendingLine = truncated,
                )
            }
        }
    }

    /**
     * Walk the child tree rendering each child separately at [width] to find
     * which one produced the offending row. Best-effort — returns "unknown" if
     * it can't be localized.
     */
    private fun locateOverflowingComponent(rowIndex: Int, width: Int): String {
        var row = 0
        for (child in children) {
            val childLines = try {
                child.render(width)
            } catch (_: Throwable) {
                return child::class.simpleName ?: "unknown"
            }
            if (rowIndex in row until (row + childLines.size)) {
                return child::class.simpleName ?: "unknown"
            }
            row += childLines.size
        }
        return "unknown"
    }

    companion object {
        /** Minimum interval between frames in milliseconds. Caps render rate at ~60fps. */
        internal const val MIN_RENDER_INTERVAL_MS: Long = 16L
    }
}

/**
 * Thrown when a component renders a line wider than the current terminal width.
 * This is a bug in the offending component, not the runtime.
 */
class TUIRenderOverflowException(
    val componentName: String,
    val rowIndex: Int,
    val measuredWidth: Int,
    val allowedWidth: Int,
    val offendingLine: String,
) : RuntimeException(
    buildString {
        append("TUI render overflow: component '")
        append(componentName)
        append("' produced line ")
        append(rowIndex)
        append(" with visible width ")
        append(measuredWidth)
        append(" but terminal width is only ")
        append(allowedWidth)
        append(". Offending line (truncated to 200 chars): ")
        append(offendingLine)
    },
)
