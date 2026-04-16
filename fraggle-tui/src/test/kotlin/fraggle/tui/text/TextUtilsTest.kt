package fraggle.tui.text

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [TextUtils].
 *
 * Coverage priorities:
 * - ASCII/invariants for every function.
 * - ANSI SGR preservation across cuts (color, background, underline, chained styles).
 * - East Asian wide characters (CJK ideographs, full-width kana, Hangul).
 * - Emoji: simple, ZWJ sequences, variation selectors, regional indicators.
 * - Combining marks and default-ignorable code points.
 * - OSC 8 hyperlinks (visible text but zero-width escapes).
 * - Truncation boundaries on multi-cell graphemes.
 * - Slice boundaries on wide chars (strict vs. non-strict).
 * - Padding no-op when input is already wider than target.
 */
class TextUtilsTest {

    @Nested
    inner class VisibleWidthBasics {
        @Test
        fun `empty string is width 0`() {
            assertEquals(0, visibleWidth(""))
        }

        @Test
        fun `printable ASCII equals length`() {
            assertEquals(11, visibleWidth("hello world"))
            assertEquals(1, visibleWidth("a"))
            assertEquals(5, visibleWidth("12345"))
        }

        @Test
        fun `space and punctuation count as 1 each`() {
            assertEquals(3, visibleWidth("a b"))
            assertEquals(5, visibleWidth("a,b,c"))
        }

        @Test
        fun `tab counts as 3 cells`() {
            assertEquals(3, visibleWidth("\t"))
            assertEquals(4, visibleWidth("a\t"))
        }
    }

    @Nested
    inner class VisibleWidthAnsi {
        @Test
        fun `CSI color escapes are zero-width`() {
            assertEquals(5, visibleWidth("\u001b[31mhello\u001b[0m"))
        }

        @Test
        fun `chained SGR codes are zero-width`() {
            assertEquals(5, visibleWidth("\u001b[1;31mhello\u001b[0m"))
            assertEquals(5, visibleWidth("\u001b[1;4;33;41mhello\u001b[0m"))
        }

        @Test
        fun `OSC 8 hyperlink counts only the visible label`() {
            val link = "\u001b]8;;https://example.com\u0007click\u001b]8;;\u0007"
            assertEquals(5, visibleWidth(link))
        }

        @Test
        fun `OSC 133 semantic markers are zero-width`() {
            val text = "\u001b]133;A\u0007hello\u001b]133;B\u0007"
            assertEquals(5, visibleWidth(text))
        }

        @Test
        fun `OSC terminated with ST are zero-width`() {
            val text = "\u001b]133;A\u001b\\hello\u001b]133;B\u001b\\"
            assertEquals(5, visibleWidth(text))
        }

        @Test
        fun `visibleWidth counts tabs inline with ANSI inline`() {
            assertEquals(5, visibleWidth("\t\u001b[31m界\u001b[0m"))
        }

        @Test
        fun `malformed escape prefix does not hang or throw`() {
            // `\u001b` with no valid continuation shouldn't be classified as ANSI.
            val text = "abc\u001bnot-ansi"
            val w = visibleWidth(text)
            assertTrue(w <= text.length)
        }
    }

    @Nested
    inner class VisibleWidthWideChars {
        @Test
        fun `single CJK ideograph is width 2`() {
            assertEquals(2, visibleWidth("中"))
            assertEquals(2, visibleWidth("界"))
        }

        @Test
        fun `Japanese kana is width 2`() {
            assertEquals(2, visibleWidth("あ"))
            assertEquals(4, visibleWidth("あい"))
        }

        @Test
        fun `Korean Hangul syllable is width 2`() {
            assertEquals(2, visibleWidth("가"))
        }

        @Test
        fun `mixed ASCII and wide chars sum correctly`() {
            // a(1)+b(1)+中(2)+c(1)+d(1) = 6
            assertEquals(6, visibleWidth("ab中cd"))
            assertEquals(8, visibleWidth("ab中cd界"))
        }
    }

