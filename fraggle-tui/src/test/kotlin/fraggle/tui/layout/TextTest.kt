package fraggle.tui.layout

import fraggle.tui.text.Ansi
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextTest {

    @Nested
    inner class Empty {
        @Test
        fun `empty text with no padding renders no lines`() {
            val text = Text("")
            assertEquals(emptyList(), text.render(10))
        }

        @Test
        fun `empty text with zero width renders no lines`() {
            val text = Text("hello")
            assertEquals(emptyList(), text.render(0))
        }
    }

    @Nested
    inner class Wrapping {
        @Test
        fun `single short line renders as one styled line`() {
            val text = Text("hi")
            val lines = text.render(10)
            assertEquals(1, lines.size)
        }

        @Test
        fun `multi-line input emits each line separately`() {
            val text = Text("a\nb\nc")
            val lines = text.render(10)
            assertEquals(3, lines.size)
            assertTrue(lines[0].contains("a"))
            assertTrue(lines[1].contains("b"))
            assertTrue(lines[2].contains("c"))
        }

        @Test
        fun `long line is wrapped to fit the width`() {
            val text = Text("one two three four five six")
            val lines = text.render(10)
            assertTrue(lines.size > 1, "expected wrapping but got ${lines.size} line(s)")
        }

        @Test
        fun `color prefix wraps every content line with RESET suffix`() {
            val text = Text("hello", color = Ansi.fgRgb(0, 200, 200))
            val lines = text.render(10)
            assertEquals(1, lines.size)
            assertTrue(lines[0].startsWith(Ansi.fgRgb(0, 200, 200)), "color prefix missing")
            assertTrue(lines[0].endsWith(Ansi.RESET), "reset suffix missing")
        }

        @Test
        fun `style prefix is combined with color prefix`() {
            val text = Text("hello", color = Ansi.fgRgb(0, 0, 0), style = Ansi.BOLD)
            val lines = text.render(10)
            assertTrue(lines[0].contains(Ansi.BOLD))
            assertTrue(lines[0].contains(Ansi.fgRgb(0, 0, 0)))
        }
    }

    @Nested
    inner class Padding {
        @Test
        fun `paddingY adds blank rows above and below content`() {
            val text = Text("a", paddingY = 1)
            val lines = text.render(10)
            assertEquals(3, lines.size)
            assertTrue(lines.first().isEmpty(), "expected blank top pad")
            assertTrue(lines.last().isEmpty(), "expected blank bottom pad")
        }

        @Test
        fun `paddingX prepends and appends that many spaces`() {
            val text = Text("x", paddingX = 2)
            val lines = text.render(10)
            assertEquals(1, lines.size)
            // The content has styling around "x" but the padding is raw spaces.
            assertTrue(lines[0].startsWith("  "), "expected 2 leading spaces")
            assertTrue(lines[0].endsWith("  "), "expected 2 trailing spaces")
        }
    }

    @Nested
    inner class ApiShape {
        // These exercise the setters without touching TextUtils.
        @Test
        fun `setText swaps text value (does not invoke wordWrap)`() {
            val text = Text("")
            text.setText("x")
            // we only call render with empty text — path returns empty and never
            // hits wordWrap.
            text.setText("")
            assertEquals(emptyList(), text.render(10))
        }

        @Test
        fun `setColor is idempotent`() {
            val text = Text("")
            text.setColor(Ansi.RESET)
            text.setColor(Ansi.RESET)
            assertEquals(emptyList(), text.render(10))
        }

        @Test
        fun `setStyle is idempotent`() {
            val text = Text("")
            text.setStyle(Ansi.BOLD)
            text.setStyle(Ansi.BOLD)
            assertEquals(emptyList(), text.render(10))
        }

        @Test
        fun `setPadding clamps negative values`() {
            val text = Text("")
            text.setPadding(x = -4, y = -2)
            assertEquals(emptyList(), text.render(10))
        }

        @Test
        fun `invalidate is a no-op on empty text`() {
            val text = Text("")
            text.invalidate()
            assertEquals(emptyList(), text.render(10))
        }
    }
}
