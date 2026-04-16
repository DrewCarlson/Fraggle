package fraggle.coding.ui

import fraggle.tui.text.Ansi
import fraggle.tui.text.stripAnsi
import fraggle.tui.text.visibleWidth
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeaderBannerTest {

    @Nested
    inner class Basics {
        @Test
        fun `renders exactly two lines`() {
            val banner = HeaderBanner(HeaderInfo(model = "mistral-7b", contextFileCount = 2))
            val out = banner.render(80)
            assertEquals(2, out.size)
        }

        @Test
        fun `both lines exactly match width`() {
            val banner = HeaderBanner(HeaderInfo(model = "m", contextFileCount = 1))
            val out = banner.render(60)
            for (line in out) {
                assertEquals(60, visibleWidth(line), "line '$line' has width ${visibleWidth(line)}")
            }
        }

        @Test
        fun `title line starts with the fraggle-code label`() {
            val banner = HeaderBanner(HeaderInfo(model = "m", contextFileCount = 0))
            val out = banner.render(60)
            assertTrue(
                stripAnsi(out[0]).startsWith("── fraggle code"),
                "title line was '${stripAnsi(out[0])}'",
            )
        }

        @Test
        fun `title line contains accent color`() {
            val banner = HeaderBanner(HeaderInfo(model = "m", contextFileCount = 0))
            val out = banner.render(60)
            assertTrue(out[0].contains(codingTheme.accent))
            assertTrue(out[0].contains(Ansi.RESET))
        }
    }

    @Nested
    inner class HintLine {
        @Test
        fun `hint line starts with two spaces and hotkeys label`() {
            val banner = HeaderBanner(HeaderInfo(model = "m", contextFileCount = 0))
            val out = banner.render(80)
            assertTrue(stripAnsi(out[1]).startsWith("  ?: /hotkeys"))
        }

        @Test
        fun `includes context file count with plural`() {
            val banner = HeaderBanner(HeaderInfo(model = "m", contextFileCount = 3))
            val out = banner.render(80)
            val plain = stripAnsi(out[1])
            assertTrue(plain.contains("3 context files"), "got '$plain'")
        }

        @Test
        fun `singular form for one context file`() {
            val banner = HeaderBanner(HeaderInfo(model = "m", contextFileCount = 1))
            val out = banner.render(80)
            val plain = stripAnsi(out[1])
            assertTrue(plain.contains("1 context file"), "got '$plain'")
            assertFalse(plain.contains("1 context files"))
        }

        @Test
        fun `omits context count when zero`() {
            val banner = HeaderBanner(HeaderInfo(model = "m", contextFileCount = 0))
            val out = banner.render(80)
            val plain = stripAnsi(out[1])
            assertFalse(plain.contains("context"), "got '$plain'")
        }

        @Test
        fun `includes model name`() {
            val banner = HeaderBanner(HeaderInfo(model = "llama-3.1", contextFileCount = 0))
            val out = banner.render(80)
            val plain = stripAnsi(out[1])
            assertTrue(plain.contains("llama-3.1"), "got '$plain'")
        }

        @Test
        fun `truncates very long model name with ellipsis`() {
            val banner = HeaderBanner(HeaderInfo(model = "a-really-really-really-long-model-name-forever", contextFileCount = 0))
            val out = banner.render(30)
            assertEquals(30, visibleWidth(out[1]))
            assertTrue(stripAnsi(out[1]).contains("…"))
        }

        @Test
        fun `uses bullet separator between parts`() {
            val banner = HeaderBanner(HeaderInfo(model = "m", contextFileCount = 2))
            val out = banner.render(80)
            assertTrue(stripAnsi(out[1]).contains("•"))
        }
    }

    @Nested
    inner class Mutability {
        @Test
        fun `setInfo swaps the displayed info`() {
            val banner = HeaderBanner(HeaderInfo(model = "old", contextFileCount = 0))
            assertTrue(stripAnsi(banner.render(80)[1]).contains("old"))
            banner.setInfo(HeaderInfo(model = "new", contextFileCount = 5))
            val plain = stripAnsi(banner.render(80)[1])
            assertTrue(plain.contains("new"))
            assertTrue(plain.contains("5 context files"))
        }
    }

    @Nested
    inner class WidthContract {
        @Test
        fun `line widths never exceed width at varied widths`() {
            val widths = listOf(10, 20, 40, 60, 80, 120, 200)
            val banner = HeaderBanner(HeaderInfo(model = "mistral", contextFileCount = 3))
            for (w in widths) {
                for (line in banner.render(w)) {
                    assertTrue(visibleWidth(line) <= w, "width $w, line width ${visibleWidth(line)}")
                }
            }
        }

        @Test
        fun `zero width renders no lines`() {
            val banner = HeaderBanner(HeaderInfo(model = "m", contextFileCount = 0))
            assertEquals(emptyList(), banner.render(0))
        }

        @Test
        fun `tiny width does not crash`() {
            val banner = HeaderBanner(HeaderInfo(model = "very-long-name", contextFileCount = 100))
            val out = banner.render(5)
            // Both lines must satisfy the width contract.
            for (line in out) {
                assertTrue(visibleWidth(line) <= 5)
            }
        }
    }
}