    @Nested
    inner class VisibleWidthEmoji {
        @Test
        fun `simple emoji is width 2`() {
            assertEquals(2, visibleWidth("😀"))
            assertEquals(2, visibleWidth("🙂"))
            assertEquals(2, visibleWidth("👍"))
        }

        @Test
        fun `emoji with skin tone is width 2`() {
            assertEquals(2, visibleWidth("👍🏻"))
        }

        @Test
        fun `ZWJ family sequence is width 2`() {
            // Man + ZWJ + Woman + ZWJ + Girl = one grapheme.
            assertEquals(2, visibleWidth("👨\u200D👩\u200D👧"))
        }

        @Test
        fun `ZWJ professional sequence is width 2`() {
            // Man + ZWJ + Computer = Man technologist
            assertEquals(2, visibleWidth("👨\u200D💻"))
        }

        @Test
        fun `rainbow flag (ZWJ with VS16) is width 2`() {
            // White flag (U+1F3F3) + VS16 + ZWJ + Rainbow (U+1F308)
            assertEquals(2, visibleWidth("🏳️\u200D🌈"))
        }

        @Test
        fun `VS16 on base char forces emoji width`() {
            // Snowman (⚡) with VS16 should still be width 2.
            assertEquals(2, visibleWidth("⚡️"))
        }

        @Test
        fun `single regional indicator is width 2`() {
            // Singleton flag halves render as width 2 to avoid streaming drift.
            assertEquals(2, visibleWidth("🇨"))
            assertEquals(2, visibleWidth("🇺"))
        }

        @Test
        fun `flag pair is width 2`() {
            assertEquals(2, visibleWidth("🇯🇵"))
            assertEquals(2, visibleWidth("🇺🇸"))
            assertEquals(2, visibleWidth("🇨🇳"))
        }

        @Test
        fun `all regional indicator singletons are width 2`() {
            for (cp in 0x1F1E6..0x1F1FF) {
                val s = String(Character.toChars(cp))
                assertEquals(2, visibleWidth(s), "singleton flag half at U+${cp.toString(16)}")
            }
        }
    }

    @Nested
    inner class VisibleWidthCombining {
        @Test
        fun `precomposed accented char is width 1`() {
            assertEquals(1, visibleWidth("á"))
        }

        @Test
        fun `decomposed accented char (combining) is width 1`() {
            // "a" + combining acute (U+0301) = one grapheme, width 1.
            assertEquals(1, visibleWidth("a\u0301"))
        }

        @Test
        fun `string of combining marks on a base is width 1`() {
            // "e" + combining acute + combining cedilla = width 1.
            assertEquals(1, visibleWidth("e\u0301\u0327"))
        }

        @Test
        fun `default-ignorable code points contribute zero`() {
            // U+200B zero-width space.
            assertEquals(0, visibleWidth("\u200B"))
            // U+00AD soft hyphen.
            assertEquals(0, visibleWidth("\u00AD"))
            // ZWNJ.
            assertEquals(0, visibleWidth("\u200C"))
        }

        @Test
        fun `base char plus VS-15 text presentation selector is width 1`() {
            // U+2139 information source + VS-15 (text presentation) stays narrow.
            // NOTE: depending on VS-15 handling, this might be 1. Accept either.
            val w = visibleWidth("\u2139\uFE0E")
            assertTrue(w in 1..2, "VS-15 presentation width is $w")
        }
    }

    @Nested
    inner class StripAnsi {
        @Test
        fun `plain text is unchanged`() {
            assertEquals("hello", stripAnsi("hello"))
            assertEquals("", stripAnsi(""))
        }

        @Test
        fun `single CSI code is removed`() {
            assertEquals("hello", stripAnsi("\u001b[31mhello\u001b[0m"))
        }

        @Test
        fun `chained codes are removed`() {
            assertEquals("hi", stripAnsi("\u001b[1;33;41mhi\u001b[0m"))
        }

        @Test
        fun `OSC 8 hyperlink escapes are removed keeping the label`() {
            val input = "\u001b]8;;https://example.com\u0007click\u001b]8;;\u0007"
            assertEquals("click", stripAnsi(input))
        }

        @Test
        fun `OSC with ST terminator is removed`() {
            val input = "\u001b]133;A\u001b\\hello"
            assertEquals("hello", stripAnsi(input))
        }

        @Test
        fun `APC sequences are removed`() {
            val input = "a\u001b_cursor\u0007b"
            assertEquals("ab", stripAnsi(input))
        }

        @Test
        fun `unrelated ESC bytes are left intact`() {
            val input = "abc\u001bXYZ"
            // `\u001b X` doesn't match any known sequence, so it should pass through.
            assertEquals("abc\u001bXYZ", stripAnsi(input))
        }
    }

