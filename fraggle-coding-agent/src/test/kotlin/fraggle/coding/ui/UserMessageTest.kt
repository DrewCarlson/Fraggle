package fraggle.coding.ui

import fraggle.tui.text.Ansi
import fraggle.tui.text.stripAnsi
import fraggle.tui.text.visibleWidth
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserMessageTest {

    @Nested
    inner class Basics {
        @Test
        fun `simple text renders with marker on line 0 and trailing blank`() {
            val msg = UserMessage("hello world")
            val out = msg.render(80)
            // 1 content line + 1 blank separator (padded to full width with spaces).
            assertEquals(2, out.size)
            assertTrue(stripAnsi(out[0]).startsWith("» hello world"))
            assertTrue(stripAnsi(out[1]).isBlank())
        }

        @Test
        fun `marker is in userText color and terminates with RESET`() {
            val msg = UserMessage("hi")
            val out = msg.render(80)
            assertTrue(out[0].contains(codingTheme.userText))
            assertTrue(out[0].contains(Ansi.RESET))
        }

        @Test
        fun `body text uses foreground color`() {
            val msg = UserMessage("hi")
            val out = msg.render(80)
            assertTrue(out[0].contains(codingTheme.foreground))
        }

        @Test
        fun `every line is padded to full width`() {
            val msg = UserMessage("short")
            for (line in msg.render(50)) {
                assertEquals(50, visibleWidth(line))
            }
        }
    }

    @Nested
    inner class Wrapping {
        @Test
        fun `long text wraps into multiple lines`() {
            val msg = UserMessage("one two three four five six seven eight nine ten")
            val out = msg.render(12)
            // Expect the first line and at least one continuation before the trailing blank.
            assertTrue(out.size >= 3, "expected wrapping but got ${out.size} lines")
        }

        @Test
        fun `continuation lines indent 2 spaces`() {
            val msg = UserMessage("aaaa bbbb cccc dddd eeee ffff gggg hhhh")
            val out = msg.render(12)
            // out[0] starts with "»", out[1..last-1] start with "  ".
            val continuationLine = stripAnsi(out[1])
            assertTrue(continuationLine.startsWith("  "), "continuation did not indent: '$continuationLine'")
        }

        @Test
        fun `width contract holds for wrapped lines`() {
            val text = "Absolutely enormous message ".repeat(10)
            val msg = UserMessage(text)
            for (line in msg.render(20)) {
                assertTrue(visibleWidth(line) <= 20)
            }
        }
    }

    @Nested
    inner class Edges {
        @Test
        fun `empty text renders just marker and trailing blank`() {
            val msg = UserMessage("")
            val out = msg.render(40)
            assertEquals(2, out.size)
            assertTrue(stripAnsi(out[0]).startsWith("»"))
        }

        @Test
        fun `zero width returns empty list`() {
            val msg = UserMessage("hi")
            assertEquals(emptyList(), msg.render(0))
        }
    }

    @Nested
    inner class Mutability {
        @Test
        fun `setText swaps the displayed text`() {
            val msg = UserMessage("first")
            assertTrue(stripAnsi(msg.render(40)[0]).contains("first"))
            msg.setText("second")
            assertTrue(stripAnsi(msg.render(40)[0]).contains("second"))
        }
    }
}
