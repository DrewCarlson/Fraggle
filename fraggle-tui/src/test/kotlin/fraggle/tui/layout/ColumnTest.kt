package fraggle.tui.layout

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColumnTest {

    @Nested
    inner class Stacking {
        @Test
        fun `empty column with zero padding renders no lines`() {
            val column = Column()
            assertEquals(emptyList(), column.render(10))
        }

        @Test
        fun `two children render top-to-bottom in order`() {
            val column = Column()
            column.addChild(FakeComponent(listOf("a1", "a2")))
            column.addChild(FakeComponent(listOf("b1")))
            assertEquals(listOf("a1", "a2", "b1"), column.render(10))
        }

        @Test
        fun `empty children contribute no lines`() {
            val column = Column()
            column.addChild(FakeComponent(emptyList()))
            column.addChild(FakeComponent(listOf("only")))
            assertEquals(listOf("only"), column.render(10))
        }
    }

    @Nested
    inner class Padding {
        @Test
        fun `paddingY adds blank rows before and after children`() {
            val column = Column()
            column.setPadding(x = 0, y = 2)
            column.addChild(FakeComponent(listOf("content")))
            val lines = column.render(10)
            assertEquals(listOf("", "", "content", "", ""), lines)
        }

        @Test
        fun `paddingX shrinks child width by 2x`() {
            val child = FakeComponent(listOf("x"))
            val column = Column()
            column.setPadding(x = 3, y = 0)
            column.addChild(child)
            column.render(width = 20)
            assertEquals(14, child.lastWidth)
        }

        @Test
        fun `paddingX wraps each child line with left+right gutters`() {
            val column = Column()
            column.setPadding(x = 2, y = 0)
            column.addChild(FakeComponent(listOf("AB")))
            val lines = column.render(10)
            assertEquals(listOf("  AB  "), lines)
        }

        @Test
        fun `paddingX zero does not prepend spaces`() {
            val column = Column()
            column.addChild(FakeComponent(listOf("raw")))
            assertEquals(listOf("raw"), column.render(10))
        }

        @Test
        fun `paddingX content width clamps to 1 for very narrow widths`() {
            val child = FakeComponent(listOf(""))
            val column = Column()
            column.setPadding(x = 5, y = 0)
            column.addChild(child)
            column.render(width = 3)
            assertEquals(1, child.lastWidth, "content width must clamp at 1 minimum")
        }
    }

    @Nested
    inner class MaxHeight {
        @Test
        fun `zero maxHeight means unlimited`() {
            val column = Column()
            repeat(5) { column.addChild(FakeComponent(listOf("row$it"))) }
            assertEquals(5, column.render(10).size)
        }

        @Test
        fun `maxHeight caps output from the top`() {
            val column = Column()
            column.setMaxHeight(3)
            repeat(5) { column.addChild(FakeComponent(listOf("row$it"))) }
            val lines = column.render(10)
            assertEquals(3, lines.size)
            assertEquals(listOf("row0", "row1", "row2"), lines)
        }

        @Test
        fun `maxHeight larger than output is a no-op`() {
            val column = Column()
            column.setMaxHeight(100)
            column.addChild(FakeComponent(listOf("a", "b")))
            assertEquals(listOf("a", "b"), column.render(10))
        }

        @Test
        fun `negative maxHeight clamps to zero which means unlimited`() {
            val column = Column()
            column.setMaxHeight(-5)
            column.addChild(FakeComponent(listOf("a", "b", "c")))
            assertEquals(3, column.render(10).size)
        }
    }

    @Nested
    inner class Invalidate {
        @Test
        fun `invalidate propagates to every child`() {
            val c1 = FakeComponent(listOf("a"))
            val c2 = FakeComponent(listOf("b"))
            val column = Column()
            column.addChild(c1)
            column.addChild(c2)
            column.invalidate()
            assertEquals(1, c1.invalidateCount)
            assertEquals(1, c2.invalidateCount)
        }
    }

    @Nested
    inner class WidthInvariant {
        @Test
        fun `line lengths are bounded by renderer contract`() {
            // We don't have visibleWidth yet (Wave 1A), so assert the byte/char
            // length is <= width; for ASCII children this matches visible width.
            val column = Column()
            column.setPadding(x = 1, y = 0)
            column.addChild(FakeComponent(listOf("abc")))
            val lines = column.render(width = 10)
            assertTrue(lines.all { it.length <= 10 })
        }
    }
}