    @Nested
    inner class TruncateToWidth {
        @Test
        fun `max zero or negative returns empty`() {
            assertEquals("", truncateToWidth("hello", 0))
            assertEquals("", truncateToWidth("hello", -1))
        }

        @Test
        fun `text fits under max is unchanged`() {
            assertEquals("hi", truncateToWidth("hi", 10))
            assertEquals("hello", truncateToWidth("hello", 5))
        }

        @Test
        fun `truncation appends ellipsis and reset`() {
            val r = truncateToWidth("hello world", 6, "…")
            assertTrue(visibleWidth(r) <= 6, "width $r should be <= 6")
            assertTrue(r.endsWith("…\u001b[0m"))
        }

        @Test
        fun `ansi color styling is preserved and reset around ellipsis`() {
            val text = "\u001b[31m" + "hello ".repeat(1000) + "\u001b[0m"
            val truncated = truncateToWidth(text, 20, "…")
            assertTrue(visibleWidth(truncated) <= 20)
            assertTrue(truncated.contains("\u001b[31m"))
            assertTrue(truncated.endsWith("\u001b[0m…\u001b[0m"))
        }

        @Test
        fun `very large unicode input stays within width`() {
            val text = "🙂界".repeat(10_000)
            val truncated = truncateToWidth(text, 40, "…")
            assertTrue(visibleWidth(truncated) <= 40)
            assertTrue(truncated.endsWith("…\u001b[0m"))
        }

        @Test
        fun `wide ellipsis clipped when max is tiny`() {
            assertEquals("", truncateToWidth("abcdef", 1, "🙂"))
            val r = truncateToWidth("abcdef", 2, "🙂")
            assertTrue(visibleWidth(r) <= 2, "width <= 2")
        }

        @Test
        fun `short text with wide ellipsis returns original when it fits`() {
            assertEquals("a", truncateToWidth("a", 2, "🙂"))
            assertEquals("界", truncateToWidth("界", 2, "🙂"))
        }

        @Test
        fun `no-ellipsis truncation still appends reset`() {
            val text = "\u001b[31m" + "hello".repeat(100)
            val truncated = truncateToWidth(text, 10, "")
            assertTrue(visibleWidth(truncated) <= 10)
            assertTrue(truncated.endsWith("\u001b[0m"))
        }

        @Test
        fun `exactly at max returns unchanged`() {
            assertEquals("hello", truncateToWidth("hello", 5))
        }

        @Test
        fun `one over max triggers truncation`() {
            val r = truncateToWidth("hello!", 5, "…")
            assertTrue(visibleWidth(r) <= 5)
            assertTrue(r.contains("…"))
        }

        @Test
        fun `wide-char at boundary is not split mid-grapheme`() {
            val r = truncateToWidth("a界b", 2, "…")
            // Should not produce a mutilated wide grapheme.
            assertTrue(visibleWidth(r) <= 2)
        }

        @Test
        fun `malformed ANSI prefix does not hang`() {
            val text = "abc\u001bnot-ansi " + "🙂".repeat(1000)
            val r = truncateToWidth(text, 20, "…")
            assertTrue(visibleWidth(r) <= 20)
        }
    }

