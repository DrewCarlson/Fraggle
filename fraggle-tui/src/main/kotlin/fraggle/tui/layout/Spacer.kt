package fraggle.tui.layout

import fraggle.tui.core.Component

/**
 * An N-line empty block used to add vertical whitespace between components in a
 * [fraggle.tui.core.Container] or [Column]. Its horizontal size is always zero
 * visible cells regardless of the viewport width — the renderer fills the rest
 * of the line naturally when compositing.
 *
 * ```
 * container.addChild(Header())
 * container.addChild(Spacer(2))    // two blank rows
 * container.addChild(MessageList())
 */
class Spacer(lines: Int = 1) : Component {
    private var lines: Int = lines.coerceAtLeast(0)

    /** Change the number of blank lines this spacer emits. */
    fun setLines(lines: Int) {
        this.lines = lines.coerceAtLeast(0)
    }

    override fun render(width: Int): List<String> {
        if (lines == 0) return emptyList()
        return List(lines) { "" }
    }
}
