package fraggle.coding.ui

import com.jakewharton.mosaic.terminal.KeyboardEvent
import fraggle.coding.session.SessionPreview
import fraggle.coding.session.SessionSummary
import fraggle.tui.core.Component
import fraggle.tui.core.Focusable
import fraggle.tui.input.matches
import fraggle.tui.text.Ansi
import fraggle.tui.text.padRightToWidth
import fraggle.tui.text.truncateToWidth
import fraggle.tui.theme.theme
import kotlin.math.max
import kotlin.math.min

/**
 * User's response to the session picker.
 */
sealed class SessionPickerResult {
    /** User pressed Enter on a specific session. */
    data class Selected(val summary: SessionSummary) : SessionPickerResult()

    /** User pressed `n` / `N` to start a fresh session instead. */
    object NewSession : SessionPickerResult()

    /** User pressed Esc or Ctrl+C — no selection, no new session, they want out. */
    object Cancelled : SessionPickerResult()
}

/**
 * TUI list component for choosing which session to resume.
 *
 * Presents one row per [SessionPreview] with a relative timestamp, message
 * count, and a preview of the first user message. Keyboard navigation:
 *
 *  - `↑` / `↓`, `k` / `j` — move the selection by one row.
 *  - `Home` / `End` — jump to first / last.
 *  - `PageUp` / `PageDown`, `Ctrl+U` / `Ctrl+D` — half-page jumps.
 *  - `Enter` — [SessionPickerResult.Selected] with the current row.
 *  - `n` / `N` — [SessionPickerResult.NewSession].
 *  - `Esc`, `Ctrl+C`, `q` — [SessionPickerResult.Cancelled].
 *
 * Vertically scrolls within [visibleRowCap] so a 100-session history doesn't
 * overflow the viewport.
 *
 * @param previews Sessions to display, most-recent-first.
 * @param visibleRowCap Maximum rows to render before scrolling. The runner
 *   (runSessionPicker) typically sets this based on the current terminal
 *   height minus title + footer chrome.
 * @param now Clock for relative-time formatting. Defaults to system clock;
 *   overridable for deterministic tests.
 * @param onComplete Invoked once the user commits to a choice. The picker
 *   does not mutate any state itself — the caller decides how to act on the
 *   result.
 */
