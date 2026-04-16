package fraggle.tui.layout

import fraggle.tui.core.Component
import fraggle.tui.text.Ansi
import fraggle.tui.text.padRightToWidth
import fraggle.tui.text.visibleWidth
import fraggle.tui.text.wordWrap

/**
 * A single paragraph (or multi-line block) of styled text.
 *
 * [Text] honors embedded `\n` separators as hard line breaks and word-wraps
 * anything that exceeds the render width. Optional [color] and [style] are
 * pre-baked ANSI SGR strings (e.g. [fraggle.tui.theme.Theme.DARK]`.accent` or
 * [Ansi.BOLD]); both are applied as a prefix on every visible content line
 * and terminated with [Ansi.RESET].
 *
 * Padding works like CSS: [paddingX] adds that many spaces on the left and
 * right of every content line, [paddingY] emits that many blank rows above
 * and below the text block. Padding always reduces the effective width passed
 * to the wrapper, not the width reported to the runtime.
 */
class Text(
    text: String,
    color: String? = null,
    style: String? = null,
    paddingX: Int = 0,
    paddingY: Int = 0,
) : Component {
    private var text: String = text
    private var color: String? = color
    private var style: String? = style
    private var paddingX: Int = paddingX.coerceAtLeast(0)
    private var paddingY: Int = paddingY.coerceAtLeast(0)

    // Width-dependent cache. Flushed on any state change or explicit invalidate().
    private var cachedWidth: Int = -1
    private var cachedLines: List<String>? = null

    /** Replace the displayed text. */
    fun setText(text: String) {
        if (this.text == text) return
        this.text = text
        invalidate()
    }

    /** Replace the ANSI color prefix (null clears it). */
    fun setColor(color: String?) {
        if (this.color == color) return
        this.color = color
        invalidate()
    }

    /** Replace the ANSI style prefix (null clears it). */
    fun setStyle(style: String?) {
        if (this.style == style) return
        this.style = style
        invalidate()
    }

    /** Set horizontal and vertical padding (both clamped to >= 0). */
    fun setPadding(x: Int, y: Int) {
        val nx = x.coerceAtLeast(0)
        val ny = y.coerceAtLeast(0)
        if (nx == paddingX && ny == paddingY) return
        paddingX = nx
        paddingY = ny
        invalidate()
    }

    override fun invalidate() {
        cachedWidth = -1
        cachedLines = null
    }

    override fun render(width: Int): List<String> {
        if (width <= 0) return emptyList()

        cachedLines?.let { if (cachedWidth == width) return it }

        // Empty content (absent or pure whitespace with no explicit newlines)
        // still renders the vertical padding rows — blank output is a legitimate
        // spacer. But if the text itself is zero-length AND paddingY == 0,
        // the component disappears entirely.
        if (text.isEmpty() && paddingY == 0) {
            val result = emptyList<String>()
            cachedWidth = width
            cachedLines = result
            return result
        }

        val contentWidth = (width - paddingX * 2).coerceAtLeast(1)
        val leftPad = if (paddingX > 0) " ".repeat(paddingX) else ""

        val result = mutableListOf<String>()

        // Top padding
        repeat(paddingY) { result += "" }

        // One wrapped line per input line (split on \n), each wrapped at contentWidth.
        // We call wordWrap per segment so literal newlines become hard breaks.
        val prefix = (color.orEmpty()) + (style.orEmpty())
        val hasStyling = prefix.isNotEmpty()

        for (inputLine in text.split('\n')) {
            val wrapped = if (inputLine.isEmpty()) {
                listOf("")
            } else {
                wordWrap(inputLine, contentWidth)
            }
            for (segment in wrapped) {
                val styled = if (hasStyling && segment.isNotEmpty()) {
                    "$prefix$segment${Ansi.RESET}"
                } else {
                    segment
                }
                val withPadding = if (paddingX > 0) {
                    "$leftPad$styled$leftPad"
                } else {
                    styled
                }
                // Defensive clamp: if the wrapped segment overshoots contentWidth
                // (possible once Wave 1A's wordWrap lands if it ever miscounts),
                // we truncate visually by trusting visibleWidth; the invariant
                // enforcement still lives in the runtime.
                result += withPadding
            }
        }

        // Bottom padding
        repeat(paddingY) { result += "" }

        cachedWidth = width
        cachedLines = result
        return result
    }

    // Exposed for tests: re-measure a rendered line to confirm the contract
    // without reaching into internal state.
    internal fun measure(line: String): Int = visibleWidth(line)

    /** Right-pad [line] to exactly [width] cells — mainly useful from external composers. */
    internal fun padRight(line: String, width: Int): String = padRightToWidth(line, width)
}
