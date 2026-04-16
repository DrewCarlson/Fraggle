package fraggle.tui.ui

import fraggle.tui.core.Component
import fraggle.tui.text.Ansi

/**
 * A width-aware horizontal rule.
 *
 * Renders a single line of [style]'s character repeated to exactly [width]
 * cells, optionally wrapped in [paddingY] blank rows above and below. Useful
 * as a section divider between message groups, footer separators, or any
 * place where a full-width rule is wanted without pulling in a [Box].
 *
 * ```
 *                                   ← paddingY row
 * ──────────────────────────────── ← the rule itself
 *                                   ← paddingY row
 * ```
 */
class DynamicBorder(
    style: BorderStyle = BorderStyle.SINGLE,
    color: String? = null,
    paddingY: Int = 0,
) : Component {

    enum class BorderStyle(val char: String) {
        SINGLE("─"),
        DOUBLE("═"),
        DASHED("┈"),
        HEAVY("━"),
    }

    private var style: BorderStyle = style
    private var color: String? = color
    private var paddingY: Int = paddingY.coerceAtLeast(0)

    /** Replace the border glyph family. */
    fun setStyle(style: BorderStyle) {
        this.style = style
    }

    /** Replace the ANSI color prefix applied to the rule (null clears). */
    fun setColor(color: String?) {
        this.color = color
    }

    /** Reconfigure the number of blank rows above and below the rule. */
    fun setPaddingY(paddingY: Int) {
        this.paddingY = paddingY.coerceAtLeast(0)
    }

    override fun render(width: Int): List<String> {
        if (width <= 0) return emptyList()

        val rule = style.char.repeat(width)
        val colored = color?.takeIf { it.isNotEmpty() }?.let { "$it$rule${Ansi.RESET}" } ?: rule

        if (paddingY == 0) return listOf(colored)

        val out = ArrayList<String>(paddingY * 2 + 1)
        repeat(paddingY) { out += "" }
        out += colored
        repeat(paddingY) { out += "" }
        return out
    }
}
