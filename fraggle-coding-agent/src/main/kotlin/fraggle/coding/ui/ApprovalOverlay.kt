package fraggle.coding.ui

import com.jakewharton.mosaic.terminal.KeyboardEvent
import fraggle.tui.core.Component
import fraggle.tui.core.Focusable
import fraggle.tui.input.matches
import fraggle.tui.layout.Box
import fraggle.tui.text.Ansi
import fraggle.tui.text.padRightToWidth
import fraggle.tui.text.truncateToWidth
import fraggle.tui.text.visibleWidth

/**
 * A pending tool-call awaiting user approval.
 *
 * [argsSummary] is already whitespace-normalized; the overlay truncates it
 * client-side if it overflows the available width.
 */
data class PendingApproval(
    val toolName: String,
    val argsSummary: String,
)

/**
 * Modal overlay asking the user to approve a pending tool call.
 *
 * Wrapped in a [fraggle.tui.layout.Box] titled "Tool approval required":
 * ```
 * ╭─ Tool approval required ─────────╮
 * │  Tool: {name}                    │
 * │  Args: {args}                    │
 * │                                  │
 * │  [y] approve   [n] deny   [Esc]  │
 * ╰──────────────────────────────────╯
 * ```
 *
 * Colors:
 *  - `Tool: ` / `Args: ` prefixes use [fraggle.tui.theme.Theme.dim].
 *  - The tool name is [fraggle.tui.theme.Theme.toolCall].
 *  - The args body is [fraggle.tui.theme.Theme.dim].
 *  - Letter keys (`y`, `n`, `Esc`) are [fraggle.tui.theme.Theme.accent]; the
 *    descriptive words are [fraggle.tui.theme.Theme.dim].
 *
 * This component renders *as* a [Box] so it slots naturally into the runtime's
 * overlay compositor — a caller that wants a dedicated overlay anchor just
 * positions this component.
 */
class ApprovalOverlay(
    approval: PendingApproval,
    private val onApprove: () -> Unit = {},
    private val onDeny: () -> Unit = {},
) : Component, Focusable {
    override var focused: Boolean = false

    private var approval: PendingApproval = approval
    private val inner = ApprovalBody()
    private val box = Box(
        child = inner,
        title = "Tool approval required",
        style = Box.BorderStyle.ROUNDED,
        paddingX = 1,
        paddingY = 0,
    )

    init {
        inner.approval = approval
    }

    fun setApproval(approval: PendingApproval) {
        this.approval = approval
        inner.approval = approval
    }

    override fun render(width: Int): List<String> = box.render(width)

    /**
     * Handle y/n/Esc keys. When focused, this component is modal — any other
     * key is swallowed so it can't leak into the editor sitting underneath.
     */
    override fun handleInput(key: KeyboardEvent): Boolean {
        if (key.matches('y') || key.matches('Y')) {
            onApprove()
            return true
        }
        if (key.matches('n') || key.matches('N')) {
            onDeny()
            return true
        }
        // Escape (codepoint 27).
        if (key.matches(27)) {
            onDeny()
            return true
        }
        // Ctrl+C also denies while overlay is up, for consistency with the
        // existing Compose implementation.
        if (key.matches('c', ctrl = true)) {
            onDeny()
            return true
        }
        // Swallow everything else — modal overlay must not leak keys through.
        return true
    }

    /**
     * The inner body laid out as four lines: tool, args, blank, key legend.
     * Kept as a private component so [Box] can manage the border and padding
     * while the body owns its own width contract.
     */
    private class ApprovalBody : Component {
        var approval: PendingApproval = PendingApproval("", "")

        override fun render(width: Int): List<String> {
            if (width <= 0) return emptyList()
            val theme = codingTheme

            val toolLine = buildString {
                append(theme.dim).append("Tool: ").append(Ansi.RESET)
                append(theme.toolCall).append(approval.toolName).append(Ansi.RESET)
            }
            val toolLinePadded = padRightToWidth(toolLine, width)

            val argsHeader = "Args: "
            val argsHeaderWidth = visibleWidth(argsHeader)
            val argsRoom = (width - argsHeaderWidth).coerceAtLeast(0)
            val argsBody = if (visibleWidth(approval.argsSummary) <= argsRoom) {
                approval.argsSummary
            } else {
                truncateToWidth(approval.argsSummary, argsRoom)
            }
            val argsLine = buildString {
                append(theme.dim).append(argsHeader).append(Ansi.RESET)
                append(theme.dim).append(argsBody).append(Ansi.RESET)
            }
            val argsLinePadded = padRightToWidth(argsLine, width)

            val blankLine = padRightToWidth("", width)

            val legend = buildString {
                append(theme.accent).append("[y]").append(Ansi.RESET)
                append(theme.dim).append(" approve   ").append(Ansi.RESET)
                append(theme.accent).append("[n]").append(Ansi.RESET)
                append(theme.dim).append(" deny   ").append(Ansi.RESET)
                append(theme.accent).append("[Esc]").append(Ansi.RESET)
                append(theme.dim).append(" deny").append(Ansi.RESET)
            }
            val legendPadded = padRightToWidth(legend, width)

            return listOf(toolLinePadded, argsLinePadded, blankLine, legendPadded)
        }
    }
}
