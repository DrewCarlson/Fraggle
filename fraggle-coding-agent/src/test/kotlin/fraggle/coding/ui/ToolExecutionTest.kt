package fraggle.coding.ui

import fraggle.tui.text.Ansi
import fraggle.tui.text.stripAnsi
import fraggle.tui.text.visibleWidth
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolExecutionTest {

    @Nested
    inner class Basics {
        @Test
        fun `success marker is a left-arrow`() {
            val out = ToolExecution("read_file", isError = false, output = "ok").render(80)
            assertTrue(stripAnsi(out[0]).contains("← "))
        }

        @Test
        fun `error marker is a cross`() {
            val out = ToolExecution("read_file", isError = true, output = "boom").render(80)
            assertTrue(stripAnsi(out[0]).contains("✗ "))
        }

        @Test
        fun `leading indent is 5 spaces`() {
            val out = ToolExecution("x", output = "y").render(80)
            assertTrue(stripAnsi(out[0]).startsWith("     "))
        }

        @Test
        fun `tool name appears after marker`() {
            val out = ToolExecution("my_tool", output = "result").render(80)
            val plain = stripAnsi(out[0])
            assertTrue(plain.contains("my_tool"))
            assertTrue(plain.contains("my_tool: result"))
        }

        @Test
        fun `rendered lines are padded to full width`() {
            val out = ToolExecution("t", output = "hi").render(60)
            for (line in out) {
                assertEquals(60, visibleWidth(line))
            }
        }

        @Test
        fun `trailing blank line is emitted`() {
            val out = ToolExecution("t", output = "hi").render(60)
            assertTrue(out.size == 2)
            assertTrue(stripAnsi(out.last()).isBlank())
        }
    }

    @Nested
    inner class Colors {
        @Test
        fun `success marker and output use toolResult color`() {
            val out = ToolExecution("t", output = "ok").render(80)
            assertTrue(out[0].contains(codingTheme.toolResult))
            assertTrue(out[0].contains(codingTheme.toolCall)) // for the tool name
            assertTrue(out[0].contains(Ansi.RESET))
        }

        @Test
        fun `error marker and output use toolError color`() {
            val out = ToolExecution("t", isError = true, output = "boom").render(80)
            assertTrue(out[0].contains(codingTheme.toolError))
            assertTrue(out[0].contains(codingTheme.toolCall))
        }
    }

    @Nested
    inner class Truncation {
        @Test
        fun `very long output is truncated with ellipsis`() {
            val huge = "x".repeat(5000)
            val out = ToolExecution("t", output = huge).render(300)
            val plain = stripAnsi(out[0])
            // Line should not be longer than the 300-cell viewport.
            assertTrue(plain.length <= 300)
            // And should not contain the full 5000 x's.
            assertTrue(plain.count { it == 'x' } < 5000)
        }

        @Test
        fun `newlines in output become spaces`() {
            val out = ToolExecution("t", output = "line1\nline2\nline3").render(80)
            val plain = stripAnsi(out[0])
            assertTrue(plain.contains("line1"))
            assertTrue(plain.contains("line2"))
            assertTrue(!plain.contains("\n"))
        }
    }

    @Nested
    inner class Mutability {
        @Test
        fun `setOutput updates the displayed output`() {
            val te = ToolExecution("t", output = "first")
            assertTrue(stripAnsi(te.render(80)[0]).contains("first"))
            te.setOutput("second")
            val plain = stripAnsi(te.render(80)[0])
            assertTrue(plain.contains("second"))
            assertTrue(!plain.contains("first"))
        }

        @Test
        fun `setIsError swaps the marker and colors`() {
            val te = ToolExecution("t", isError = false, output = "o")
            assertTrue(stripAnsi(te.render(80)[0]).contains("← "))
            te.setIsError(true)
            val line = te.render(80)[0]
            assertTrue(stripAnsi(line).contains("✗ "))
            assertTrue(line.contains(codingTheme.toolError))
        }
    }

    @Nested
    inner class WidthContract {
        @Test
        fun `tight widths still satisfy the width contract`() {
            val te = ToolExecution("some_tool", output = "hello world how are you today")
            for (w in listOf(10, 15, 20, 30, 80, 120)) {
                for (line in te.render(w)) {
                    assertTrue(visibleWidth(line) <= w, "width $w: '${stripAnsi(line)}'")
                }
            }
        }

        @Test
        fun `zero width returns empty list`() {
            assertEquals(emptyList(), ToolExecution("t", output = "o").render(0))
        }
    }
}
