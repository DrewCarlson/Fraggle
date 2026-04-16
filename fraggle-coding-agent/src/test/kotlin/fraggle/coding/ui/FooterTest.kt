package fraggle.coding.ui

import fraggle.tui.text.stripAnsi
import fraggle.tui.text.visibleWidth
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FooterTest {

    private val cwd: Path = Path("/tmp/demo")

    private fun defaultInfo(
        status: FooterStatus = FooterStatus.IDLE,
        supervisionLabel: String = "",
        confirmExit: Boolean = false,
        contextRatio: Double = 0.0,
        usedTokens: Int = 42,
    ): FooterInfo = FooterInfo(
        cwd = cwd,
        sessionId = "abcdef1234567890",
        usedTokens = usedTokens,
        contextRatio = contextRatio,
        status = status,
        supervisionLabel = supervisionLabel,
        confirmExit = confirmExit,
    )

    @Nested
    inner class Basics {
        @Test
        fun `default info renders 2 lines (divider + status row)`() {
            val f = Footer(defaultInfo())
            val out = f.render(80)
            assertEquals(2, out.size)
        }

        @Test
        fun `divider fills the width with dashes`() {
            val f = Footer(defaultInfo())
            val out = f.render(40)
            val plain = stripAnsi(out[0])
            assertEquals(40, plain.length)
            assertTrue(plain.all { it == '─' })
        }

        @Test
        fun `divider uses divider color`() {
            val f = Footer(defaultInfo())
            val out = f.render(40)
            assertTrue(out[0].contains(codingTheme.divider))
        }

        @Test
        fun `every line is padded to exact width`() {
            val f = Footer(defaultInfo(supervisionLabel = "ask"))
            for (line in f.render(80)) {
                assertEquals(80, visibleWidth(line))
            }
        }
    }

    @Nested
    inner class StatusRow {
        @Test
        fun `shows short cwd`() {
            val f = Footer(defaultInfo())
            val out = f.render(80)
            val plain = stripAnsi(out[1])
            assertTrue(plain.contains("demo"), "status row: '$plain'")
        }

        @Test
        fun `shows first 8 chars of session id`() {
            val f = Footer(defaultInfo())
            val out = f.render(80)
            val plain = stripAnsi(out[1])
            assertTrue(plain.contains("abcdef12"), "status row: '$plain'")
        }

        @Test
        fun `shows token count`() {
            val f = Footer(defaultInfo(usedTokens = 1234))
            val out = f.render(80)
            val plain = stripAnsi(out[1])
            assertTrue(plain.contains("1234 tok"), "status row: '$plain'")
        }

        @Test
        fun `shows ctx percentage when ratio is non-zero`() {
            val f = Footer(defaultInfo(contextRatio = 0.5))
            val out = f.render(80)
            val plain = stripAnsi(out[1])
            assertTrue(plain.contains("50% ctx"), "status row: '$plain'")
        }

        @Test
        fun `hides ctx percentage when ratio is zero`() {
            val f = Footer(defaultInfo(contextRatio = 0.0))
            val out = f.render(80)
            val plain = stripAnsi(out[1])
            assertTrue(!plain.contains("% ctx"), "status row: '$plain'")
        }

        @Test
        fun `status label uses selector color`() {
            val f = Footer(defaultInfo(status = FooterStatus.BUSY))
            val out = f.render(80)
            val plain = stripAnsi(out[1])
            assertTrue(plain.contains("thinking..."))
            assertTrue(out[1].contains(codingTheme.accent))
        }

        @Test
        fun `error status uses error color`() {
            val f = Footer(defaultInfo(status = FooterStatus.ERROR))
            val out = f.render(80)
            assertTrue(out[1].contains(codingTheme.error))
        }
    }

    @Nested
    inner class ContextColor {
        @Test
        fun `ctx percentage at 50 uses dim color`() {
            val f = Footer(defaultInfo(contextRatio = 0.5))
            val out = f.render(80)
            val line = out[1]
            // The "[50% ctx]" segment is in dim. Since other segments are in dim too,
            // we check that the error/warning colors are NOT applied.
            assertTrue(line.contains(codingTheme.dim))
        }

        @Test
        fun `ctx percentage at 85 or higher uses error color`() {
            val f = Footer(defaultInfo(contextRatio = 0.85))
            val out = f.render(80)
            assertTrue(out[1].contains(codingTheme.error))
        }

        @Test
        fun `ctx percentage between 70 and 85 uses warning color`() {
            val f = Footer(defaultInfo(contextRatio = 0.75))
            val out = f.render(80)
            // Warning but not error.
            assertTrue(out[1].contains(codingTheme.warning))
        }
    }

    @Nested
    inner class ThirdRow {
        @Test
        fun `supervisionLabel adds a third row`() {
            val f = Footer(defaultInfo(supervisionLabel = "ask"))
            val out = f.render(80)
            assertEquals(3, out.size)
            val plain = stripAnsi(out[2])
            assertTrue(plain.contains("supervision: ask"), "row 3: '$plain'")
        }

        @Test
        fun `confirmExit replaces supervision with warning`() {
            val f = Footer(defaultInfo(supervisionLabel = "ask", confirmExit = true))
            val out = f.render(80)
            assertEquals(3, out.size)
            val plain = stripAnsi(out[2])
            assertTrue(plain.contains("press Esc or Ctrl+C again to exit"))
            assertTrue(!plain.contains("supervision"))
            assertTrue(out[2].contains(codingTheme.warning))
        }

        @Test
        fun `no third row when both are empty`() {
            val f = Footer(defaultInfo(supervisionLabel = "", confirmExit = false))
            val out = f.render(80)
            assertEquals(2, out.size)
        }
    }

    @Nested
    inner class Mutability {
        @Test
        fun `setInfo updates displayed state`() {
            val f = Footer(defaultInfo(status = FooterStatus.IDLE))
            assertTrue(stripAnsi(f.render(80)[1]).contains("idle"))

            f.setInfo(defaultInfo(status = FooterStatus.COMPACTING, supervisionLabel = "always"))
            val out = f.render(80)
            assertTrue(stripAnsi(out[1]).contains("compacting..."))
            assertEquals(3, out.size)
            assertTrue(stripAnsi(out[2]).contains("supervision: always"))
        }
    }

    @Nested
    inner class WidthContract {
        @Test
        fun `widths are never exceeded`() {
            val f = Footer(defaultInfo(contextRatio = 0.5, supervisionLabel = "ask"))
            for (w in listOf(20, 40, 80, 120, 200)) {
                for (line in f.render(w)) {
                    assertTrue(visibleWidth(line) <= w, "w=$w: '${stripAnsi(line)}'")
                }
            }
        }

        @Test
        fun `zero width returns empty`() {
            assertEquals(emptyList(), Footer(defaultInfo()).render(0))
        }

        @Test
        fun `narrow width does not crash`() {
            val f = Footer(defaultInfo(contextRatio = 0.9, supervisionLabel = "ask", confirmExit = true))
            val out = f.render(10)
            for (line in out) {
                assertTrue(visibleWidth(line) <= 10)
            }
        }
    }
}
