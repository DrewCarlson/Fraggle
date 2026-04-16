package fraggle.coding.ui

import fraggle.tui.core.Component
import fraggle.tui.text.Ansi
import fraggle.tui.text.padRightToWidth
import fraggle.tui.text.visibleWidth
import fraggle.tui.theme.Theme
import java.nio.file.Path

/**
 * Footer status pill. Each entry owns its label plus a selector that picks a
 * color from the current theme — this avoids hard-coding a color at enum
 * declaration time (themes can be swapped at runtime).
 */
enum class FooterStatus(val label: String, val colorSelector: (Theme) -> String) {
    IDLE("idle", { it.dim }),
    BUSY("thinking...", { it.accent }),
    COMPACTING("compacting...", { it.warning }),
    AWAITING_APPROVAL("awaiting approval", { it.warning }),
    ERROR("error", { it.error }),
}

/**
 * Data bundle describing the bottom-of-screen status bar. Produced by the
 * orchestrator from the current agent/session state.
 *
 * @param cwd the working directory, shortened to a basename + parent when the
 *   full absolute path would overflow the left half.
 * @param sessionId stable identifier of the JSONL session file — only the
 *   first 8 chars are shown to keep the line compact.
 * @param usedTokens cumulative tokens consumed across the session.
 * @param contextRatio fraction of the context window in use; 0.0 means
 *   "unknown" (common early in a session). Displayed as a percentage and
 *   color-coded: dim at < 70%, warning at 70-84%, error at >= 85%.
 * @param status the dynamic-zone state (idle, thinking, compacting, ...).
 * @param supervisionLabel descriptive text for the current permission mode
 *   (e.g. "ask", "always"). Rendered on row 3 when non-empty.
 * @param confirmExit when true, row 3 becomes a loud warning instead of the
 *   supervision label — the user pressed Esc / Ctrl+C once and we want them
 *   to confirm before killing the app.
 */
data class FooterInfo(
    val cwd: Path,
    val sessionId: String,
    val usedTokens: Int,
    val contextRatio: Double,
    val status: FooterStatus,
    val supervisionLabel: String = "",
    val confirmExit: Boolean = false,
)

/**
 * Bottom-of-screen status bar. Always 2 lines; a 3rd line is added when
 * [FooterInfo.supervisionLabel] is non-empty or [FooterInfo.confirmExit] is true.
 *
 * Row 1: full-width divider, colored with [Theme.divider].
 * Row 2: left-aligned short-cwd; right-aligned session id, token count,
 *        optional context percentage, and status pill. The middle is
 *        space-filled so the total visible width is exactly `width`.
 * Row 3: supervision label in [Theme.dim], OR — when [confirmExit] — a
 *        warning `⚠ press Esc or Ctrl+C again to exit` in [Theme.warning].
 */
class Footer(info: FooterInfo) : Component {
    private var info: FooterInfo = info

    fun setInfo(info: FooterInfo) {
        this.info = info
    }

    override fun render(width: Int): List<String> {
        if (width <= 0) return emptyList()
        val theme = codingTheme

        val lines = mutableListOf<String>()
        lines += renderDivider(width, theme)
        lines += renderStatusRow(width, theme)

        if (info.confirmExit) {
            lines += renderConfirmExit(width, theme)
        } else if (info.supervisionLabel.isNotBlank()) {
            lines += renderSupervision(width, theme)
        }

        return lines
    }

    private fun renderDivider(width: Int, theme: Theme): String {
        val fill = "─".repeat(width)
        return "${theme.divider}$fill${Ansi.RESET}"
    }

    private fun renderStatusRow(width: Int, theme: Theme): String {
        val left = renderLeft(theme)
        val right = renderRight(theme)

        val leftWidth = visibleWidth(left)
        val rightWidth = visibleWidth(right)

        // Happy path: both sides fit with room to spare.
        if (leftWidth + rightWidth < width) {
            val gap = width - leftWidth - rightWidth
            return padRightToWidth(left + " ".repeat(gap) + right, width)
        }

        // Tight path: right cluster has priority (status + tokens). Truncate the
        // right segment to width if needed, then drop the left entirely.
        if (rightWidth >= width) {
            val clippedRight = fraggle.tui.text.truncateToWidth(right, width)
            return padRightToWidth(clippedRight, width)
        }

        // Right fits on its own but together-with-left does not: keep right and
        // pad left with spaces to fill the gap.
        val room = (width - rightWidth).coerceAtLeast(0)
        val leftClipped = if (leftWidth <= room) {
            left + " ".repeat(room - leftWidth)
        } else {
            // Left doesn't fit either; replace with blank padding.
            " ".repeat(room)
        }
        return padRightToWidth(leftClipped + right, width)
    }

    private fun renderLeft(theme: Theme): String {
        val shortCwd = shortPath(info.cwd, thresholdWidth = null)
        // Single-space indent mirrors the existing Footer for visual breathing room.
        return " ${theme.dim}$shortCwd${Ansi.RESET}"
    }

    private fun renderRight(theme: Theme): String {
        val sb = StringBuilder()
        sb.append(theme.dim).append(info.sessionId.take(8)).append(Ansi.RESET)
        sb.append("  ")
        sb.append(theme.dim).append("${info.usedTokens} tok").append(Ansi.RESET)

        if (info.contextRatio > 0.0) {
            sb.append("  ")
            val pct = (info.contextRatio * 100).toInt()
            val pctColor = when {
                info.contextRatio >= 0.85 -> theme.error
                info.contextRatio >= 0.70 -> theme.warning
                else -> theme.dim
            }
            sb.append(pctColor).append("[${pct}% ctx]").append(Ansi.RESET)
        }

        sb.append("  ")
        val statusColor = info.status.colorSelector(theme)
        sb.append(statusColor).append(info.status.label).append(Ansi.RESET)
        sb.append(' ')
        return sb.toString()
    }

    private fun renderSupervision(width: Int, theme: Theme): String {
        val line = " ${theme.dim}supervision: ${info.supervisionLabel}${Ansi.RESET}"
        val fit = if (visibleWidth(line) > width) {
            fraggle.tui.text.truncateToWidth(line, width)
        } else {
            line
        }
        return padRightToWidth(fit, width)
    }

    private fun renderConfirmExit(width: Int, theme: Theme): String {
        val line = " ${theme.warning}⚠ press Esc or Ctrl+C again to exit${Ansi.RESET}"
        val fit = if (visibleWidth(line) > width) {
            fraggle.tui.text.truncateToWidth(line, width)
        } else {
            line
        }
        return padRightToWidth(fit, width)
    }

    /**
     * Render a filesystem path compactly for the footer. Shows the last two
     * path components if the full absolute path would be too long.
     *
     * Port of the heuristic from fraggle.coding.tui.Footer.shortPath with a
     * small generalization — if the caller passes a viewport width we use
     * `viewport / 2` as the cutoff; otherwise we use a fixed 40-char cutoff
     * (which matches the pre-fraggle-tui behavior).
     */
    private fun shortPath(path: Path, thresholdWidth: Int?): String {
        val abs = path.toAbsolutePath().toString()
        val threshold = thresholdWidth?.let { (it / 2).coerceAtLeast(10) } ?: 40
        if (abs.length <= threshold) return abs
        val parts = path.toAbsolutePath()
        val count = parts.nameCount
        return if (count >= 2) {
            ".../${parts.getName(count - 2)}/${parts.getName(count - 1)}"
        } else {
            abs.takeLast(threshold)
        }
    }
}
