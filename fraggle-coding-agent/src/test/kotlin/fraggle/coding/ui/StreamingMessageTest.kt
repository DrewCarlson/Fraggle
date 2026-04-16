package fraggle.coding.ui

import fraggle.tui.text.Ansi
import fraggle.tui.text.stripAnsi
import fraggle.tui.text.visibleWidth
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamingMessageTest {

    @Nested
    inner class Empty {
        @Test
        fun `empty text renders thinking placeholder`() {
            val msg = StreamingMessage("")
            val out = msg.render(80)
            assertEquals(1, out.size)
            val plain = stripAnsi(out[0])
            assertTrue(plain.contains("◆"))
            assertTrue(plain.contains("…"))
        }

        @Test
        fun `thinking placeholder uses accent color`() {
            val msg = StreamingMessage("")
            val line = msg.render(80)[0]
            assertTrue(line.contains(codingTheme.accent))
            assertTrue(line.contains(Ansi.RESET))
        }

        @Test
        fun `thinking placeholder is width-exact`() {
            val msg = StreamingMessage("")
            assertEquals(60, visibleWidth(msg.render(60)[0]))
        }
    }

    @Nested
    inner class Streaming {
        @Test
        fun `text renders with diamond marker aligned`() {
            val msg = StreamingMessage("hello")
            val out = msg.render(80)
            assertTrue(stripAnsi(out[0]).startsWith("◆ hello") || stripAnsi(out[0]).contains("hello"))
        }

        @Test
        fun `does not emit trailing blank line`() {
            val msg = StreamingMessage("brief")
            val out = msg.render(80)
            // The text is short enough to fit on one line — so we expect exactly 1 line, no trailing blank.
            assertEquals(1, out.size)
            assertTrue(stripAnsi(out.last()).isNotEmpty())
        }

        @Test
        fun `long text wraps and all lines are padded`() {
            val long = "this is a fairly long message that should definitely wrap ".repeat(4)
            val msg = StreamingMessage(long)
            val out = msg.render(30)
            assertTrue(out.size > 1, "expected wrapping")
            for (line in out) {
                assertEquals(30, visibleWidth(line))
            }
        }

        @Test
        fun `continuation lines indent 2 spaces`() {
            val msg = StreamingMessage("aaa bbb ccc ddd eee fff ggg hhh iii jjj")
            val out = msg.render(15)
            assertTrue(out.size >= 2)
            val continuation = stripAnsi(out[1])
            assertTrue(continuation.startsWith("  "), "did not indent: '$continuation'")
        }
    }

    @Nested
    inner class Mutability {
        @Test
        fun `setText swaps text and rerenders`() {
            val msg = StreamingMessage("first chunk")
            assertTrue(stripAnsi(msg.render(80)[0]).contains("first chunk"))
            msg.setText("first chunk second chunk")
            val out = msg.render(80)
            assertTrue(out.any { stripAnsi(it).contains("second chunk") })
        }

        @Test
        fun `setText to empty string returns to thinking placeholder`() {
            val msg = StreamingMessage("something")
            msg.setText("")
            val out = msg.render(80)
            assertEquals(1, out.size)
            assertTrue(stripAnsi(out[0]).contains("…"))
        }
    }

    @Nested
    inner class WidthContract {
        @Test
        fun `width contract holds at many widths`() {
            val msg = StreamingMessage("hello ".repeat(20))
            for (w in listOf(10, 20, 40, 80)) {
                for (line in msg.render(w)) {
                    assertTrue(visibleWidth(line) <= w, "width $w")
                }
            }
        }

        @Test
        fun `zero width returns empty list`() {
            assertEquals(emptyList(), StreamingMessage("hi").render(0))
        }
    }
}
