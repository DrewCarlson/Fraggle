package fraggle.tui.ui

import fraggle.tui.text.visibleWidth
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LoaderTest {

    @Nested
    inner class WidthContract {
        @Test
        fun `single line rendered exactly width cells wide`() {
            val loader = Loader(label = "Loading...")
            for (w in listOf(1, 5, 10, 40, 80, 200)) {
                val lines = loader.render(w)
                assertEquals(1, lines.size, "width=$w should produce one line")
                assertEquals(w, visibleWidth(lines[0]), "width=$w should be exactly $w cells")
            }
        }

        @Test
        fun `zero or negative width produces empty output`() {
            val loader = Loader()
            assertEquals(emptyList(), loader.render(0))
            assertEquals(emptyList(), loader.render(-4))
        }

        @Test
        fun `narrow width frame-only clamps to width and pads`() {
            // If width == 1, the frame (single cell) fills the viewport exactly.
            val loader = Loader(label = "anything", frames = listOf("X"))
            val lines = loader.render(1)
            assertEquals(1, lines.size)
            assertEquals(1, visibleWidth(lines[0]))
        }
    }

    @Nested
    inner class FrameCycling {
        @Test
        fun `tick advances to next frame`() {
            val frames = listOf("A", "B", "C")
            val loader = Loader(label = "", frames = frames)
            assertEquals(0, loader.frame)

            loader.tick()
            assertEquals(1, loader.frame)

            loader.tick()
            assertEquals(2, loader.frame)
        }

        @Test
        fun `frame wraps at end of list`() {
            val frames = listOf("A", "B", "C")
            val loader = Loader(label = "", frames = frames)
            loader.tick(); loader.tick(); loader.tick()
            assertEquals(0, loader.frame, "should wrap back to 0")

            loader.tick()
            assertEquals(1, loader.frame)
        }

        @Test
        fun `rendered output reflects current frame`() {
            val loader = Loader(label = "hi", frames = listOf("A", "B", "C"))
            val first = loader.render(10)[0]
            loader.tick()
            val second = loader.render(10)[0]
            assertNotEquals(first, second)
            assertTrue(first.startsWith("A"), "expected frame A, got: $first")
            assertTrue(second.startsWith("B"), "expected frame B, got: $second")
        }

        @Test
        fun `default braille frames are a non-empty list`() {
            assertTrue(Loader.DEFAULT_FRAMES.isNotEmpty())
            assertEquals(10, Loader.DEFAULT_FRAMES.size)
        }

        @Test
        fun `DOTS frames cycle ASCII dots`() {
            assertEquals(listOf("   ", ".  ", ".. ", "..."), Loader.DOTS)
        }
    }

    @Nested
    inner class LabelHandling {
        @Test
        fun `label renders after frame and a separating space`() {
            val loader = Loader(label = "hello", frames = listOf("X"))
            val line = loader.render(20)[0]
            // The line starts with "X hello" then padding.
            assertTrue(line.startsWith("X hello"), "expected 'X hello' prefix; got '$line'")
        }

        @Test
        fun `label longer than available space is truncated`() {
            val loader = Loader(label = "a".repeat(100), frames = listOf("X"))
            val line = loader.render(10)[0]
            // Line still exactly 10 cells.
            assertEquals(10, visibleWidth(line))
            // Should contain an ellipsis from truncateToWidth.
            assertTrue(line.contains('…'), "expected ellipsis in truncated label; got '$line'")
        }

        @Test
        fun `setLabel replaces the displayed label`() {
            val loader = Loader(label = "one", frames = listOf("X"))
            val before = loader.render(20)[0]
            loader.setLabel("two")
            val after = loader.render(20)[0]
            assertNotEquals(before, after)
            assertTrue(after.startsWith("X two"))
        }

        @Test
        fun `empty label renders just frame plus padding`() {
            val loader = Loader(label = "", frames = listOf("X"))
            val line = loader.render(5)[0]
            assertEquals(5, visibleWidth(line))
            assertTrue(line.startsWith("X "))
        }
    }

    @Nested
    inner class Mutation {
        @Test
        fun `setFrames swaps the cycle and resets frame index`() {
            val loader = Loader(label = "", frames = listOf("A", "B", "C"))
            loader.tick(); loader.tick()
            assertEquals(2, loader.frame)

            loader.setFrames(listOf("X", "Y"))
            assertEquals(0, loader.frame)

            val line = loader.render(5)[0]
            assertTrue(line.startsWith("X"))
        }

        @Test
        fun `setFrames with empty list falls back to default`() {
            val loader = Loader(frames = listOf("X"))
            loader.setFrames(emptyList())
            // Should still render something — the default frames fill in.
            val line = loader.render(10)[0]
            assertEquals(10, visibleWidth(line))
        }

        @Test
        fun `setColor wraps the output with ansi codes`() {
            val loader = Loader(label = "hi", frames = listOf("X"))
            val uncolored = loader.render(10)[0]
            loader.setColor("\u001B[31m")
            val colored = loader.render(10)[0]
            assertNotEquals(uncolored, colored)
            // Still exactly 10 visible cells.
            assertEquals(10, visibleWidth(colored))
            assertTrue(colored.startsWith("\u001B[31m"))
        }

        @Test
        fun `constructor color applies on first render`() {
            val loader = Loader(label = "hi", frames = listOf("X"), color = "\u001B[32m")
            val line = loader.render(10)[0]
            assertTrue(line.startsWith("\u001B[32m"))
            assertEquals(10, visibleWidth(line))
        }
    }

    @Nested
    inner class Constructor {
        @Test
        fun `empty frames in constructor fall back to default`() {
            val loader = Loader(label = "x", frames = emptyList())
            val line = loader.render(10)[0]
            assertEquals(10, visibleWidth(line))
        }
    }
}