class SessionPicker(
    private val previews: List<SessionPreview>,
    private val visibleRowCap: Int = 12,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val onComplete: (SessionPickerResult) -> Unit,
) : Component, Focusable {

    override var focused: Boolean = false

    private var selectedIndex: Int = 0
    private var scrollOffset: Int = 0
    private var completed: Boolean = false

    val selection: SessionPreview?
        get() = previews.getOrNull(selectedIndex)

    // ── Rendering ───────────────────────────────────────────────────────────

    override fun render(width: Int): List<String> {
        if (previews.isEmpty()) return renderEmpty(width)

        val rowsVisible = min(visibleRowCap, previews.size)
        ensureSelectionVisible(rowsVisible)

        val out = ArrayList<String>(rowsVisible)
        val widths = computeColumnWidths(width)
        for (row in 0 until rowsVisible) {
            val index = scrollOffset + row
            if (index >= previews.size) {
                out += padRightToWidth("", width)
                continue
            }
            out += renderRow(previews[index], index == selectedIndex, widths, width)
        }
        return out
    }

    private fun renderEmpty(width: Int): List<String> {
        // Shouldn't happen in practice — CodeCommand only shows the picker
        // when at least one session exists — but be safe.
        val msg = "  no sessions found — press n to start a new one, Esc to cancel"
        val line = "${theme.dim}${truncateToWidth(msg, width)}${Ansi.RESET}"
        return listOf(padRightToWidth(line, width))
    }

    private data class ColumnWidths(val marker: Int, val time: Int, val count: Int)

    /**
     * Compute fixed widths for the marker (▸), relative time, and message
     * count columns. The remainder of the line is for the preview. We
     * budget conservatively so rows never overflow and the preview never
     * shrinks below a readable minimum.
     */
    private fun computeColumnWidths(totalWidth: Int): ColumnWidths {
        // Marker column: "  ▸ " when selected, "    " when not. Width 3.
        val marker = 3
        // Relative-time column: "3d ago" / "yesterday" — pad to 12 for alignment.
        val time = 12
        // Message count: "999 msgs" — pad to 8.
        val count = 8
        return ColumnWidths(marker, time, count)
    }

    private fun renderRow(
        preview: SessionPreview,
        selected: Boolean,
        widths: ColumnWidths,
        totalWidth: Int,
    ): String {
        val markerSymbol = if (selected) "▸ " else "  "
        val markerColor = if (selected) theme.accent else theme.dim
        val marker = "$markerColor$markerSymbol${Ansi.RESET}"

        val relTime = formatRelativeTime(now() - preview.summary.lastModifiedMs)
        val relTimePadded = padRightToWidth(relTime, widths.time)
        val timeColor = if (selected) theme.foreground else theme.dim
        val timeStr = "$timeColor$relTimePadded${Ansi.RESET}"

        val countRaw = "${preview.messageCount} msg${if (preview.messageCount == 1) "" else "s"}"
        val countPadded = padRightToWidth(countRaw, widths.count)
        val countColor = if (selected) theme.foreground else theme.dim
        val countStr = "$countColor$countPadded${Ansi.RESET}"

        // Preview occupies whatever's left after the fixed columns + a single
        // trailing space. Truncate with an ellipsis when it doesn't fit.
        val previewWidth = max(1, totalWidth - widths.marker - widths.time - widths.count - 1)
        val previewRaw = preview.firstUserMessage
            ?.replace('\n', ' ')
            ?.trim()
            ?.ifBlank { null }
            ?: "(empty session)"
        val previewTruncated = truncateToWidth(previewRaw, previewWidth)
        val previewColor = if (selected) theme.foreground else theme.dim
        val previewStr = "$previewColor$previewTruncated${Ansi.RESET}"

        // Compose the line and hard-pad the visible width out to totalWidth
        // so the inverse-video background (when selected) fills the whole row.
        val body = "$marker$timeStr$countStr $previewStr"
        val line = if (selected) {
            "${Ansi.INVERSE}$body${Ansi.RESET}"
        } else {
            body
        }
        // padRightToWidth is ANSI-aware and handles the trailing gap.
        return padRightToWidth(line, totalWidth)
    }

    // ── Input ───────────────────────────────────────────────────────────────

    override fun handleInput(key: KeyboardEvent): Boolean {
        if (completed) return false

        // Named keys first (arrows, Home/End, PageUp/PageDown, Delete).
        when (key.codepoint) {
            KeyboardEvent.Up -> { moveBy(-1); return true }
            KeyboardEvent.Down -> { moveBy(+1); return true }
            KeyboardEvent.Home -> { jumpTo(0); return true }
            KeyboardEvent.End -> { jumpTo(previews.lastIndex); return true }
            KeyboardEvent.PageUp -> { moveBy(-visibleRowCap / 2); return true }
            KeyboardEvent.PageDown -> { moveBy(+visibleRowCap / 2); return true }
        }

        // Enter = confirm.
        if (key.matches(13)) {
            val choice = previews.getOrNull(selectedIndex) ?: return true
            complete(SessionPickerResult.Selected(choice.summary))
            return true
        }

        // Vim-style nav.
        if (key.matches('k')) { moveBy(-1); return true }
        if (key.matches('j')) { moveBy(+1); return true }

        // Half-page jumps.
        if (key.matches('u', ctrl = true)) { moveBy(-visibleRowCap / 2); return true }
        if (key.matches('d', ctrl = true)) { moveBy(+visibleRowCap / 2); return true }

        // New session shortcut.
        if (key.matches('n') || key.matches('N')) {
            complete(SessionPickerResult.NewSession)
            return true
        }

        // Cancel — Esc (codepoint 27), Ctrl+C, or 'q'.
        if (key.codepoint == 27 || key.matches('c', ctrl = true) || key.matches('q')) {
            complete(SessionPickerResult.Cancelled)
            return true
        }

        // Swallow everything else while focused so stray keys don't leak to
        // a parent handler.
        return true
    }

    private fun moveBy(delta: Int) {
        if (previews.isEmpty()) return
        jumpTo(selectedIndex + delta)
    }

    private fun jumpTo(index: Int) {
        if (previews.isEmpty()) return
        selectedIndex = index.coerceIn(0, previews.lastIndex)
    }

    private fun ensureSelectionVisible(rowsVisible: Int) {
        if (rowsVisible <= 0) return
        if (selectedIndex < scrollOffset) {
            scrollOffset = selectedIndex
        } else if (selectedIndex >= scrollOffset + rowsVisible) {
            scrollOffset = selectedIndex - rowsVisible + 1
        }
        scrollOffset = scrollOffset.coerceIn(0, max(0, previews.size - rowsVisible))
    }

    private fun complete(result: SessionPickerResult) {
        if (completed) return
        completed = true
        onComplete(result)
    }

    companion object {
        /**
         * Format elapsed [millis] as a short relative string: "2m ago",
         * "3h ago", "yesterday", "5d ago", "3w ago", "2mo ago". Matches the
         * compact style of Git log and GitHub.
         */
        internal fun formatRelativeTime(millis: Long): String {
            val abs = if (millis < 0) 0 else millis
            val seconds = abs / 1_000
            if (seconds < 45) return "just now"
            val minutes = seconds / 60
            if (minutes < 60) return "${minutes}m ago"
            val hours = minutes / 60
            if (hours < 24) return "${hours}h ago"
            if (hours < 48) return "yesterday"
            val days = hours / 24
            if (days < 7) return "${days}d ago"
            val weeks = days / 7
            if (weeks < 5) return "${weeks}w ago"
            val months = days / 30
            if (months < 12) return "${months}mo ago"
            val years = days / 365
            return "${years}y ago"
        }
    }
}