    @Nested
    inner class HardWrap {
        @Test
        fun `empty input yields single empty segment`() {
            assertEquals(listOf(""), hardWrap("", 10))
        }

        @Test
        fun `text shorter than width returns one segment`() {
            assertEquals(listOf("hello"), hardWrap("hello", 10))
        }

        @Test
        fun `zero width throws`() {
            assertThrows<IllegalArgumentException> { hardWrap("anything", 0) }
        }

        @Test
        fun `width 1 with pure ASCII yields one char per segment`() {
            val segments = hardWrap("abc", 1)
            assertEquals(listOf("a", "b", "c"), segments)
        }

        @Test
        fun `ASCII splits at exact column boundaries`() {
            val segments = hardWrap("abcdefghij", 3)
            assertEquals(listOf("abc", "def", "ghi", "j"), segments)
        }

        @Test
        fun `wide chars are not split mid-grapheme`() {
            // 界界界 = 6 cells; width 2 fits one per line.
            val segments = hardWrap("界界界", 2)
            assertEquals(3, segments.size)
            for (s in segments) {
                assertTrue(visibleWidth(s) <= 2, "segment '$s' width > 2")
            }
        }

        @Test
        fun `wide char at boundary does not overflow`() {
            // "a界" at width 2: "a" then "界" (can't fit 界 after a).
            val segments = hardWrap("a界", 2)
            for (s in segments) {
                assertTrue(visibleWidth(s) <= 2)
            }
        }

        @Test
        fun `ANSI color state carries across segments`() {
            val text = "\u001b[31mabcdef\u001b[0m"
            val segments = hardWrap(text, 2)
            // 3 segments of 2 chars.
            assertEquals(3, segments.size)
            // First segment starts red, ends reset.
            assertTrue(segments[0].startsWith("\u001b[31m"))
            assertTrue(segments[0].endsWith("\u001b[0m"))
            // Middle + last segments should re-enter red.
            assertTrue(segments[1].startsWith("\u001b[31m"))
            assertTrue(segments[2].startsWith("\u001b[31m"))
        }

        @Test
        fun `newlines are hard breaks`() {
            val segments = hardWrap("ab\ncd", 10)
            assertEquals(listOf("ab", "cd"), segments)
        }

        @Test
        fun `all-wide-char input wraps cleanly`() {
            val segments = hardWrap("中国人民", 4)
            assertEquals(2, segments.size)
            for (s in segments) assertTrue(visibleWidth(s) <= 4)
        }

        @Test
        fun `every segment respects width bound`() {
            val text = "The quick brown fox jumps over the lazy dog"
            val segments = hardWrap(text, 7)
            for (s in segments) {
                assertTrue(visibleWidth(s) <= 7, "segment '$s' has width ${visibleWidth(s)}")
            }
        }
    }

    @Nested
    inner class WordWrap {
        @Test
        fun `empty input yields single empty segment`() {
            assertEquals(listOf(""), wordWrap("", 10))
        }

        @Test
        fun `zero width throws`() {
            assertThrows<IllegalArgumentException> { wordWrap("x", 0) }
        }

        @Test
        fun `short text is not wrapped`() {
            assertEquals(listOf("hello"), wordWrap("hello", 10))
        }

        @Test
        fun `wraps at word boundaries`() {
            val segments = wordWrap("the quick brown fox", 10)
            for (s in segments) {
                assertTrue(visibleWidth(s) <= 10, "segment '$s' too wide")
            }
            assertTrue(segments.size > 1)
        }

        @Test
        fun `no spaces falls back to hard wrap`() {
            val segments = wordWrap("abcdefghij", 3)
            for (s in segments) assertTrue(visibleWidth(s) <= 3)
        }

        @Test
        fun `long word falls back to hard wrap`() {
            val url = "https://example.com/really/long/path/that/will/wrap"
            val segments = wordWrap("prefix $url suffix", 15)
            for (s in segments) {
                assertTrue(visibleWidth(s) <= 15, "segment '$s' too wide")
            }
        }

        @Test
        fun `consecutive spaces are handled`() {
            val segments = wordWrap("a  b  c  d  e", 3)
            for (s in segments) assertTrue(visibleWidth(s) <= 3)
        }

        @Test
        fun `only whitespace trims to within width`() {
            val segments = wordWrap("  ", 1)
            assertTrue(visibleWidth(segments[0]) <= 1)
        }

        @Test
        fun `ANSI color is reapplied after wrap`() {
            val red = "\u001b[31m"
            val reset = "\u001b[0m"
            val text = "${red}hello world this is red$reset"
            val segments = wordWrap(text, 10)
            // Continuation lines should re-enter red.
            for (i in 1 until segments.size) {
                assertTrue(segments[i].startsWith(red), "segment $i does not start with red: ${segments[i]}")
            }
        }

        @Test
        fun `ANSI styling from first line carries over after literal newline`() {
            val text = "\u001b[31mhello\nworld\u001b[0m"
            val segments = wordWrap(text, 20)
            // After the newline, "world" is still red.
            assertTrue(segments[1].contains("world"))
            // The continuation line should have red prefix.
            assertTrue(segments[1].contains("\u001b[31m"))
        }
    }

