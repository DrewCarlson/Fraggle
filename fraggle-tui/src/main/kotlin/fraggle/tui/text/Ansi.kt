package fraggle.tui.text

/**
 * ANSI/VT escape sequence constants and small helpers.
 *
 * All sequences here are plain [String]s — suitable to splice into render output
 * or [fraggle.tui.core.Component.render] return values. For complex sequences
 * (cursor positioning with parameters, color palette entries) use the helpers
 * below rather than string concatenating at call sites.
 */
object Ansi {
    // ── Sequence introducers ────────────────────────────────────────────────
    const val ESC: String = "\u001b"
    const val CSI: String = "\u001b["   // Control Sequence Introducer
    const val OSC: String = "\u001b]"   // Operating System Command

    // ── SGR (Select Graphic Rendition) — styles + colors ───────────────────
    /** Reset all attributes and terminate active OSC 8 hyperlinks. */
    const val RESET: String = "\u001b[0m\u001b]8;;\u0007"

    const val BOLD: String = "\u001b[1m"
    const val DIM: String = "\u001b[2m"
    const val ITALIC: String = "\u001b[3m"
    const val UNDERLINE: String = "\u001b[4m"
    const val INVERSE: String = "\u001b[7m"
    const val STRIKETHROUGH: String = "\u001b[9m"

    // ── Cursor movement ─────────────────────────────────────────────────────
    /** Move cursor to row 1 col 1 (home). */
    const val CURSOR_HOME: String = "\u001b[H"

    /** Hide the hardware cursor. */
    const val CURSOR_HIDE: String = "\u001b[?25l"

    /** Show the hardware cursor. */
    const val CURSOR_SHOW: String = "\u001b[?25h"

    // ── Erase ───────────────────────────────────────────────────────────────
    /** Erase from cursor to end of line. */
    const val CLEAR_LINE: String = "\u001b[K"

    /** Erase from cursor to end of display. */
    const val CLEAR_DISPLAY_BELOW: String = "\u001b[J"

    /** Erase entire visible display. */
    const val CLEAR_DISPLAY: String = "\u001b[2J"

    /**
     * Erase scrollback (xterm extension). **The TUI runtime does not emit this
     * by default** — see the class docs on [fraggle.tui.core.TUI] for why.
     */
    const val CLEAR_SCROLLBACK: String = "\u001b[3J"

    // ── Synchronized output (DEC mode 2026) ─────────────────────────────────
    const val SYNC_BEGIN: String = "\u001b[?2026h"
    const val SYNC_END: String = "\u001b[?2026l"

    // ── Bracketed paste (DEC mode 2004) ─────────────────────────────────────
    const val BRACKETED_PASTE_ON: String = "\u001b[?2004h"
    const val BRACKETED_PASTE_OFF: String = "\u001b[?2004l"

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Move cursor up [n] rows and to column 1 (CSI `n` F). */
    fun cursorUp(n: Int): String = "\u001b[${n}F"

    /** Move cursor down [n] rows and to column 1 (CSI `n` E). */
    fun cursorDown(n: Int): String = "\u001b[${n}E"

    /** Move cursor to absolute row [row], column [col]. Both 1-indexed. */
    fun cursorTo(row: Int, col: Int): String = "\u001b[${row};${col}H"

    /** 8-bit foreground color (CSI 38;5;[n] m). */
    fun fg256(n: Int): String = "\u001b[38;5;${n}m"

    /** 24-bit foreground color (CSI 38;2;r;g;b m). */
    fun fgRgb(r: Int, g: Int, b: Int): String = "\u001b[38;2;${r};${g};${b}m"

    /** 24-bit background color. */
    fun bgRgb(r: Int, g: Int, b: Int): String = "\u001b[48;2;${r};${g};${b}m"

    /** OSC 8 hyperlink. Wrap the visible text separately. */
    fun hyperlinkStart(url: String): String = "\u001b]8;;$url\u0007"
    const val HYPERLINK_END: String = "\u001b]8;;\u0007"

    /** Wrap [text] in [style] … [RESET]. Convenience. */
    fun style(text: String, style: String): String = "$style$text$RESET"
}
