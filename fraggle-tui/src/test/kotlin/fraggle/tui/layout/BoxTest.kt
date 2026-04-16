package fraggle.tui.layout

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoxTest {

    /**
     * Empty child so `render` does not walk content lines or call into Wave 1A
     * stubs. This lets us verify border glyphs without blocking tests.
     */
    private fun emptyChild(): FakeComponent = FakeComponent(emptyList())

    @Nested
    inner class BorderGlyphs {
        @Test
        fun `rounded border uses curved corner glyphs`() {
            val box = Box(emptyChild(), style = Box.BorderStyle.ROUNDED, paddingY = 0, paddingX = 0)
            val lines = box.render(6)
            assertEquals(2, lines.size)
            assertEquals("╭────╮", lines[0])
            assertEquals("╰────╯", lines[1])
        }

        @Test
        fun `sharp border uses square corner glyphs`() {
            val box = Box(emptyChild(), style = Box.BorderStyle.SHARP, paddingY = 0, paddingX = 0)
            val lines = box.render(6)
            assertEquals(2, lines.size)
            assertEquals("┌────┐", lines[0])
            assertEquals("└────┘", lines[1])
        }

        @Test
        fun `double border uses double-line glyphs`() {
            val box = Box(emptyChild(), style = Box.BorderStyle.DOUBLE, paddingY = 0, paddingX = 0)
            val lines = box.render(6)
            assertEquals(2, lines.size)
            assertEquals("╔════╗", lines[0])
            assertEquals("╚════╝", lines[1])
        }

        @Test
        fun `NONE border omits glyphs entirely (empty child, zero padding is empty)`() {
            val box = Box(emptyChild(), style = Box.BorderStyle.NONE, paddingY = 0, paddingX = 0)
            val lines = box.render(6)
            assertEquals(emptyList(), lines)
        }

        @Test
        fun `NONE border with paddingY emits blank lines only`() {
            val box = Box(emptyChild(), style = Box.BorderStyle.NONE, paddingY = 2, paddingX = 0)
            val lines = box.render(6)
            assertEquals(listOf("", "", "", ""), lines)
        }
    }

    @Nested
    inner class DegenerateWidths {
        @Test
        fun `zero width renders nothing`() {
            val box = Box(emptyChild())
            assertEquals(emptyList(), box.render(0))
        }

        @Test
        fun `width 2 renders bare corner pair on each edge`() {
            // interior = 0 → no fill, just corners.
            val box = Box(emptyChild(), style = Box.BorderStyle.ROUNDED, paddingY = 0, paddingX = 0)
            val lines = box.render(2)
            assertEquals(2, lines.size)
            assertEquals("╭╮", lines[0])
            assertEquals("╰╯", lines[1])
        }
    }

    @Nested
    inner class Mutation {
        @Test
        fun `setBorderStyle swaps glyph family`() {
            val box = Box(emptyChild(), style = Box.BorderStyle.ROUNDED, paddingY = 0, paddingX = 0)
            assertTrue(box.render(6)[0].startsWith("╭"))
            box.setBorderStyle(Box.BorderStyle.SHARP)
            assertTrue(box.render(6)[0].startsWith("┌"))
        }

        @Test
        fun `setChild replaces the inner component`() {
            val box = Box(emptyChild())
            val replacement = emptyChild()
            box.setChild(replacement)
            box.render(10)
            assertEquals(1, replacement.renderCount)
        }

        @Test
        fun `setPadding clamps negatives`() {
            val box = Box(emptyChild(), paddingX = 0, paddingY = 0)
            box.setPadding(-4, -2)
            // Should still render something without throwing.
            assertEquals(2, box.render(6).size)
        }
    }

    @Nested
    inner class Invalidate {
        @Test
        fun `invalidate propagates to the child`() {
            val child = emptyChild()
            val box = Box(child)
            box.invalidate()
            assertEquals(1, child.invalidateCount)
        }
    }

    @Nested
    inner class ChildWidth {
        @Test
        fun `child is asked for width minus 2 border cells minus 2 paddingX`() {
            val child = emptyChild()
            val box = Box(child, paddingX = 3, paddingY = 0)
            box.render(20)
            assertEquals(20 - 2 - 6, child.lastWidth)
        }

        @Test
        fun `NONE border asks child for width minus 2 paddingX only`() {
            val child = emptyChild()
            val box = Box(child, style = Box.BorderStyle.NONE, paddingX = 2, paddingY = 0)
            box.render(20)
            assertEquals(16, child.lastWidth)
        }

        @Test
        fun `child content width clamps to 1 for very narrow widths`() {
            val child = emptyChild()
            val box = Box(child, paddingX = 2, paddingY = 0)
            box.render(3)
            assertEquals(1, child.lastWidth)
        }
    }

    @Nested
    inner class ContentBoundedByBorder {
        @Test
        fun `child content appears inside the border with leading vertical glyph`() {
            val child = FakeComponent(listOf("hi"))
            val box = Box(child, style = Box.BorderStyle.ROUNDED, paddingY = 0, paddingX = 1)
            val lines = box.render(10)
            assertEquals(3, lines.size)
            assertTrue(lines[1].startsWith("│"))
            assertTrue(lines[1].endsWith("│"))
            assertTrue(lines[1].contains("hi"))
        }

        @Test
        fun `child content taller than one line produces matching interior rows`() {
            val child = FakeComponent(listOf("a", "b", "c"))
            val box = Box(child, paddingY = 0, paddingX = 0)
            val lines = box.render(6)
            // top + 3 content + bottom = 5 lines
            assertEquals(5, lines.size)
        }
    }

    @Nested
    inner class Title {
        @Test
        fun `title appears on the top border with surrounding dashes`() {
            val box = Box(emptyChild(), title = "Hi", style = Box.BorderStyle.ROUNDED, paddingX = 0, paddingY = 0)
            val lines = box.render(20)
            assertTrue(lines[0].startsWith("╭"))
            assertTrue(lines[0].endsWith("╮"))
            assertTrue(lines[0].contains("Hi"))
        }

        @Test
        fun `title too long to fit gets truncated with ellipsis`() {
            val box = Box(emptyChild(), title = "This is a very long title", paddingX = 0, paddingY = 0)
            val lines = box.render(10)
            // Exact ellipsis content depends on Wave 1A's truncateToWidth
            // (ANSI reset escapes may appear in the raw string), but the
            // visible width must still be exactly the total width.
            assertEquals(10, fraggle.tui.text.visibleWidth(lines[0]))
        }

        @Test
        fun `no title omits the title segment`() {
            val box = Box(emptyChild(), title = null, paddingX = 0, paddingY = 0)
            val lines = box.render(10)
            assertEquals("╭────────╮", lines[0])
        }
    }
}