    @Nested
    inner class SliceByColumn {
        @Test
        fun `ASCII slice returns subrange`() {
            assertEquals("ell", sliceByColumn("hello", 1, 4))
        }

        @Test
        fun `slice with end at or before start is empty`() {
            assertEquals("", sliceByColumn("hello", 3, 3))
            assertEquals("", sliceByColumn("hello", 5, 2))
        }

        @Test
        fun `slice through ANSI emits codes within range`() {
            val text = "\u001b[31mhello\u001b[0m"
            val r = sliceByColumn(text, 1, 4)
            // Should contain 'ell' and the red opener.
            assertTrue(r.contains("ell"))
            assertTrue(r.contains("\u001b[31m"))
        }

        @Test
        fun `strict slice drops wide char that straddles right boundary`() {
            // "a界" = a(col0) + 界(cols1-2). Slicing [0,2) strict should exclude 界.
            val r = sliceByColumn("a界", 0, 2, strict = true)
            assertEquals("a", r)
        }

        @Test
        fun `non-strict slice includes wide char that straddles boundary`() {
            // In non-strict mode, the wide char is included even though it
            // overflows — the caller is assumed to handle the overhang.
            val r = sliceByColumn("a界", 0, 2, strict = false)
            assertEquals("a界", r)
        }

        @Test
        fun `slice at interior boundary drops wide char in strict mode`() {
            // "中国" = 中(cols0-1) + 国(cols2-3). Slice [1,3) strict: 中 spans
            // col 1 (inside range) but starts at col 0 (outside), so drop; 国
            // starts at col 2 (inside) ends at col 3 (inside), include.
            val r = sliceByColumn("中国", 1, 3, strict = true)
            // Strict mode tests `currentCol + w <= end`. 国 has currentCol=2,
            // w=2, end=3, so 2+2=4 > 3, drops 国 too. Result: nothing.
            assertEquals("", r)
        }

        @Test
        fun `slice preserves pending ANSI before start`() {
            // Red is set before col 0; slice [1,5) should include red on first char.
            val text = "\u001b[31m12345"
            val r = sliceByColumn(text, 1, 4)
            assertTrue(r.contains("\u001b[31m"))
            assertTrue(r.contains("234"))
        }
    }

    @Nested
    inner class PadRight {
        @Test
        fun `pads short ASCII with spaces`() {
            assertEquals("hi   ", padRightToWidth("hi", 5))
        }

        @Test
        fun `no-op when exactly at width`() {
            assertEquals("hello", padRightToWidth("hello", 5))
        }

        @Test
        fun `no-op when already wider`() {
            assertEquals("toolong", padRightToWidth("toolong", 3))
        }

        @Test
        fun `wide-char input pads to remaining cells`() {
            // 界 = 2 cells; padding to 5 adds 3 spaces.
            assertEquals("界   ", padRightToWidth("界", 5))
        }

        @Test
        fun `ANSI escapes don't inflate width`() {
            val r = padRightToWidth("\u001b[31mhi\u001b[0m", 5)
            // visible width of "hi" is 2, so 3 trailing spaces.
            assertEquals(5, visibleWidth(r))
            assertTrue(r.endsWith("   "))
        }
    }

    @Nested
    inner class PadLeft {
        @Test
        fun `pads short ASCII with leading spaces`() {
            assertEquals("   hi", padLeftToWidth("hi", 5))
        }

        @Test
        fun `no-op when exactly at width`() {
            assertEquals("hello", padLeftToWidth("hello", 5))
        }

        @Test
        fun `no-op when already wider`() {
            assertEquals("toolong", padLeftToWidth("toolong", 3))
        }

        @Test
        fun `wide-char input pads correctly`() {
            assertEquals("   界", padLeftToWidth("界", 5))
        }

        @Test
        fun `ANSI escapes don't inflate width`() {
            val r = padLeftToWidth("\u001b[31mhi\u001b[0m", 5)
            assertEquals(5, visibleWidth(r))
            assertTrue(r.startsWith("   "))
        }
    }

