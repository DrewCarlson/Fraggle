package fraggle.coding.ui

import fraggle.tui.core.Component
import fraggle.tui.text.Ansi
import fraggle.tui.text.padRightToWidth
import fraggle.tui.text.truncateToWidth
import fraggle.tui.text.visibleWidth

/**
 * Session metadata shown in the banner above the scrollback.
 *
 * Constructed by the CLI command at startup; the banner doesn't know how to
 * derive any of it — the orchestrator feeds it in.
 */
data class HeaderInfo(
    val model: String,
    val contextFileCount: Int,
)

/**
 * Two-line banner rendered once at the top of the session.
 *
 * ```
 * ── fraggle code ──────────────────────────────────────────
 *   ?: /hotkeys  •  N context file(s)  •  {model}
 * ```
 *
 * Line 1 is the session title padded out with horizontal rule glyphs to the
 * full viewport width, colored with [fraggle.tui.theme.Theme.accent].
 *
 * Line 2 is a hint row: "?: /hotkeys" is the universal help-key hint, then the
 * context-file count (pluralized), then the model name. Separators use
 * [fraggle.tui.theme.Theme.veryDim] to sink below the text. If the model name
 * would overflow the line, it is truncated with an ellipsis.
 *
 * Both lines are padded to exactly [width] cells so the runtime's diff
 * renderer sees complete rows.
 */
class HeaderBanner(info: HeaderInfo) : Component {
    private var info: HeaderInfo = info

    fun setInfo(info: HeaderInfo) {
        this.info = info
    }

    override fun render(width: Int): List<String> {
        if (width <= 0) return emptyList()
        val theme = codingTheme
        return listOf(
            renderTitleLine(width, theme.accent),
            renderHintLine(width, theme.dim, theme.veryDim),
        )
    }

    private fun renderTitleLine(width: Int, accent: String): String {
        val prefix = "── fraggle code "
        val prefixWidth = visibleWidth(prefix)
        if (prefixWidth >= width) {
            // Terminal too narrow to even fit the label — truncate hard.
            val trimmed = truncateToWidth(prefix, width)
            return padRightToWidth("$accent$trimmed${Ansi.RESET}", width)
        }
        val fill = "─".repeat(width - prefixWidth)
        return "$accent$prefix$fill${Ansi.RESET}"
    }

    private fun renderHintLine(width: Int, dim: String, veryDim: String): String {
        val parts = mutableListOf<String>()
        parts += "?: /hotkeys"
        if (info.contextFileCount > 0) {
            val plural = if (info.contextFileCount == 1) "" else "s"
            parts += "${info.contextFileCount} context file$plural"
        }
        if (info.model.isNotBlank()) {
            parts += info.model
        }

        // Separator renders as "  •  " — 5 cells — between adjacent parts.
        // We treat the leading "  " indent as 2 cells the whole line starts with.
        val contentWidth = (width - 2).coerceAtLeast(1)

        val sb = StringBuilder()
        var cells = 0
        for ((index, part) in parts.withIndex()) {
            val isLast = index == parts.lastIndex
            val separator = if (index == 0) "" else "  •  "
            val separatorCells = visibleWidth(separator)

            // Reserve room: we need at least 1 visible cell for this part's content.
            val remaining = contentWidth - cells - separatorCells
            if (remaining < 1) break

            // If this is the last part (typically the model) and its full width
            // doesn't fit, truncate it with an ellipsis.
            val partWidth = visibleWidth(part)
            val rendered = if (isLast && partWidth > remaining) {
                truncateToWidth(part, remaining)
            } else if (partWidth > remaining) {
                // A middle part doesn't fit — drop it and everything after.
                break
            } else {
                part
            }

            if (separator.isNotEmpty()) {
                sb.append(veryDim).append(separator).append(Ansi.RESET)
                cells += separatorCells
            }
            sb.append(dim).append(rendered).append(Ansi.RESET)
            cells += visibleWidth(rendered)
        }

        val body = "  " + sb.toString()
        return padRightToWidth(body, width)
    }
}
