package fraggle.coding.ui

import fraggle.tui.core.Component
import fraggle.tui.text.Ansi
import fraggle.tui.text.padRightToWidth
import fraggle.tui.text.truncateToWidth
import fraggle.tui.text.visibleWidth

/**
 * A tool-result row displayed below the assistant turn that invoked it.
 *
 * Layout:
 * ```
 *      ← tool_name: {truncated output}
 *
 *      ✗ tool_name: {truncated error text}
 * ```
 *
 * The leading 5 spaces align under the `◆ ` marker + tool-call indent of
 * [AssistantMessage]. When [isError] is `true`, the `✗` marker and the output
 * use [fraggle.tui.theme.Theme.toolError]; otherwise `←` is
 * [fraggle.tui.theme.Theme.toolResult] and the output is colored the same.
 *
 * The tool name is always [fraggle.tui.theme.Theme.toolCall], matching the
 * color used on the matching tool-call line above.
 *
 * The output is collapsed to a single line (newlines replaced with spaces) and
 * truncated to ~200 cells with an ellipsis, so the scrollback stays skimmable
 * even when a tool returns a large buffer.
 *
 * A trailing blank line visually separates this row from the next message.
 */
class ToolExecution(
    private val toolName: String,
    isError: Boolean = false,
    output: String,
) : Component {
    private var isError: Boolean = isError
    private var output: String = output

    fun setOutput(output: String) {
        this.output = output
    }

    fun setIsError(isError: Boolean) {
        this.isError = isError
    }

    override fun render(width: Int): List<String> {
        if (width <= 0) return emptyList()
        val theme = codingTheme
        val resultColor = if (isError) theme.toolError else theme.toolResult
        val marker = if (isError) "✗ " else "← "

        // Leading chrome: 5 spaces + marker (2 cells) + name + ": " (2 cells).
        val lead = "     "
        val leadCells = 5
        val markerCells = 2
        val separatorCells = 2 // ": "

        // Reserve room for lead + marker. The name gets truncated if there isn't
        // room for it alongside the marker; if there's no room at all for the
        // marker, we emit just the padded blank content line.
        val afterLead = (width - leadCells).coerceAtLeast(0)
        if (afterLead < markerCells) {
            return listOf(padRightToWidth("", width), padRightToWidth("", width))
        }

        val nameBudget = (afterLead - markerCells).coerceAtLeast(0)
        val renderedName = if (visibleWidth(toolName) <= nameBudget) {
            toolName
        } else {
            truncateToWidth(toolName, nameBudget)
        }
        val nameCells = visibleWidth(renderedName)

        val outputBudget = (width - leadCells - markerCells - nameCells - separatorCells).coerceAtLeast(0)
        val outputCap = minOf(200, outputBudget)

        val compactedOutput = output.replace(Regex("\\s+"), " ").trim()
        val truncated = if (outputCap > 0) {
            truncateToWidth(compactedOutput, outputCap)
        } else {
            ""
        }

        val sb = StringBuilder()
        sb.append(lead)
        sb.append(resultColor).append(marker).append(Ansi.RESET)
        if (renderedName.isNotEmpty()) {
            sb.append(theme.toolCall).append(renderedName).append(Ansi.RESET)
        }
        // Only include ": " if we had room to fit the full name; when truncated,
        // the ellipsis already signals elision and the separator is pointless.
        val hasSeparator = nameCells > 0 && outputBudget > 0 &&
            nameBudget >= visibleWidth(toolName) // full name fit
        if (hasSeparator) {
            sb.append(theme.veryDim).append(": ").append(Ansi.RESET)
            if (truncated.isNotEmpty()) {
                sb.append(resultColor).append(truncated).append(Ansi.RESET)
            }
        }

        val lines = mutableListOf<String>()
        lines += padRightToWidth(sb.toString(), width)
        lines += padRightToWidth("", width)
        return lines
    }
}
