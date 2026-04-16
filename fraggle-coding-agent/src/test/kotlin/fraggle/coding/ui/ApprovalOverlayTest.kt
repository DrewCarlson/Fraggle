package fraggle.coding.ui

import fraggle.tui.text.stripAnsi
import fraggle.tui.text.visibleWidth
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApprovalOverlayTest {

    @Nested
    inner class Basics {
        @Test
        fun `renders at least 6 lines (box + 4 body lines)`() {
            val overlay = ApprovalOverlay(PendingApproval("read_file", "{\"path\":\"/tmp\"}"))
            val out = overlay.render(60)
            // Box: top border, 4 body rows, bottom border = 6 lines.
            assertEquals(6, out.size)
        }

        @Test
        fun `top border contains the title`() {
            val overlay = ApprovalOverlay(PendingApproval("t", "{}"))
            val out = overlay.render(60)
            val topBorder = stripAnsi(out[0])
            assertTrue(topBorder.contains("Tool approval required"), "top border: $topBorder")
        }

        @Test
        fun `every rendered line matches the width`() {
            val overlay = ApprovalOverlay(PendingApproval("my_tool", "{\"a\":1,\"b\":2}"))
            val out = overlay.render(60)
            for (line in out) {
                assertEquals(60, visibleWidth(line))
            }
        }
    }

    @Nested
    inner class Body {
        @Test
        fun `body includes Tool and Args labels`() {
            val overlay = ApprovalOverlay(PendingApproval("my_tool", "{\"path\":\"a\"}"))
            val out = overlay.render(60)
            val bodyText = out.drop(1).dropLast(1).joinToString("\n") { stripAnsi(it) }
            assertTrue(bodyText.contains("Tool:"))
            assertTrue(bodyText.contains("Args:"))
        }

        @Test
        fun `tool name appears in body`() {
            val overlay = ApprovalOverlay(PendingApproval("rm_rf", "{}"))
            val out = overlay.render(60)
            val bodyText = out.drop(1).dropLast(1).joinToString("\n") { stripAnsi(it) }
            assertTrue(bodyText.contains("rm_rf"))
        }

        @Test
        fun `tool name uses toolCall color`() {
            val overlay = ApprovalOverlay(PendingApproval("execute_shell", "{}"))
            val out = overlay.render(60)
            // One of the body lines should carry the toolCall color.
            assertTrue(out.any { it.contains(codingTheme.toolCall) })
        }

        @Test
        fun `args body is displayed`() {
            val overlay = ApprovalOverlay(PendingApproval("x", "cmd: rm file.txt"))
            val out = overlay.render(60)
            val bodyText = out.drop(1).dropLast(1).joinToString("\n") { stripAnsi(it) }
            assertTrue(bodyText.contains("cmd: rm file.txt"))
        }

        @Test
        fun `long args are truncated`() {
            val longArgs = "x".repeat(500)
            val overlay = ApprovalOverlay(PendingApproval("t", longArgs))
            val out = overlay.render(40)
            // Each line must satisfy the width contract.
            for (line in out) {
                assertTrue(visibleWidth(line) <= 40)
            }
        }

        @Test
        fun `key legend contains y n and Esc in accent color`() {
            val overlay = ApprovalOverlay(PendingApproval("t", "{}"))
            val out = overlay.render(80)
            val legendLine = out[4] // 4th body line = last before bottom border
            val plain = stripAnsi(legendLine)
            assertTrue(plain.contains("[y]"))
            assertTrue(plain.contains("[n]"))
            assertTrue(plain.contains("[Esc]"))
            assertTrue(plain.contains("approve"))
            assertTrue(plain.contains("deny"))
            assertTrue(legendLine.contains(codingTheme.accent))
            assertTrue(legendLine.contains(codingTheme.dim))
        }
    }

    @Nested
    inner class Mutability {
        @Test
        fun `setApproval updates the displayed pending call`() {
            val overlay = ApprovalOverlay(PendingApproval("first_tool", "{}"))
            val firstBody = overlay.render(60).joinToString("\n") { stripAnsi(it) }
            assertTrue(firstBody.contains("first_tool"))

            overlay.setApproval(PendingApproval("second_tool", "{\"arg\":true}"))
            val secondBody = overlay.render(60).joinToString("\n") { stripAnsi(it) }
            assertTrue(secondBody.contains("second_tool"))
            assertTrue(!secondBody.contains("first_tool"))
        }
    }

    @Nested
    inner class WidthContract {
        @Test
        fun `width contract holds across widths`() {
            val overlay = ApprovalOverlay(PendingApproval("some_tool_name", "{\"long\":\"${"x".repeat(100)}\"}"))
            for (w in listOf(20, 30, 40, 60, 80, 120)) {
                for (line in overlay.render(w)) {
                    assertTrue(visibleWidth(line) <= w, "width $w: ${stripAnsi(line)}")
                }
            }
        }

        @Test
        fun `zero width returns empty`() {
            assertEquals(emptyList(), ApprovalOverlay(PendingApproval("t", "{}")).render(0))
        }
    }
}
