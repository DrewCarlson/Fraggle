package fraggle.tui.layout

import com.jakewharton.mosaic.terminal.KeyboardEvent
import fraggle.tui.core.Component
import fraggle.tui.text.padRightToWidth
import fraggle.tui.text.visibleWidth

/**
 * A horizontal stack of children. Each child is given a slice of the total
 * [width] based on its [flex] weight; the slices sum to exactly `width`.
 *
 * The component height equals `max(child.render(slice).size)` across all
 * children. Shorter children are padded with empty rows at the bottom so each
 * composed line has a cell for every child.
 *
 * Allocation algorithm:
 *  1. Sum the flex weights. If zero (should not happen — [addChild] defaults
 *     to 1), return empty.
 *  2. Each child gets `floor(width * flex / totalFlex)` cells.
 *  3. Any leftover cells from flooring go to the last child so the total is
 *     exactly [width].
 */
class Row : Component {
    private data class Entry(val component: Component, val flex: Int)

    private val entries: MutableList<Entry> = mutableListOf()

    /** Read-only snapshot of the children in insertion order. */
    val children: List<Component> get() = entries.map { it.component }

    /** Append [component] with a [flex] weight (clamped to >= 1). */
    fun addChild(component: Component, flex: Int = 1) {
        entries += Entry(component, flex.coerceAtLeast(1))
    }

    /** Remove [component] if present. No-op otherwise. */
    fun removeChild(component: Component) {
        entries.removeAll { it.component === component }
    }

    /** Remove every child. */
    fun clear() {
        entries.clear()
    }

    override fun render(width: Int): List<String> {
        if (entries.isEmpty() || width <= 0) return emptyList()

        val widths = allocateWidths(width, entries.map { it.flex })

        // Render each child at its allocated width. Filter out zero-width
        // allocations so a very narrow terminal doesn't crash on a child that
        // cannot honor `width >= 1`.
        val childOutputs: List<List<String>> = entries.mapIndexed { index, entry ->
            val w = widths[index]
            if (w <= 0) emptyList() else entry.component.render(w)
        }

        val height = childOutputs.maxOfOrNull { it.size } ?: 0
        if (height == 0) return emptyList()

        val composedLines = ArrayList<String>(height)
        val sb = StringBuilder()
        for (row in 0 until height) {
            sb.setLength(0)
            for (i in entries.indices) {
                val w = widths[i]
                if (w <= 0) continue
                val lines = childOutputs[i]
                val line = if (row < lines.size) lines[row] else ""
                // Pad each cell to exactly its allocated width so subsequent
                // cells start at the right column.
                sb.append(padRightToWidth(line, w))
            }
            composedLines += sb.toString()
        }
        return composedLines
    }

    override fun handleInput(key: KeyboardEvent): Boolean {
        for (i in entries.indices.reversed()) {
            if (entries[i].component.handleInput(key)) return true
        }
        return false
    }

    override fun invalidate() {
        for (entry in entries) entry.component.invalidate()
    }

    companion object {
        /**
         * Distribute [total] cells across children whose flex weights are
         * [flexes]. Each slot gets `floor(total * flex / sum)`; any rounding
         * remainder is poured into the last non-zero slot so the returned
         * widths sum to exactly [total].
         *
         * Public (internal) so tests can verify the allocation math directly
         * rather than inferring it from rendered output.
         */
        internal fun allocateWidths(total: Int, flexes: List<Int>): IntArray {
            val n = flexes.size
            val out = IntArray(n)
            if (n == 0 || total <= 0) return out

            val sum = flexes.sum()
            if (sum <= 0) return out

            var assigned = 0
            for (i in 0 until n) {
                out[i] = (total.toLong() * flexes[i] / sum).toInt()
                assigned += out[i]
            }

            val remainder = total - assigned
            if (remainder > 0) {
                // Give the leftover cells to the last slot that already has
                // at least 1 cell; if every slot is zero (e.g. total < n),
                // hand them to the first slot so something renders.
                var target = -1
                for (i in n - 1 downTo 0) {
                    if (out[i] > 0) {
                        target = i
                        break
                    }
                }
                if (target < 0) target = 0
                out[target] += remainder
            }
            return out
        }

        // Re-expose so tests can avoid depending on the text package directly.
        internal fun measure(line: String): Int = visibleWidth(line)
    }
}
