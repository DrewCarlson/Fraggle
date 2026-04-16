package fraggle.tui.layout

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpacerTest {

    @Nested
    inner class LineCount {
        @Test
        fun `default constructor renders one empty line`() {
            val spacer = Spacer()
            assertEquals(listOf(""), spacer.render(10))
        }

        @Test
        fun `zero lines renders empty output`() {
            val spacer = Spacer(0)
            assertEquals(emptyList(), spacer.render(10))
        }

        @Test
        fun `many lines renders that many empty strings`() {
            val spacer = Spacer(5)
            val lines = spacer.render(80)
            assertEquals(5, lines.size)
            assertTrue(lines.all { it.isEmpty() })
        }

        @Test
        fun `negative lines is clamped to zero`() {
            val spacer = Spacer(-3)
            assertEquals(emptyList(), spacer.render(10))
        }
    }

    @Nested
    inner class Width {
        @Test
        fun `rendered lines are empty strings regardless of width`() {
            val spacer = Spacer(2)
            for (w in listOf(1, 10, 80, 1000)) {
                val lines = spacer.render(w)
                assertEquals(2, lines.size)
                assertTrue(lines.all { it.isEmpty() }, "width=$w produced non-empty lines")
            }
        }
    }

    @Nested
    inner class Mutation {
        @Test
        fun `setLines updates the count`() {
            val spacer = Spacer(1)
            spacer.setLines(4)
            assertEquals(4, spacer.render(10).size)
        }

        @Test
        fun `setLines negative clamps to zero`() {
            val spacer = Spacer(3)
            spacer.setLines(-1)
            assertEquals(emptyList(), spacer.render(10))
        }
    }
}
