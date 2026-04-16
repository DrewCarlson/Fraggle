package fraggle.coding.ui

import fraggle.tui.core.Component
import fraggle.tui.text.Ansi
import fraggle.tui.text.padRightToWidth
import fraggle.tui.ui.Markdown

/**
 * In-flight assistant turn shown in the dynamic (redrawable) zone.
 *
 * The coding-agent splits its UI into static scrollback and a tiny dynamic
 * frame. While a turn is streaming, this component represents it — once the
 * turn finishes, the orchestrator flushes a completed [AssistantMessage] into
 * the scrollback and removes this component.
 *
 * Behavior:
 *  - Empty text → `◆ …` on a single line (a "thinking" placeholder).
 *  - Non-empty text → same layout as [AssistantMessage] without tool calls.
 *
 * No trailing blank line — the dynamic frame always sits just above the editor
 * and gets its own spacer there.
 */
class StreamingMessage(text: String = "") : Component {
    private var text: String = text
    private val markdown = Markdown(text, fallbackColor = null)

    fun setText(text: String) {
        if (this.text == text) return
        this.text = text
        markdown.setText(text)
    }

    override fun invalidate() {
        markdown.invalidate()
    }

    override fun render(width: Int): List<String> {
        if (width <= 0) return emptyList()
        val theme = codingTheme

        if (text.isEmpty()) {
            val line = "${theme.accent}◆${Ansi.RESET} ${theme.dim}…${Ansi.RESET}"
            return listOf(padRightToWidth(line, width))
        }

        val bodyWidth = (width - 2).coerceAtLeast(1)
        val bodyLines = markdown.render(bodyWidth).ifEmpty { listOf("") }

        val result = ArrayList<String>(bodyLines.size)
        for ((index, line) in bodyLines.withIndex()) {
            val prefix = if (index == 0) "${theme.accent}◆${Ansi.RESET} " else "  "
            result += padRightToWidth("$prefix$line", width)
        }
        return result
    }
}
