package fraggle.tui.ui

import fraggle.tui.text.visibleWidth
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DynamicBorderTest {

    @Nested
    inner class BorderStyles {
        @Test
        fun `SINGLE style uses box-drawing light horizontal`() {
            val border = DynamicBorder(style = DynamicBorder.BorderStyle.SINGLE)
            assertEquals(listOf("──────────"), border.render(10))
        }

        @Test
        fun `DOUBLE style uses box-drawing double horizontal`() {
            val border = DynamicBorder(style = DynamicBorder.BorderStyle.DOUBLE)
            assertEquals(listOf("=".let { "═══════════" }), border.render(11))
        }

        @Test
        fun `DASHED style uses dotted horizontal`() {
            val border = DynamicBorder(style = DynamicBorder.BorderStyle.DASHED)
            assertEquals(listOf("┈".repeat(5)), border.render(5))
        }

        @Test
        fun `HEAVY style uses thick horizontal`() {
            val border = DynamicBorder(style = DynamicBorder.BorderStyle.HEAVY)
            assertEquals(listOf("━".repeat(7)), border.render(7))
        }

        @Test
        fun `each BorderStyle char is width 1`() {
            for (style in DynamicBorder.BorderStyle.entries) {
                assertEquals(
                    1,
                    visibleWidth(style.char),
                    "style=$style char '${style.char}' must have visible width 1",
                )
            }
        }
    }

    @Nested
    inner class WidthContract {
        @Test
        fun `rule has exactly width visible cells`() {
            val border = DynamicBorder()
            for (w in listOf(1, 5, 10, 40, 80, 200)) {
                val lines = border.render(w)
                assertEquals(1, lines.size, "width=$w should produce one line when paddingY=0")
                assertEquals(w, visibleWidth(lines[0]), "width=$w rule should be $w cells")
            }
        }

        @Test
        fun `zero width produces empty output`() {
            val border = DynamicBorder()
            assertEquals(emptyList(), border.render(0))
        }

        @Test
        fun `negative width produces empty output`() {
            val border = DynamicBorder()
            assertEquals(emptyList(), border.render(-7))
        }

        @Test
        fun `width one renders a single border cell`() {
            val border = DynamicBorder(style = DynamicBorder.BorderStyle.SINGLE)
            assertEquals(listOf("─"), border.render(1))
        }
    }

    @Nested
    inner class Padding {
        @Test
        fun `paddingY adds blank rows above and below`() {
            val border = DynamicBorder(style = DynamicBorder.BorderStyle.SINGLE, paddingY = 1)
            val lines = border.render(4)
            assertEquals(3, lines.size)
            assertEquals("", lines[0])
            assertEquals("────", lines[1])
            assertEquals("", lines[2])
        }

        @Test
        fun `paddingY 2 adds two rows on each side`() {
            val border = DynamicBorder(paddingY = 2)
            val lines = border.render(5)
            assertEquals(5, lines.size)
            assertEquals("", lines[0])
            assertEquals("", lines[1])
            assertEquals("─────", lines[2])
            assertEquals("", lines[3])
            assertEquals("", lines[4])
        }

        @Test
        fun `paddingY zero emits single line`() {
            val border = DynamicBorder(paddingY = 0)
            assertEquals(1, border.render(10).size)
        }

        @Test
        fun `negative paddingY is clamped to zero`() {
            val border = DynamicBorder(paddingY = -3)
            assertEquals(1, border.render(10).size)
        }

        @Test
        fun `setPaddingY updates padding`() {
            val border = DynamicBorder(paddingY = 0)
            border.setPaddingY(2)
            assertEquals(5, border.render(10).size)
        }
    }

    @Nested
    inner class Color {
        @Test
        fun `color prefix wraps the rule with reset`() {
            val border = DynamicBorder(style = DynamicBorder.BorderStyle.SINGLE, color = "\u001B[31m")
            val lines = border.render(4)
            assertEquals(1, lines.size)
            assertTrue(lines[0].startsWith("\u001B[31m"), "expected red prefix; got ${lines[0]}")
            // Still exactly 4 visible cells.
            assertEquals(4, visibleWidth(lines[0]))
        }

        @Test
        fun `no color leaves the line bare of ansi`() {
            val border = DynamicBorder(color = null)
            val line = border.render(4)[0]
            assertTrue(!line.contains('\u001B'), "expected no ANSI escape; got $line")
        }

        @Test
        fun `setColor swaps the prefix`() {
            val border = DynamicBorder()
            val plain = border.render(5)[0]
            border.setColor("\u001B[34m")
            val colored = border.render(5)[0]
            assertTrue(!plain.contains('\u001B'))
            assertTrue(colored.contains('\u001B'))
            assertEquals(5, visibleWidth(colored))
        }

        @Test
        fun `setColor to null removes coloring`() {
            val border = DynamicBorder(color = "\u001B[33m")
            border.setColor(null)
            val line = border.render(5)[0]
            assertTrue(!line.contains('\u001B'))
        }

        @Test
        fun `color with padding only colors the rule row`() {
            val border = DynamicBorder(paddingY = 1, color = "\u001B[35m")
            val lines = border.render(4)
            assertEquals(3, lines.size)
            assertEquals("", lines[0])
            assertTrue(lines[1].startsWith("\u001B[35m"))
            assertEquals("", lines[2])
        }
    }

    @Nested
    inner class Mutation {
        @Test
        fun `setStyle swaps the border glyph`() {
            val border = DynamicBorder(style = DynamicBorder.BorderStyle.SINGLE)
            assertEquals("────", border.render(4)[0])
            border.setStyle(DynamicBorder.BorderStyle.HEAVY)
            assertEquals("━━━━", border.render(4)[0])
        }
    }
}
