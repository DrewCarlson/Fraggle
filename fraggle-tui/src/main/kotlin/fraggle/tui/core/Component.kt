package fraggle.tui.core

import com.jakewharton.mosaic.terminal.KeyboardEvent

/**
 * A TUI component. Every visible element in a fraggle-tui application implements
 * this interface.
 *
 * The contract is deliberately minimal:
 *
 * - [render] returns the component's content as a list of already-ANSI-styled lines,
 *   each guaranteed to be at most [width] visible cells wide.
 * - [handleInput] receives keyboard events only when this component has focus.
 *   Returns `true` when it consumed the key, `false` to let the runtime dispatch
 *   elsewhere (e.g. global hotkeys).
 * - [invalidate] drops any cached rendering state; called on resize, theme change,
 *   or explicit refresh.
 *
 * ## Width contract
 *
 * Every line in the return value of [render] MUST have a [fraggle.tui.text.visibleWidth]
 * of at most [width]. Violating this contract causes the differential renderer to
 * drift: physical terminal rows no longer match the component's row count, and
 * subsequent cursor-up-and-rewrite math produces ghost content in the scrollback.
 *
 * The runtime enforces this invariant — a line wider than [width] crashes the TUI
 * with a clear error message pointing at the offending component. This is intentional:
 * width overflow is the single largest source of TUI instability, and catching it
 * at the source is the only reliable fix.
 */
interface Component {
    /**
     * Render this component at the given viewport [width].
     *
     * @param width Number of visible cells available. Must be >= 1. Each returned
     *   line must have a visible width <= [width].
     * @return Lines of ANSI-styled text. May be empty. May contain embedded ANSI
     *   escape sequences for colors, underlines, hyperlinks, etc.
     */
    fun render(width: Int): List<String>

    /**
     * Handle a keyboard event delivered by the runtime. Only the focused
     * component receives events. Default implementation consumes nothing.
     *
     * @return `true` if the event was consumed, `false` to let it fall through
     *   to the runtime's global handler (for e.g. exit hotkeys).
     */
    fun handleInput(key: KeyboardEvent): Boolean = false

    /**
     * Invalidate any cached rendering state. Default is a no-op.
     *
     * Called when: terminal width changes (for width-dependent caches), theme
     * changes, or an explicit refresh is requested. Components that pre-compute
     * wrapped-line caches or style-calculated strings should drop them here so
     * the next [render] call recomputes from scratch.
     */
    fun invalidate() {}
}

/**
 * Optional mixin for components that can receive focus and display a hardware cursor.
 *
 * The runtime tracks the currently-focused component and routes key events to it.
 * When a focusable component is drawn, it may embed [CURSOR_MARKER] in its output
 * at the desired caret position; the renderer finds the marker, strips it, and
 * positions the terminal's hardware cursor there. This is what makes IME candidate
 * windows appear in the right place in the editor.
 */
interface Focusable {
    /** Updated by the runtime as focus changes. */
    var focused: Boolean
}

/**
 * Zero-width Application Program Command escape sequence. Emit this at the caret
 * position inside a [Focusable] component's render output to tell the renderer
 * where to place the terminal's hardware cursor.
 *
 * The marker is stripped from the final output before writing to the terminal.
 */
const val CURSOR_MARKER: String = "\u001b_fraggle:c\u0007"
