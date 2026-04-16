package fraggle.tui.layout

import com.jakewharton.mosaic.terminal.KeyboardEvent
import fraggle.tui.core.Component

/**
 * A vertical stack of children — like [fraggle.tui.core.Container] but with
 * optional padding and an optional maximum height.
 *
 * Children are rendered in declaration order. Each child is asked to render at
 * `width - 2 * paddingX` so horizontal padding appears as left+right gutters.
 * Vertical padding is inserted as blank lines above the first child and below
 * the last child. A [maxHeight] of `> 0` caps the total emitted lines — any
 * lines beyond the cap are dropped from the end (callers that need scrolling
 * wrap the column in a higher-level viewport component).
 *
 * Use [fraggle.tui.core.Container] when you want bare vertical stacking with
 * no padding or height cap; use [Column] when you need those knobs.
 */
class Column : Component {
    private val _children: MutableList<Component> = mutableListOf()
    private var paddingX: Int = 0
    private var paddingY: Int = 0
    private var maxHeight: Int = 0 // 0 = unlimited

    /** Read-only view of the current children list. */
    val children: List<Component> get() = _children

    /** Append [component] to the end of the column. */
    fun addChild(component: Component) {
        _children += component
    }

    /** Remove [component] if it's a current child. */
    fun removeChild(component: Component) {
        _children.remove(component)
    }

    /** Remove all children. */
    fun clear() {
        _children.clear()
    }

    /** Set horizontal/vertical padding in cells/rows (both clamped to >= 0). */
    fun setPadding(x: Int, y: Int) {
        paddingX = x.coerceAtLeast(0)
        paddingY = y.coerceAtLeast(0)
    }

    /**
     * Cap the column's total rendered height. Pass `0` (the default) to mean
     * "unlimited — render every line every child wants".
     */
    fun setMaxHeight(maxHeight: Int) {
        this.maxHeight = maxHeight.coerceAtLeast(0)
    }

    override fun render(width: Int): List<String> {
        if (_children.isEmpty() && paddingY == 0) return emptyList()
        if (width <= 0) return emptyList()

        val contentWidth = (width - paddingX * 2).coerceAtLeast(1)
        val leftPad = if (paddingX > 0) " ".repeat(paddingX) else ""
        val rightPad = leftPad

        val lines = mutableListOf<String>()

        // Top padding
        repeat(paddingY) { lines += "" }

        for (child in _children) {
            for (childLine in child.render(contentWidth)) {
                val padded = if (paddingX > 0) {
                    // Add left+right gutters. Note: we do NOT right-pad to full width
                    // here — that's the renderer's job. Visible width of the padded
                    // line is (2*paddingX + childLineVisibleWidth), which is
                    // guaranteed <= width since childLine's visible width <= contentWidth.
                    "$leftPad$childLine$rightPad"
                } else {
                    childLine
                }
                lines += padded
            }
        }

        // Bottom padding
        repeat(paddingY) { lines += "" }

        // Enforce height cap
        val cap = maxHeight
        if (cap > 0 && lines.size > cap) {
            return lines.subList(0, cap).toList()
        }
        return lines
    }

    override fun handleInput(key: KeyboardEvent): Boolean {
        // Mirror Container: deliver to children in reverse order so the most
        // recently added has priority.
        for (i in _children.indices.reversed()) {
            if (_children[i].handleInput(key)) return true
        }
        return false
    }

    override fun invalidate() {
        for (child in _children) child.invalidate()
    }
}
