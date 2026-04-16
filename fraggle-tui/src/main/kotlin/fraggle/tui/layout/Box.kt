package fraggle.tui.layout

import com.jakewharton.mosaic.terminal.KeyboardEvent
import fraggle.tui.core.Component
import fraggle.tui.text.truncateToWidth
import fraggle.tui.text.visibleWidth

/**
 * A single-child container wrapped in a Unicode border, optionally labeled.
 *
 * ```
 * ╭─ title ─────╮
 * │  content    │
 * │  more       │
 * ╰─────────────╯
 * ```
 *
 * The border is drawn with the glyphs selected by [style]:
 *  - [BorderStyle.ROUNDED] — `╭ ╮ ╰ ╯ ─ │`
 *  - [BorderStyle.SHARP]   — `┌ ┐ └ ┘ ─ │`
 *  - [BorderStyle.DOUBLE]  — `╔ ╗ ╚ ╝ ═ ║`
 *  - [BorderStyle.NONE]    — no glyphs, just padding (useful when you want
 *    the padding semantics of Box without the border)
 *
 * A non-null [title] is rendered on the top edge as `─ title ─`. Titles are
 * truncated with an ellipsis if they don't fit in the available top-border
 * cells. Titles for `BorderStyle.NONE` are ignored.
 *
 * [paddingX] adds left+right spaces between the child content and the vertical
 * border. [paddingY] inserts blank lines above and below the content, inside
 * the border.
 */
