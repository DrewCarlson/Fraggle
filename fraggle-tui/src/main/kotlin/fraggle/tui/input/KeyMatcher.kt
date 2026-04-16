package fraggle.tui.input

import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.KeyboardEvent.Companion.EventTypePress
import com.jakewharton.mosaic.terminal.KeyboardEvent.Companion.EventTypeRepeat

/**
 * Matching helpers around Mosaic's [KeyboardEvent].
 *
 * Mosaic parses terminal input into structured [KeyboardEvent] instances —
 * codepoint, modifiers, and (when the Kitty keyboard protocol is active)
 * a press/repeat/release type plus shifted and base-layout codepoints. The
 * helpers here give components a compact DSL for the common match cases:
 *
 * ```
 * if (key.matches('c', ctrl = true)) { ... }       // Ctrl+C
 * if (key.matches(KeyboardEvent.Up)) { ... }        // Arrow up
 * if (key.matches(Enter, shift = true)) { ... }     // Shift+Enter
 * ```
 *
 * By default only PRESS and REPEAT events match. Components that need to see
 * key-release events (rare — mostly Kitty keyboard protocol features) should
 * check [isRelease] directly.
 */

/** True when the event is a physical key release (Kitty keyboard protocol only). */
val KeyboardEvent.isRelease: Boolean
    get() = eventType == KeyboardEvent.EventTypeRelease

/** True for initial press or subsequent auto-repeats. Excludes releases. */
val KeyboardEvent.isPressOrRepeat: Boolean
    get() = eventType == EventTypePress || eventType == EventTypeRepeat

/**
 * Match a single character [codepoint] with optional required modifier state.
 *
 * When a modifier parameter is `null` (default), its state is ignored.
 * When `true`/`false`, that modifier must match exactly.
 *
 * Only PRESS and REPEAT events match; release events fall through.
 */
fun KeyboardEvent.matches(
    codepoint: Int,
    ctrl: Boolean? = null,
    alt: Boolean? = null,
    shift: Boolean? = null,
    meta: Boolean? = null,
): Boolean {
    if (!isPressOrRepeat) return false
    if (this.codepoint != codepoint) return false
    if (ctrl != null && this.ctrl != ctrl) return false
    if (alt != null && this.alt != alt) return false
    if (shift != null && this.shift != shift) return false
    if (meta != null && this.meta != meta) return false
    return true
}

/** Shorthand: match an ASCII [char]. */
fun KeyboardEvent.matches(
    char: Char,
    ctrl: Boolean? = null,
    alt: Boolean? = null,
    shift: Boolean? = null,
    meta: Boolean? = null,
): Boolean = matches(char.code, ctrl, alt, shift, meta)

/** True when no modifiers are held (except Shift; see [isPrintable]). */
val KeyboardEvent.isPlain: Boolean
    get() = !ctrl && !alt && !meta && !`super` && !hyper

/**
 * True when this event represents a printable, modifier-free (or Shift-only)
 * character that should be inserted into a text buffer.
 *
 * Excludes: modifier combos, control codes (< 0x20), DEL (0x7f), and the
 * private-use codepoints Mosaic uses for named keys (arrows, F-keys, Home/End,
 * etc. — anything in the Kitty-defined range 57344-57500 or so).
 */
val KeyboardEvent.isPrintable: Boolean
    get() {
        if (!isPressOrRepeat) return false
        if (ctrl || alt || meta || `super` || hyper) return false
        val cp = codepoint
        if (cp < 0x20 || cp == 0x7f) return false
        // Kitty private-use range for named keys
        if (cp in 57344..57500) return false
        return true
    }
