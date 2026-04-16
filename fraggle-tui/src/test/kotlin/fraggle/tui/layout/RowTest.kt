package fraggle.tui.layout

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RowTest {

    @Nested
    inner class WidthAllocation {
        @Test
        fun `two children equal flex split width evenly`() {
            val widths = Row.allocateWidths(total = 10, flexes = listOf(1, 1))
            assertEquals(10, widths.sum())
            assertEquals(5, widths[0])
            assertEquals(5, widths[1])
        }

        @Test
        fun `three children equal flex with remainder goes to last`() {
            val widths = Row.allocateWidths(total = 10, flexes = listOf(1, 1, 1))
            assertEquals(10, widths.sum())
            // 10/3 = 3 each, remainder 1 → last gets 4
            assertEquals(3, widths[0])
            assertEquals(3, widths[1])
            assertEquals(4, widths[2])
        }

        @Test
        fun `unequal flex proportions distribute proportionally`() {
            val widths = Row.allocateWidths(total = 10, flexes = listOf(1, 3))
            assertEquals(10, widths.sum())
            // 1/4 * 10 = 2, 3/4 * 10 = 7, remainder 1 → last gets 8
            assertEquals(2, widths[0])
            assertEquals(8, widths[1])
        }

        @Test
        fun `single child gets full width`() {
            val widths = Row.allocateWidths(total = 42, flexes = listOf(1))
            assertEquals(intArrayOf(42).toList(), widths.toList())
        }

        @Test
        fun `empty flex list returns empty`() {
            val widths = Row.allocateWidths(total = 10, flexes = emptyList())
            assertTrue(widths.isEmpty())
        }

        @Test
        fun `zero total returns zeros`() {
            val widths = Row.allocateWidths(total = 0, flexes = listOf(1, 2, 3))
            assertEquals(listOf(0, 0, 0), widths.toList())
        }

        @Test
        fun `very narrow total goes entirely to first slot`() {
            // total < children count → every per-child floor is 0 and the
            // remainder should fall into the first (and only viable) slot.
            val widths = Row.allocateWidths(total = 1, flexes = listOf(1, 1, 1))
            assertEquals(1, widths.sum())
            assertEquals(1, widths[0])
            assertEquals(0, widths[1])
            assertEquals(0, widths[2])
        }
    }

    @Nested
    inner class Composition {
        @Test
        fun `two children sharing width 50 50 each receive half`() {
            val left = FakeComponent(listOf("a"))
            val right = FakeComponent(listOf("b"))
            val row = Row()
            row.addChild(left)
            row.addChild(right)
            row.render(20)
            assertEquals(10, left.lastWidth)
            assertEquals(10, right.lastWidth)
        }

        @Test
        fun `unequal flex produces proportional child widths`() {
            val a = FakeComponent(listOf("A"))
            val b = FakeComponent(listOf("B"))
            val row = Row()
            row.addChild(a, flex = 1)
            row.addChild(b, flex = 3)
            row.render(20)
            assertEquals(5, a.lastWidth)
            assertEquals(15, b.lastWidth)
        }

        @Test
        fun `empty row renders empty`() {
            val row = Row()
            assertEquals(emptyList(), row.render(10))
        }

        @Test
        fun `flex defaults to one when unspecified`() {
            val a = FakeComponent(listOf("a"))
            val b = FakeComponent(listOf("b"))
            val row = Row()
            row.addChild(a)
            row.addChild(b)
            row.render(20)
            // 20/2 = 10 each, no remainder
            assertEquals(10, a.lastWidth)
            assertEquals(10, b.lastWidth)
        }

        @Test
        fun `flex less than one clamps to one`() {
            val a = FakeComponent(listOf("a"))
            val b = FakeComponent(listOf("b"))
            val row = Row()
            row.addChild(a, flex = 0)
            row.addChild(b, flex = -1)
            row.render(10)
            // Both clamp to 1 → 5 each
            assertEquals(5, a.lastWidth)
            assertEquals(5, b.lastWidth)
        }
    }

    @Nested
    inner class Mutation {
        @Test
        fun `removeChild drops the component by identity`() {
            val a = FakeComponent(listOf("a"))
            val b = FakeComponent(listOf("b"))
            val row = Row()
            row.addChild(a)
            row.addChild(b)
            row.removeChild(a)
            assertEquals(listOf(b), row.children)
        }

        @Test
        fun `clear removes every child`() {
            val row = Row()
            row.addChild(FakeComponent(listOf("a")))
            row.addChild(FakeComponent(listOf("b")))
            row.clear()
            assertTrue(row.children.isEmpty())
        }
    }

    @Nested
    inner class Invalidate {
        @Test
        fun `invalidate propagates to every child`() {
            val a = FakeComponent(listOf("a"))
            val b = FakeComponent(listOf("b"))
            val row = Row()
            row.addChild(a)
            row.addChild(b)
            row.invalidate()
            assertEquals(1, a.invalidateCount)
            assertEquals(1, b.invalidateCount)
        }
    }

    @Nested
    inner class HeightAndComposition {
        @Test
        fun `row height matches the tallest child`() {
            val tall = FakeComponent(listOf("t1", "t2", "t3"))
            val short = FakeComponent(listOf("s1"))
            val row = Row()
            row.addChild(tall)
            row.addChild(short)
            val lines = row.render(20)
            assertEquals(3, lines.size)
        }

        @Test
        fun `shorter children contribute blank cells in their allocated width`() {
            val tall = FakeComponent(listOf("A", "B", "C"))
            val short = FakeComponent(listOf("x"))
            val row = Row()
            row.addChild(tall)
            row.addChild(short)
            val lines = row.render(10)
            // Width 10 split evenly = 5+5. Short child has 1 row; rows 1+2 in
            // its column should be padded blanks of width 5.
            assertEquals(3, lines.size)
            // Row 0: "A    x    "
            assertEquals(10, lines[0].length)
            // Row 1 and 2: "B    " and "C    " — short column all spaces.
            assertTrue(lines[1].endsWith("     "), "row 1 should have 5 blank tail, got `${lines[1]}`")
            assertTrue(lines[2].endsWith("     "), "row 2 should have 5 blank tail, got `${lines[2]}`")
        }

        @Test
        fun `each composed line equals total width exactly`() {
            val a = FakeComponent(listOf("L"))
            val b = FakeComponent(listOf("R"))
            val row = Row()
            row.addChild(a)
            row.addChild(b)
            val lines = row.render(8)
            assertEquals(1, lines.size)
            assertEquals(8, lines[0].length)
        }
    }
}
