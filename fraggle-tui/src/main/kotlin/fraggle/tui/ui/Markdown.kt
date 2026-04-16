package fraggle.tui.ui

import fraggle.tui.core.Component
import fraggle.tui.text.Ansi
import fraggle.tui.text.visibleWidth
import fraggle.tui.text.wordWrap
import fraggle.tui.theme.theme

/**
 * A [Component] that renders markdown source as ANSI-styled terminal lines.
 *
 * This is the fraggle-tui analogue of the coding agent's Compose-based
 * `fraggle.coding.tui.markdown.Markdown`. Same parser (JetBrains markdown,
 * GFM flavour), new emitter (ANSI lines instead of `AnnotatedString`
 * composables).
 *
 * ## Shape
 *
 * Supported blocks: paragraph, heading (1-6), unordered/ordered list with
 * nesting and continuation paragraphs, fenced + indented code blocks,
 * blockquote, horizontal rule. Inline: bold, italic, code, strikethrough,
 * links.
 *
 * Blocks are separated by a single blank line, except between adjacent list
 * items which stay packed tight.
 *
 * ## Width contract
 *
 * Every returned line satisfies `visibleWidth(line) <= width`. Code blocks
 * hard-wrap their content at column (`width - 2`) to preserve character
 * positions; everything else word-wraps.
 *
 * ## Caching
 *
 * The parsed [MdBlock] list is cached across renders and invalidated by
 * [setText]. The rendered line list is additionally cached per-width, so
 * steady-state rerenders at the same width are O(1).
 *
 * ## Graceful failure
 *
 * If the parser throws (streaming / partial input with unterminated
 * constructs), the component falls back to [wordWrap]ping the raw text. The
 * TUI never crashes because of malformed markdown — individual streaming
 * chunks commonly look invalid.
 *
 * @param text Markdown source. May contain partial / invalid constructs.
 * @param paddingX Horizontal padding (cells) added to both sides of every
 *   content line; reduces the effective wrapping width.
 * @param paddingY Vertical padding (rows) prepended and appended to the
 *   output.
 * @param fallbackColor Optional ANSI SGR prefix (e.g. `Theme.DARK.assistantText`)
 *   wrapped around plain text runs that don't have a more specific inline
 *   style. Headings, code, links, etc. use their own theme colors regardless.
 */
class Markdown(
    text: String,
    paddingX: Int = 0,
    paddingY: Int = 0,
    private val fallbackColor: String? = null,
) : Component {

    // LaTeX math fragments (`$\approx$`, `$x^2$`, etc.) that LLMs emit are
    // pre-processed into Unicode before reaching the parser. See
    // [LatexPreprocessor] for the substitution table.
    private var rawText: String = text
    private var text: String = LatexPreprocessor.process(text)
    private var paddingX: Int = paddingX.coerceAtLeast(0)
    private var paddingY: Int = paddingY.coerceAtLeast(0)

    private val parser = MarkdownParser()

    // Block cache — parsed once per text.
    private var cachedParseText: String? = null
    private var cachedBlocks: List<MdBlock>? = null

    // Render cache — keyed on width. Invalidated on text/padding change.
    private var cachedWidth: Int = -1
    private var cachedLines: List<String>? = null

    /** Replace the markdown source. No-op if unchanged. */
    fun setText(text: String) {
        if (this.rawText == text) return
        this.rawText = text
        this.text = LatexPreprocessor.process(text)
        invalidate()
    }

    /** Set horizontal and vertical padding (clamped to >= 0). */
    fun setPadding(x: Int, y: Int) {
        val nx = x.coerceAtLeast(0)
        val ny = y.coerceAtLeast(0)
        if (nx == paddingX && ny == paddingY) return
        paddingX = nx
        paddingY = ny
        // Padding changes affect line layout but not parsing.
        cachedWidth = -1
        cachedLines = null
    }

    override fun invalidate() {
        cachedParseText = null
        cachedBlocks = null
        cachedWidth = -1
        cachedLines = null
    }

    override fun render(width: Int): List<String> {
        if (width <= 0) return emptyList()

        cachedLines?.let { if (cachedWidth == width) return it }

        val result = if (text.isEmpty()) {
            if (paddingY == 0) emptyList() else List(paddingY * 2) { "" }
        } else {
            renderContent(width)
        }

        cachedWidth = width
        cachedLines = result
        return result
    }

    private fun renderContent(width: Int): List<String> {
        val contentWidth = (width - paddingX * 2).coerceAtLeast(1)
        val leftPad = if (paddingX > 0) " ".repeat(paddingX) else ""

        val blocks = parseCached(text)
        val emitter = MarkdownEmitter(theme, fallbackColor)

        val rawLines = try {
            emitter.emit(blocks, contentWidth)
        } catch (_: Throwable) {
            // Emitter should never throw, but belt & suspenders in case future
            // theme/bullet tweaks introduce a bad path for narrow widths.
            fallbackRender(contentWidth)
        }

        val lines = if (rawLines.isEmpty()) listOf("") else rawLines

        val out = ArrayList<String>(lines.size + paddingY * 2)

        // Top padding
        repeat(paddingY) { out += "" }

        for (line in lines) {
            val visible = visibleWidth(line)
            val padded = when {
                paddingX == 0 -> line
                visible <= contentWidth -> "$leftPad$line$leftPad"
                else -> line // Already over — leave it; width-invariant check below still applies.
            }
            out += padded
        }

        // Bottom padding
        repeat(paddingY) { out += "" }

        return out
    }

    private fun parseCached(text: String): List<MdBlock> {
        val cached = cachedBlocks
        if (cached != null && cachedParseText == text) return cached

        val fresh = try {
            parser.parse(text)
        } catch (_: Throwable) {
            // Partial/streaming input may occasionally make the parser
            // throw on an unfinished construct. Fall back to a single
            // paragraph of the raw source and let the emitter wrap it.
            listOf(MdBlock.Paragraph(InlineText(listOf(StyledSpan(text, InlineStyle.NONE)))))
        }
        cachedBlocks = fresh
        cachedParseText = text
        return fresh
    }

    /**
     * Last-ditch path when the emitter fails: ignore markdown entirely and
     * just word-wrap the raw text with the fallback color. Guarantees we
     * return something rather than crashing the TUI.
     */
    private fun fallbackRender(width: Int): List<String> {
        val prefix = fallbackColor
        val lines = wordWrap(text, width)
        if (prefix.isNullOrEmpty()) return lines
        return lines.map { if (it.isEmpty()) it else "$prefix$it${Ansi.RESET}" }
    }
}