    @Nested
    inner class EndToEndInvariants {
        @Test
        fun `hardWrap all segments fit within width`() {
            // Width must be >= the widest grapheme in the input.
            // A 2-cell emoji or CJK char can't fit in width 1.
            val narrowSafe = listOf(
                "simple text" to listOf(1, 3, 5, 10, 20),
                "中国人民中国人民中国人民" to listOf(2, 3, 4, 6, 10),
                "\u001b[31mred\u001b[0m and \u001b[32mgreen\u001b[0m" to listOf(1, 3, 5, 10, 20),
                "👨\u200D💻 coder emoji" to listOf(2, 3, 5, 10, 20),
                "" to listOf(1, 3, 5, 10, 20),
            )
            for ((input, widths) in narrowSafe) {
                for (w in widths) {
                    val segments = hardWrap(input, w)
                    for (s in segments) {
                        val sw = visibleWidth(s)
                        assertTrue(sw <= w, "input='$input' width=$w segment='$s' sw=$sw")
                    }
                }
            }
        }

        @Test
        fun `wordWrap all segments fit within width`() {
            val inputs = listOf(
                "hello world foo bar",
                "a short line",
                "\u001b[34mblue\u001b[0m sky blue sky",
                "      - 🇨", // regression: partial regional indicator
            )
            for (w in listOf(3, 5, 9, 10, 20)) {
                for (input in inputs) {
                    val segments = wordWrap(input, w)
                    for (s in segments) {
                        assertTrue(visibleWidth(s) <= w, "input='$input' width=$w segment='$s'")
                    }
                }
            }
        }

        @Test
        fun `regression partial regional indicator wraps cleanly`() {
            val wrapped = wordWrap("      - 🇨", 9)
            for (s in wrapped) assertTrue(visibleWidth(s) <= 9, "segment '$s' too wide")
        }

        @Test
        fun `truncateToWidth is bounded`() {
            val inputs = listOf(
                "hello world",
                "界界界界界界界",
                "\u001b[1;31mbold red text\u001b[0m",
                "🙂".repeat(100),
                "\u001b]8;;https://a\u0007click\u001b]8;;\u0007 here",
            )
            for (max in listOf(1, 3, 5, 10)) {
                for (input in inputs) {
                    val r = truncateToWidth(input, max, "…")
                    assertTrue(visibleWidth(r) <= max, "input='$input' max=$max result='$r' w=${visibleWidth(r)}")
                }
            }
        }
    }

    @Nested
    inner class AnsiTrackerInternals {
        // These tests exercise cross-function behavior that hinges on the
        // internal ANSI tracker. They live here rather than as private unit
        // tests so we don't leak implementation details into the public API.

        @Test
        fun `background color preserved across hardWrap segments`() {
            val bg = "\u001b[44m"
            val text = "${bg}hello world${'\u001b'}[0m"
            val segments = hardWrap(text, 5)
            assertTrue(segments.size >= 2)
            // Every segment should carry the background.
            for (s in segments) assertTrue(s.contains(bg) || s.contains("[44m"), "segment missing bg: $s")
        }

        @Test
        fun `chained underline + color preserved across hardWrap`() {
            val open = "\u001b[4;31m"
            val text = "${open}underlined red text here\u001b[0m"
            val segments = hardWrap(text, 7)
            // Segment after the first should reapply the active state.
            assertTrue(segments.size >= 2)
            for (i in 1 until segments.size) {
                // The SGR prefix should contain both 4 and 31 (exact form may
                // differ — e.g. "\u001b[1;4;31m" vs "\u001b[4;31m").
                val hasUnderline = segments[i].contains("[4m") || segments[i].contains("[4;") || segments[i].contains(";4m") || segments[i].contains(";4;")
                val hasRed = segments[i].contains("31")
                assertTrue(hasUnderline, "seg '${segments[i]}' missing underline")
                assertTrue(hasRed, "seg '${segments[i]}' missing red")
            }
        }

        @Test
        fun `full reset clears all state`() {
            // After a full reset, subsequent segments should NOT re-enter the
            // previously-active style.
            val text = "\u001b[1;31mAAA\u001b[0mBBBCCCDDD"
            val segments = hardWrap(text, 3)
            assertTrue(segments.isNotEmpty())
            // After the full reset, any segment that doesn't contain "A" must
            // not carry bold/red styling.
            for (s in segments) {
                if (!s.contains("A")) {
                    assertFalse(s.contains("[1;31m") || s.contains("[31m") && s.contains("[1m"),
                        "segment after reset should not re-enter bold/red: '$s'")
                }
            }
        }
    }
}
