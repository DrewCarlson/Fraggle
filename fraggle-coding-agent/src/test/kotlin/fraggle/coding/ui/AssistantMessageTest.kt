package fraggle.coding.ui

import fraggle.tui.text.Ansi
import fraggle.tui.text.stripAnsi
import fraggle.tui.text.visibleWidth
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AssistantMessageTest {

    @Nested
    inner class Basics {
        @Test
        fun `simple text renders with accent diamond and trailing blank`() {
            val msg = AssistantMessage("hello")
            val out = msg.render(80)
            assertTrue(out.size >= 2)
            assertTrue(stripAnsi(out[0]).startsWith("◆ hello") || stripAnsi(out[0]).startsWith("◆"))
            assertTrue(stripAnsi(out.last()).isBlank())
        }

        @Test
        fun `diamond marker uses accent color`() {
            val msg = AssistantMessage("hi")
            val out = msg.render(80)
            assertTrue(out[0].contains(codingTheme.accent))
            assertTrue(out[0].contains(Ansi.RESET))
        }

        @Test
        fun `every line is padded to full width`() {
            val msg = AssistantMessage("short body")
            for (line in msg.render(60)) {
                assertEquals(60, visibleWidth(line))
            }
        }
    }

    @Nested
    inner class ToolCalls {
        @Test
        fun `tool calls render with L-elbow and tool name`() {
            val calls = listOf(
                AssistantMessage.ToolCallSnippet("read_file", "{\"path\":\"x\"}"),
            )
            val msg = AssistantMessage("text", toolCalls = calls)
            val out = msg.render(80)
            val toolLine = out.firstOrNull { stripAnsi(it).contains("└─") }
                ?: error("no tool-call line found in:\n${out.joinToString("\n") { stripAnsi(it) }}")
            assertTrue(stripAnsi(toolLine).contains("read_file"))
        }

        @Test
        fun `tool call line uses toolCall color for name and dim for json`() {
            val calls = listOf(
                AssistantMessage.ToolCallSnippet("my_tool", "{\"a\":1}"),
            )
            val msg = AssistantMessage("text", toolCalls = calls)
            val out = msg.render(80)
            val toolLine = out.first { stripAnsi(it).contains("└─") }
            assertTrue(toolLine.contains(codingTheme.toolCall))
            assertTrue(toolLine.contains(codingTheme.dim))
            assertTrue(toolLine.contains(codingTheme.veryDim))
        }

        @Test
        fun `empty args do not render json segment`() {
            val calls = listOf(
                AssistantMessage.ToolCallSnippet("noop", "{}"),
            )
            val msg = AssistantMessage("text", toolCalls = calls)
            val out = msg.render(80)
            val toolLine = out.first { stripAnsi(it).contains("└─") }
            // No "{" after the name since args == "{}".
            val plain = stripAnsi(toolLine)
            assertTrue(plain.contains("noop"))
            // Ensure no json braces appear.
            assertTrue(!plain.contains("{}"), "unexpected empty-json snippet in '$plain'")
        }

        @Test
        fun `long json is truncated`() {
            val longJson = "{\"x\":\"" + "a".repeat(500) + "\"}"
            val calls = listOf(AssistantMessage.ToolCallSnippet("t", longJson))
            val msg = AssistantMessage("", toolCalls = calls)
            val out = msg.render(200)
            val toolLine = out.first { stripAnsi(it).contains("└─") }
            // Truncated to ~80 cells of snippet plus chrome; the stripped line cannot be absurdly long.
            val plain = stripAnsi(toolLine)
            assertTrue(plain.length <= 200, "tool line too wide: '$plain'")
        }

        @Test
        fun `multiple tool calls render on separate lines`() {
            val calls = listOf(
                AssistantMessage.ToolCallSnippet("a", "{}"),
                AssistantMessage.ToolCallSnippet("b", "{}"),
                AssistantMessage.ToolCallSnippet("c", "{}"),
            )
            val msg = AssistantMessage("", toolCalls = calls)
            val out = msg.render(80)
            val toolLines = out.filter { stripAnsi(it).contains("└─") }
            assertEquals(3, toolLines.size)
        }
    }

    @Nested
    inner class ErrorLine {
        @Test
        fun `error message renders with bang marker in error color`() {
            val msg = AssistantMessage("text", errorMessage = "something broke")
            val out = msg.render(80)
            val errorLine = out.firstOrNull { stripAnsi(it).contains("!") && stripAnsi(it).contains("something broke") }
                ?: error("no error line")
            assertTrue(errorLine.contains(codingTheme.error))
        }

        @Test
        fun `null errorMessage emits no error line`() {
            val msg = AssistantMessage("text", errorMessage = null)
            val out = msg.render(80)
            assertTrue(out.none { stripAnsi(it).contains("something broke") })
        }
    }

    @Nested
    inner class Mutability {
        @Test
        fun `setText updates rendered body`() {
            val msg = AssistantMessage("first")
            msg.setText("second")
            val out = msg.render(80)
            assertTrue(out.any { stripAnsi(it).contains("second") })
            assertTrue(out.none { stripAnsi(it).contains("first") })
        }

        @Test
        fun `setToolCalls updates rendered tool list`() {
            val msg = AssistantMessage("")
            msg.setToolCalls(listOf(AssistantMessage.ToolCallSnippet("added_tool", "{}")))
            val out = msg.render(80)
            assertTrue(out.any { stripAnsi(it).contains("added_tool") })
        }

        @Test
        fun `setErrorMessage updates the error line`() {
            val msg = AssistantMessage("")
            msg.setErrorMessage("oh no")
            val out = msg.render(80)
            assertTrue(out.any { stripAnsi(it).contains("oh no") })
        }
    }

    @Nested
    inner class WidthContract {
        @Test
        fun `width contract holds across many widths`() {
            val msg = AssistantMessage(
                text = "Lorem ipsum dolor sit amet ".repeat(10),
                toolCalls = listOf(AssistantMessage.ToolCallSnippet("a-very-very-long-tool-name", "{\"k\":\"v\"}")),
                errorMessage = "a fairly verbose error message about what went wrong",
            )
            for (w in listOf(10, 20, 40, 80, 120)) {
                for (line in msg.render(w)) {
                    assertTrue(visibleWidth(line) <= w, "width $w, line '${stripAnsi(line)}'")
                }
            }
        }

        @Test
        fun `zero width returns empty list`() {
            val msg = AssistantMessage("hi")
            assertEquals(emptyList(), msg.render(0))
        }
    }
}
