package fraggle.coding.ui

import fraggle.tui.core.Component
import fraggle.tui.text.Ansi
import fraggle.tui.text.padRightToWidth
import fraggle.tui.text.truncateToWidth
import fraggle.tui.text.wordWrap
import fraggle.tui.ui.Markdown

/**
 * A completed assistant turn in the scrollback, with optional tool-call
 * summary and error line.
 *
 * Layout:
 * ```
 * ◆ {markdown line 1}
 *   {markdown line 2}
 *   └─ tool_name { compact json… }
 *   └─ other_tool {...}
 *   ! error message
 *
 * ```
 *
 * The `◆` diamond is [fraggle.tui.theme.Theme.accent]. The body is markdown-
 * rendered via [fraggle.tui.ui.Markdown]; its first line aligns with the
 * marker, subsequent lines indent 2 spaces.
 *
 * Tool-call lines use a thin Unicode L-elbow (`└─`) in
 * [fraggle.tui.theme.Theme.veryDim], the tool name in
 * [fraggle.tui.theme.Theme.toolCall], and a compacted JSON args snippet in
 * [fraggle.tui.theme.Theme.dim] (whitespace collapsed, truncated to ~80 cells).
 *
 * If [errorMessage] is set (typically: the turn ended with an LLM-side error),
 * a `!` line in [fraggle.tui.theme.Theme.error] follows the tool calls.
 *
 * A trailing blank line visually separates this turn from the next message.
 */
class AssistantMessage(
    text: String,
    toolCalls: List<ToolCallSnippet> = emptyList(),
    errorMessage: String? = null,
) : Component {

    /**
     * One line item in the tool-call summary below the body. [argsJson] is the
     * raw JSON; the renderer collapses whitespace and truncates it on display.
     */
    data class ToolCallSnippet(val name: String, val argsJson: String)

    private var text: String = text
    private var toolCalls: List<ToolCallSnippet> = toolCalls
    private var errorMessage: String? = errorMessage
    private val markdown = Markdown(text, fallbackColor = null)

    fun setText(text: String) {
        if (this.text == text) return
        this.text = text
        markdown.setText(text)
    }

    fun setToolCalls(calls: List<ToolCallSnippet>) {
        this.toolCalls = calls
    }

    fun setErrorMessage(msg: String?) {
        this.errorMessage = msg
    }

    override fun invalidate() {
        markdown.invalidate()
    }

    override fun render(width: Int): List<String> {
        if (width <= 0) return emptyList()
        val theme = codingTheme

        val result = mutableListOf<String>()

        // Body — render markdown at (width - 2) then prefix the first line with
        // "◆ " and indent continuation lines by 2 spaces.
        val bodyWidth = (width - 2).coerceAtLeast(1)
        val bodyLines = if (text.isEmpty()) {
            listOf("")
        } else {
            val lines = markdown.render(bodyWidth)
            if (lines.isEmpty()) listOf("") else lines
        }

        for ((index, line) in bodyLines.withIndex()) {
            val prefix = if (index == 0) "${theme.accent}◆${Ansi.RESET} " else "  "
            result += padRightToWidth("$prefix$line", width)
        }

        // Tool calls — one per line, indented 2 spaces to line up with the body.
        for (call in toolCalls) {
            result += renderToolCall(call, width, theme)
        }

        // Error line — wrap long messages so every line obeys the width contract.
        errorMessage?.let { err ->
            val flat = err.replace('\n', ' ')
            // Chrome eats 4 visible cells: "  ! " on line 0, "    " on continuations.
            val contentWidth = (width - 4).coerceAtLeast(1)
            val wrapped = fraggle.tui.text.wordWrap(flat, contentWidth)
            for ((index, line) in wrapped.withIndex()) {
                val leadGlyph = if (index == 0) "! " else "  "
                val styled = "  ${theme.error}$leadGlyph$line${Ansi.RESET}"
                val final = if (fraggle.tui.text.visibleWidth(styled) > width) {
                    fraggle.tui.text.truncateToWidth(styled, width)
                } else {
                    styled
                }
                result += padRightToWidth(final, width)
            }
        }

        // Trailing blank separator.
        result += padRightToWidth("", width)
        return result
    }

    private fun renderToolCall(call: ToolCallSnippet, width: Int, theme: fraggle.tui.theme.Theme): String {
        // Lead chrome: "  └─ " is 5 cells visible.
        val leadWidth = 5
        val available = (width - leadWidth).coerceAtLeast(1)

        val nameWidth = fraggle.tui.text.visibleWidth(call.name)
        val nameFits = nameWidth <= available
        val renderedName = if (nameFits) call.name else truncateToWidth(call.name, available)
        val consumed = if (nameFits) nameWidth else fraggle.tui.text.visibleWidth(fraggle.tui.text.stripAnsi(renderedName))

        val sb = StringBuilder()
        sb.append("  ")
        sb.append(theme.veryDim).append("└─").append(Ansi.RESET).append(' ')
        sb.append(theme.toolCall).append(renderedName).append(Ansi.RESET)

        if (nameFits) {
            val jsonRoom = (available - consumed - 1).coerceAtLeast(0) // -1 for the separator space
            val compact = compactJson(call.argsJson)
            if (compact.isNotBlank() && compact != "{}" && jsonRoom > 0) {
                val cap = minOf(80, jsonRoom)
                val snippet = truncateToWidth(compact, cap)
                sb.append(' ')
                sb.append(theme.dim).append(snippet).append(Ansi.RESET)
            }
        }

        return padRightToWidth(sb.toString(), width)
    }

    private fun compactJson(json: String): String {
        if (json.isBlank()) return ""
        return json.replace(Regex("\\s+"), " ").trim()
    }
}
