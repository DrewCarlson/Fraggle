package fraggle.coding.ui

import fraggle.tui.core.Component
import fraggle.tui.text.Ansi
import fraggle.tui.text.padRightToWidth
import fraggle.tui.text.wordWrap

/**
 * A completed user turn in the scrollback.
 *
 * Renders as:
 * ```
 * » {first wrapped line}
 *   {continuation line}
 *   {continuation line}
 *
 * ```
 *
 * The `»` marker uses [fraggle.tui.theme.Theme.userText] and the message body
 * uses [fraggle.tui.theme.Theme.foreground]. Word-wrapping keeps words intact
 * where possible; continuation lines indent two spaces to line up under the
 * first character of the body.
 *
 * A trailing blank line visually separates this turn from the next message.
 */
class UserMessage(text: String) : Component {
    private var text: String = text

    fun setText(text: String) {
        this.text = text
    }

    override fun render(width: Int): List<String> {
        if (width <= 0) return emptyList()
        val theme = codingTheme
        val marker = "» "
        val markerWidth = 2
        val contentWidth = (width - markerWidth).coerceAtLeast(1)

        val wrapped = if (text.isEmpty()) listOf("") else wordWrap(text, contentWidth)

        val result = ArrayList<String>(wrapped.size + 1)
        for ((index, line) in wrapped.withIndex()) {
            val prefix = if (index == 0) "${theme.userText}»${Ansi.RESET} " else "  "
            val body = if (line.isEmpty()) "" else "${theme.foreground}$line${Ansi.RESET}"
            result += padRightToWidth("$prefix$body", width)
        }
        // Trailing blank separator line.
        result += padRightToWidth("", width)
        return result
    }
}