class Box(
    child: Component,
    title: String? = null,
    style: BorderStyle = BorderStyle.ROUNDED,
    paddingX: Int = 1,
    paddingY: Int = 0,
) : Component {

    enum class BorderStyle { ROUNDED, SHARP, DOUBLE, NONE }

    private var child: Component = child
    private var title: String? = title
    private var style: BorderStyle = style
    private var paddingX: Int = paddingX.coerceAtLeast(0)
    private var paddingY: Int = paddingY.coerceAtLeast(0)

    /** Replace the single child component. */
    fun setChild(child: Component) {
        this.child = child
    }

    /** Replace the top-border title (null to omit). */
    fun setTitle(title: String?) {
        this.title = title
    }

    /** Switch the border glyph family. */
    fun setBorderStyle(style: BorderStyle) {
        this.style = style
    }

    /** Reconfigure interior padding. */
    fun setPadding(x: Int, y: Int) {
        paddingX = x.coerceAtLeast(0)
        paddingY = y.coerceAtLeast(0)
    }

    override fun render(width: Int): List<String> {
        if (width <= 0) return emptyList()

        val glyphs = Glyphs.forStyle(style)
        val borderWidth = if (style == BorderStyle.NONE) 0 else 1

        // Children get (width - 2*borderWidth - 2*paddingX) cells for content.
        // If that's less than 1 we still render the border but with zero content
        // columns.
        val contentWidth = (width - 2 * borderWidth - 2 * paddingX).coerceAtLeast(1)
        val childLines = child.render(contentWidth)

        val result = mutableListOf<String>()

        if (style == BorderStyle.NONE) {
            // Just padding — no visible border glyphs.
            repeat(paddingY) { result += "" }
            val leftPad = " ".repeat(paddingX)
            val rightPad = leftPad
            for (line in childLines) {
                result += if (paddingX > 0) "$leftPad$line$rightPad" else line
            }
            repeat(paddingY) { result += "" }
            return result
        }

        // Top border (with optional title)
        result += renderTopBorder(width, glyphs)

        // Interior rows: paddingY blank rows, then content, then paddingY blank rows.
        // "blank" still means "│       │" with the vertical border — the fill
        // happens between the two border glyphs.
        val interiorWidth = (width - 2 * borderWidth).coerceAtLeast(0)
        val blankInterior = buildInteriorLine("", interiorWidth, glyphs)

        repeat(paddingY) { result += blankInterior }

        for (line in childLines) {
            val padded = if (paddingX > 0) {
                " ".repeat(paddingX) + line + " ".repeat(paddingX)
            } else {
                line
            }
            result += buildInteriorLine(padded, interiorWidth, glyphs)
        }

        repeat(paddingY) { result += blankInterior }

        // Bottom border
        result += renderBottomBorder(width, glyphs)

        return result
    }

    override fun handleInput(key: KeyboardEvent): Boolean = child.handleInput(key)

    override fun invalidate() {
        child.invalidate()
    }

    private fun renderTopBorder(width: Int, glyphs: Glyphs): String {
        // Border consumes 2 cells for the corners. Everything else is fill plus
        // an optional title segment. Minimum renderable width is 2.
        if (width < 2) {
            // Degenerate: emit just what we can. Not hit in practice, but keeps
            // the width invariant true for pathological inputs.
            return glyphs.topLeft.toString().take(width)
        }

        val interior = width - 2
        val title = this.title?.takeIf { it.isNotEmpty() }

        if (title == null || interior < 4) {
            // No title, or not enough room for "─ x ─" (needs at least 4 cells:
            // one dash before, space, one char, space — we only render titles
            // when they fit at least "─ ? ─" = 5 cells, so 4 is the strict cutoff).
            val fill = glyphs.horizontal.toString().repeat(interior)
            return "${glyphs.topLeft}$fill${glyphs.topRight}"
        }

        // Title segment: "─ <title> ─". The ". " and " ." chrome cost 4 cells.
        // That leaves `interior - 4` cells for the title text; if that's
        // non-positive we fall through to no-title rendering.
        val maxTitleWidth = interior - 4
        if (maxTitleWidth < 1) {
            val fill = glyphs.horizontal.toString().repeat(interior)
            return "${glyphs.topLeft}$fill${glyphs.topRight}"
        }

        val truncated = truncateToWidth(title, maxTitleWidth)
        val titleCells = visibleWidth(truncated)
        // One leading dash, one space, title, one space, and fill-of-dashes for the rest.
        val leadingDashes = 1
        val trailingDashes = interior - leadingDashes - 1 - titleCells - 1
        val safeTrailing = trailingDashes.coerceAtLeast(0)
        val builder = StringBuilder()
        builder.append(glyphs.topLeft)
        builder.append(glyphs.horizontal.toString().repeat(leadingDashes))
        builder.append(' ')
        builder.append(truncated)
        builder.append(' ')
        builder.append(glyphs.horizontal.toString().repeat(safeTrailing))
        builder.append(glyphs.topRight)
        return builder.toString()
    }

    private fun renderBottomBorder(width: Int, glyphs: Glyphs): String {
        if (width < 2) return glyphs.bottomLeft.toString().take(width)
        val interior = width - 2
        val fill = glyphs.horizontal.toString().repeat(interior)
        return "${glyphs.bottomLeft}$fill${glyphs.bottomRight}"
    }

    private fun buildInteriorLine(content: String, interiorWidth: Int, glyphs: Glyphs): String {
        if (interiorWidth <= 0) return "${glyphs.vertical}${glyphs.vertical}"
        val contentCells = visibleWidth(content)
        val clamped = if (contentCells > interiorWidth) {
            // Trim to fit; truncateToWidth preserves ANSI state.
            truncateToWidth(content, interiorWidth)
        } else {
            content
        }
        val fillCells = interiorWidth - visibleWidth(clamped)
        val fill = if (fillCells > 0) " ".repeat(fillCells) else ""
        return "${glyphs.vertical}$clamped$fill${glyphs.vertical}"
    }

    private data class Glyphs(
        val topLeft: Char,
        val topRight: Char,
        val bottomLeft: Char,
        val bottomRight: Char,
        val horizontal: Char,
        val vertical: Char,
    ) {
        companion object {
            val ROUNDED = Glyphs('╭', '╮', '╰', '╯', '─', '│')
            val SHARP = Glyphs('┌', '┐', '└', '┘', '─', '│')
            val DOUBLE = Glyphs('╔', '╗', '╚', '╝', '═', '║')
            val NONE = Glyphs(' ', ' ', ' ', ' ', ' ', ' ')

            fun forStyle(style: BorderStyle): Glyphs = when (style) {
                BorderStyle.ROUNDED -> ROUNDED
                BorderStyle.SHARP -> SHARP
                BorderStyle.DOUBLE -> DOUBLE
                BorderStyle.NONE -> NONE
            }
        }
    }
}
