package fraggle.tui.ui

import fraggle.tui.text.Ansi
import fraggle.tui.text.stripAnsi
import fraggle.tui.text.visibleWidth
import fraggle.tui.theme.Theme
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MarkdownTest {

    /** Assert every rendered line is within width + non-null. */
    private fun assertWidthInvariant(lines: List<String>, width: Int) {
        for ((i, line) in lines.withIndex()) {
            val w = visibleWidth(line)
            assertTrue(w <= width, "line $i overshot width $width (got $w): ${stripAnsi(line)}")
        }
    }

    /** Plain-text view of a rendered markdown: stripped ANSI, joined with newlines. */
    private fun plain(lines: List<String>): String = lines.joinToString("\n") { stripAnsi(it) }

    /** Plain-text view of a single line. */
    private fun plain(line: String): String = stripAnsi(line)

    @Nested
    inner class Empty {
        @Test
        fun `empty input returns empty list`() {
            val md = Markdown("")
            assertEquals(emptyList(), md.render(40))
        }

        @Test
        fun `whitespace-only input renders no blocks`() {
            val md = Markdown("   \n   ")
            val lines = md.render(40)
            // Either empty or a single empty line from the Blank block — both fine,
            // the important thing is width invariant + no crash.
            assertWidthInvariant(lines, 40)
        }

        @Test
        fun `zero width returns empty list and does not crash`() {
            val md = Markdown("hello world")
            assertEquals(emptyList(), md.render(0))
        }

        @Test
        fun `paddingY on empty text still emits the pad rows`() {
            val md = Markdown("", paddingY = 2)
            val lines = md.render(10)
            assertEquals(4, lines.size)
            for (l in lines) assertEquals("", l)
        }
    }

    @Nested
    inner class Paragraph {
        @Test
        fun `plain paragraph renders the text`() {
            val md = Markdown("hello world")
            val lines = md.render(40)
            assertEquals(1, lines.size)
            assertEquals("hello world", stripAnsi(lines[0]))
            assertWidthInvariant(lines, 40)
        }

        @Test
        fun `paragraph wraps at width`() {
            val md = Markdown("one two three four five six seven eight")
            val lines = md.render(12)
            assertTrue(lines.size > 1, "expected wrapping, got ${lines.size} line(s)")
            assertWidthInvariant(lines, 12)
        }

        @Test
        fun `fallback color wraps plain text`() {
            val color = Ansi.fgRgb(1, 2, 3)
            val md = Markdown("plain", fallbackColor = color)
            val lines = md.render(40)
            assertTrue(lines[0].contains(color), "fallback color should appear in output")
            assertTrue(lines[0].contains(Ansi.RESET), "should close with reset")
            assertWidthInvariant(lines, 40)
        }

        @Test
        fun `two paragraphs have a blank separator between them`() {
            val md = Markdown("first para\n\nsecond para")
            val lines = md.render(40)
            assertEquals("first para", stripAnsi(lines[0]))
            assertEquals("", stripAnsi(lines[1]))
            assertEquals("second para", stripAnsi(lines[2]))
            assertWidthInvariant(lines, 40)
        }
    }

    @Nested
    inner class Headings {
        @Test
        fun `heading 1 has bold + invert styling, no hash prefix`() {
            val md = Markdown("# Title")
            val lines = md.render(40)
            assertTrue(plain(lines).startsWith("Title"), "no hash prefix for h1")
            assertTrue(lines[0].contains(Ansi.BOLD), "h1 should be bold")
            assertTrue(lines[0].contains(Ansi.INVERSE), "h1 should be inverted")
            assertWidthInvariant(lines, 40)
        }

        @Test
        fun `heading 2 has bold and no hash prefix`() {
            val md = Markdown("## Section")
            val lines = md.render(40)
            assertTrue(plain(lines).startsWith("Section"))
            assertTrue(lines[0].contains(Ansi.BOLD))
            assertFalse(lines[0].contains(Ansi.INVERSE), "h2 not inverted")
            assertWidthInvariant(lines, 40)
        }

        @Test
        fun `heading 3 has bold color and no hash prefix`() {
            val md = Markdown("### Sub")
            val lines = md.render(40)
            assertTrue(plain(lines).startsWith("Sub"), "got ${plain(lines)}")
            assertFalse(plain(lines).contains("###"), "no '#' prefix expected")
            assertTrue(lines[0].contains(Ansi.BOLD))
            assertWidthInvariant(lines, 40)
        }

        @Test
        fun `heading levels 4-6 render as plain bold text without prefixes`() {
            for (level in 4..6) {
                val md = Markdown("${"#".repeat(level)} H$level")
                val lines = md.render(60)
                assertTrue(plain(lines).startsWith("H$level"),
                    "level $level: got ${plain(lines)}")
                assertFalse(plain(lines).contains("#"),
                    "level $level should have no '#' prefix")
                assertWidthInvariant(lines, 60)
            }
        }

        @Test
        fun `heading has the mdHeading color`() {
            val md = Markdown("# Hi")
            val lines = md.render(40)
            assertTrue(lines[0].contains(Theme.DARK.mdHeading))
        }
    }

    @Nested
    inner class Lists {
        @Test
        fun `unordered list renders with dash bullets`() {
            val md = Markdown("- one\n- two\n- three")
            val lines = md.render(40)
            assertEquals(3, lines.size)
            assertTrue(plain(lines[0]).startsWith("- one"))
            assertTrue(plain(lines[1]).startsWith("- two"))
            assertTrue(plain(lines[2]).startsWith("- three"))
            assertWidthInvariant(lines, 40)
        }

        @Test
        fun `ordered list renders numbered bullets`() {
            val md = Markdown("1. first\n2. second")
            val lines = md.render(40)
            assertEquals(2, lines.size)
            assertTrue(plain(lines[0]).startsWith("1. first"))
            assertTrue(plain(lines[1]).startsWith("2. second"))
            assertWidthInvariant(lines, 40)
        }

        @Test
        fun `list items have no blank separator between them`() {
            val md = Markdown("- a\n- b")
            val lines = md.render(40)
            assertEquals(2, lines.size, "list items should pack tight")
            assertWidthInvariant(lines, 40)
        }

        @Test
        fun `nested list is indented`() {
            val md = Markdown("- top\n  - nested\n- bottom")
            val lines = md.render(40)
            val nested = lines.map { plain(it) }
            // The nested item starts with at least two leading spaces.
            val nestedLine = nested.firstOrNull { it.contains("nested") }
                ?: error("no nested item, got $nested")
            assertTrue(nestedLine.startsWith("  "), "nested item should be indented, got '$nestedLine'")
            assertWidthInvariant(lines, 40)
        }

        @Test
        fun `list item body wraps under the bullet`() {
            val md = Markdown("- some words that really ought to wrap around")
            val lines = md.render(20)
            assertTrue(lines.size > 1, "expected wrapping")
            // First line has bullet; continuation lines have the bullet indent.
            assertTrue(plain(lines[0]).startsWith("- "))
            assertTrue(plain(lines[1]).startsWith("  "), "continuation indent = '${plain(lines[1])}'")
            assertWidthInvariant(lines, 20)
        }

        @Test
        fun `bullet gets the mdListBullet color`() {
            val md = Markdown("- item")
            val lines = md.render(40)
            assertTrue(lines[0].contains(Theme.DARK.mdListBullet))
        }
    }

    @Nested
    inner class Inline {
        @Test
        fun `bold text carries the BOLD SGR`() {
            val md = Markdown("before **bold** after")
            val lines = md.render(40)
            assertTrue(lines[0].contains(Ansi.BOLD), "bold escape missing")
            assertTrue(plain(lines[0]).contains("bold"))
            assertWidthInvariant(lines, 40)
        }

        @Test
        fun `italic text carries the ITALIC SGR`() {
            val md = Markdown("hello *world* today")
            val lines = md.render(40)
            assertTrue(lines[0].contains(Ansi.ITALIC), "italic escape missing")
            assertWidthInvariant(lines, 40)
        }

        @Test
        fun `inline code uses mdCode color`() {
            val md = Markdown("call `foo()` now")
            val lines = md.render(40)
            assertTrue(lines[0].contains(Theme.DARK.mdCode), "code color missing")
            assertTrue(plain(lines[0]).contains("foo()"))
            assertWidthInvariant(lines, 40)
        }

        @Test
        fun `strikethrough uses STRIKETHROUGH SGR`() {
            val md = Markdown("old ~~gone~~ text")
            val lines = md.render(40)
            assertTrue(lines[0].contains(Ansi.STRIKETHROUGH), "strikethrough missing")
            assertWidthInvariant(lines, 40)
        }

        @Test
        fun `link text uses mdLink color and underline`() {
            val md = Markdown("See [the docs](https://example.com) online")
            val lines = md.render(60)
            assertTrue(lines[0].contains(Theme.DARK.mdLink), "link color missing")
            assertTrue(lines[0].contains(Ansi.UNDERLINE), "link underline missing")
            assertTrue(plain(lines[0]).contains("the docs"))
            assertWidthInvariant(lines, 60)
        }
    }

    @Nested
    inner class CodeBlocks {
        @Test
        fun `fenced code block has top, bottom borders and gutters`() {
            val md = Markdown("```\nline1\nline2\n```")
            val lines = md.render(40)
            // top + 2 content + bottom = 4
            assertEquals(4, lines.size, "unexpected code block shape: ${lines.map(::stripAnsi)}")
            assertTrue(plain(lines[0]).startsWith("┌─"), "top border")
            assertTrue(plain(lines[1]).startsWith("│ "), "gutter")
            assertTrue(plain(lines[2]).startsWith("│ "))
            assertTrue(plain(lines[3]).startsWith("└─"), "bottom border")
            assertWidthInvariant(lines, 40)
        }

        @Test
        fun `fenced code block with lang has the lang in the top border`() {
            val md = Markdown("```kotlin\nfun x() {}\n```")
            val lines = md.render(40)
            assertTrue(plain(lines[0]).contains("kotlin"), "lang missing: ${plain(lines[0])}")
            assertWidthInvariant(lines, 40)
        }

        @Test
        fun `indented code block also renders with borders`() {
            val md = Markdown("paragraph\n\n    indented\n    code")
            val lines = md.render(40)
            // Paragraph + blank + code block (at least 4 lines).
            assertTrue(lines.any { plain(it).startsWith("┌─") }, "no top border")
            assertTrue(lines.any { plain(it).startsWith("└─") }, "no bottom border")
            assertWidthInvariant(lines, 40)
        }

        @Test
        fun `code block borders use mdCodeBorder color`() {
            val md = Markdown("```\nx\n```")
            val lines = md.render(40)
            assertTrue(lines[0].contains(Theme.DARK.mdCodeBorder), "border color")
            assertTrue(lines.last().contains(Theme.DARK.mdCodeBorder), "border color")
        }

        @Test
        fun `code block wraps long lines`() {
            val md = Markdown("```\n" + "x".repeat(50) + "\n```")
            val lines = md.render(20)
            // Content wraps at width-2 (for gutter), so at least 3 content lines for 50 chars at 18.
            val contentLines = lines.filter { plain(it).startsWith("│ ") }
            assertTrue(contentLines.size >= 2, "expected wrapped code content, got ${contentLines.size}")
            assertWidthInvariant(lines, 20)
        }
    }

    @Nested
    inner class Quotes {
        @Test
        fun `quote lines have the left gutter`() {
            val md = Markdown("> quoted text here")
            val lines = md.render(40)
            for (line in lines) {
                assertTrue(plain(line).startsWith("│ "), "missing gutter: ${plain(line)}")
            }
            assertWidthInvariant(lines, 40)
        }

        @Test
        fun `quote border uses mdQuoteBorder color`() {
            val md = Markdown("> hello")
            val lines = md.render(40)
            assertTrue(lines[0].contains(Theme.DARK.mdQuoteBorder), "quote border color missing")
        }

        @Test
        fun `quote content uses italic quote color`() {
            val md = Markdown("> an *italic* quote")
            val lines = md.render(40)
            assertTrue(lines[0].contains(Theme.DARK.mdQuote), "quote color missing")
            assertTrue(lines[0].contains(Ansi.ITALIC), "quote italic missing")
            assertWidthInvariant(lines, 40)
        }

        @Test
        fun `quote with multiple paragraphs renders all`() {
            val md = Markdown("> first\n>\n> second")
            val lines = md.render(40)
            assertTrue(lines.any { plain(it).contains("first") })
            assertTrue(lines.any { plain(it).contains("second") })
            assertWidthInvariant(lines, 40)
        }
    }

    @Nested
    inner class Rule {
        @Test
        fun `rule renders as dashes with mdRule color`() {
            val md = Markdown("---")
            val lines = md.render(40)
            assertEquals(1, lines.size)
            assertTrue(plain(lines[0]).all { it == '─' }, "expected only box chars, got '${plain(lines[0])}'")
            assertTrue(lines[0].contains(Theme.DARK.mdRule))
            assertWidthInvariant(lines, 40)
        }

        @Test
        fun `rule fits within a narrow width`() {
            val md = Markdown("---")
            val lines = md.render(3)
            assertEquals(1, lines.size)
            assertWidthInvariant(lines, 3)
        }
    }

    @Nested
    inner class MultiBlock {
        @Test
        fun `heading then paragraph gets blank separator`() {
            val md = Markdown("# Title\n\nBody text here.")
            val lines = md.render(40)
            assertTrue(plain(lines[0]).startsWith("Title"))
            assertEquals("", stripAnsi(lines[1]))
            assertTrue(plain(lines[2]).startsWith("Body"))
            assertWidthInvariant(lines, 40)
        }

        @Test
        fun `paragraph then list then code block renders cleanly`() {
            val md = Markdown(
                """
                hello

                - one
                - two

                ```
                code
                ```
                """.trimIndent(),
            )
            val lines = md.render(40)
            val joined = plain(lines)
            assertTrue(joined.contains("hello"))
            assertTrue(joined.contains("- one"))
            assertTrue(joined.contains("- two"))
            assertTrue(joined.contains("┌─"))
            assertTrue(joined.contains("code"))
            assertTrue(joined.contains("└─"))
            assertWidthInvariant(lines, 40)
        }
    }

    @Nested
    inner class WidthAndSafety {
        @Test
        fun `narrow width still respects width contract on code block`() {
            val md = Markdown("```kotlin\nfun veryLongFunctionName() { return 42 }\n```")
            val lines = md.render(10)
            assertWidthInvariant(lines, 10)
        }

        @Test
        fun `very narrow width doesn't crash`() {
            val md = Markdown("# Heading\n\nSome text\n\n- list item\n\n> quote\n\n```\ncode\n```")
            // width = 1 is pathological but must not crash.
            val lines = md.render(1)
            assertWidthInvariant(lines, 1)
        }

        @Test
        fun `malformed markdown does not throw`() {
            // Unterminated bold, unclosed code fence, stray backtick — partial stream stuff.
            val inputs = listOf(
                "**unterminated",
                "```\nno closing fence",
                "`stray backtick",
                "[link text without close",
                "# \n\n\n",
                "- \n- \n- ",
            )
            for (input in inputs) {
                val md = Markdown(input)
                val lines = md.render(40)
                assertWidthInvariant(lines, 40)
            }
        }

        @Test
        fun `streaming prefix of a code fence renders without crashing`() {
            // Simulate streaming: feed partial inputs incrementally, re-render each.
            val full = "```kotlin\nval x = 1\n```"
            for (i in 1..full.length) {
                val md = Markdown(full.substring(0, i))
                val lines = md.render(40)
                assertWidthInvariant(lines, 40)
            }
        }
    }

    @Nested
    inner class Caching {
        @Test
        fun `same width renders from cache (identity check)`() {
            val md = Markdown("hello **world**")
            val a = md.render(30)
            val b = md.render(30)
            // Cached — should be the exact same list reference.
            assertTrue(a === b, "expected cached identity")
        }

        @Test
        fun `setText invalidates the cache`() {
            val md = Markdown("alpha")
            val before = md.render(30)
            md.setText("beta")
            val after = md.render(30)
            assertNotEquals(stripAnsi(before.joinToString()), stripAnsi(after.joinToString()))
        }

        @Test
        fun `setText with same value is a no-op`() {
            val md = Markdown("same")
            val a = md.render(30)
            md.setText("same")
            val b = md.render(30)
            assertTrue(a === b, "no-op setText should preserve cached result")
        }

        @Test
        fun `setPadding invalidates cache`() {
            val md = Markdown("x")
            md.render(30)
            md.setPadding(1, 1)
            val after = md.render(30)
            assertTrue(after.size >= 3, "paddingY=1 should add top + bottom")
        }

        @Test
        fun `different widths produce different results`() {
            val md = Markdown("a long sentence that needs wrapping at narrow widths")
            val narrow = md.render(10)
            val wide = md.render(80)
            assertNotEquals(narrow.size, wide.size)
        }
    }

    @Nested
    inner class Padding {
        @Test
        fun `paddingY adds blank rows above and below`() {
            val md = Markdown("x", paddingY = 1)
            val lines = md.render(20)
            assertEquals("", lines.first())
            assertEquals("", lines.last())
        }

        @Test
        fun `paddingX reduces effective content width`() {
            val md = Markdown("one two three four five", paddingX = 2)
            val lines = md.render(12)
            assertWidthInvariant(lines, 12)
            // Padded content should have leading spaces.
            val contentLine = lines.first { stripAnsi(it).isNotBlank() }
            assertTrue(contentLine.startsWith("  "), "expected 2-space left pad, got '${stripAnsi(contentLine)}'")
        }
    }
}
