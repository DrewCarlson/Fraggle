@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package fraggle.coding.ui

import com.jakewharton.mosaic.tty.Tty
import com.jakewharton.mosaic.tty.terminal.asTerminalIn
import fraggle.coding.session.SessionPreview
import fraggle.tui.core.Component
import fraggle.tui.core.TUI
import fraggle.tui.core.TtyOutput
import fraggle.tui.text.Ansi
import fraggle.tui.text.padRightToWidth
import fraggle.tui.text.truncateToWidth
import fraggle.tui.theme.theme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * Blocking entry point that shows the session-picker UI and returns the
 * user's choice.
 *
 * Manages its own [Tty] lifecycle — binds the terminal, shows the picker,
 * tears down. The caller can then re-bind the Tty for the main coding-agent
 * TUI without collision; `Tty.close()` releases the platform binding and a
 * subsequent `Tty.tryBind()` succeeds.
 *
 * Returns [SessionPickerResult]:
 *  - [SessionPickerResult.Selected] — run with this session.
 *  - [SessionPickerResult.NewSession] — user pressed `n`; create a fresh one.
 *  - [SessionPickerResult.Cancelled] — user pressed Esc / Ctrl+C / q. The
 *    caller should typically exit the process cleanly.
 *
 * If [previews] is empty the picker never actually displays — we return
 * [SessionPickerResult.NewSession] immediately so the caller falls through
 * to "start fresh" without showing an empty screen.
 */
fun runSessionPicker(previews: List<SessionPreview>): SessionPickerResult {
    if (previews.isEmpty()) return SessionPickerResult.NewSession

    val tty = Tty.tryBind() ?: error("no TTY available for session picker")
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val result = CompletableDeferred<SessionPickerResult>()

    var tui: TUI? = null
    try {
        runBlocking {
            val terminal = tty.asTerminalIn(scope)
            val output = TtyOutput(tty)
            val size = output.currentSize() ?: terminal.state.size.value
            // Leave a few rows for title + footer chrome; cap the list height
            // so a huge session count still fits. -6 = 2 title rows + 2 footer rows + slack.
            val visibleRowCap = (size.rows - 6).coerceAtLeast(3)

            val picker = SessionPicker(
                previews = previews,
                visibleRowCap = visibleRowCap,
                onComplete = { result.complete(it) },
            )

            tui = TUI(terminal, output, scope).also { t ->
                t.addChild(PickerTitle())
                t.addChild(picker)
                t.addChild(PickerFooter())
                t.setFocus(picker)
                t.start()
            }

            result.await()
        }
    } finally {
        runCatching { tui?.stop() }
        runCatching { scope.cancel() }
        runCatching { tty.close() }
    }

    return result.getCompleted()
}

/** Single-line picker title — renders above the list. */
private class PickerTitle : Component {
    override fun render(width: Int): List<String> {
        val title = "select a session to resume"
        val accentLine = "${theme.accent}── $title ${"─".repeat((width - title.length - 4).coerceAtLeast(0))}${Ansi.RESET}"
        return listOf(
            padRightToWidth(truncateToWidth(accentLine, width), width),
            padRightToWidth("", width),
        )
    }
}

/** Two-line picker footer with keyboard hints. */
private class PickerFooter : Component {
    override fun render(width: Int): List<String> {
        val hintLine = buildString {
            append(theme.veryDim)
            append("  ")
            append(theme.accent).append("↑/↓").append(theme.veryDim).append(" navigate   ")
            append(theme.accent).append("Enter").append(theme.veryDim).append(" open   ")
            append(theme.accent).append("n").append(theme.veryDim).append(" new session   ")
            append(theme.accent).append("Esc").append(theme.veryDim).append(" cancel")
            append(Ansi.RESET)
        }
        return listOf(
            padRightToWidth("", width),
            padRightToWidth(truncateToWidth(hintLine, width), width),
        )
    }
}
