package fraggle.coding.ui

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AtFileExpanderTest {

    @Nested
    inner class NoTrigger {
        @Test
        fun `empty text returns empty unchanged`(@TempDir tmp: Path) {
            val result = AtFileExpander.expand("", tmp)
            assertEquals("", result.expandedText)
            assertTrue(result.resolved.isEmpty())
            assertTrue(result.unresolved.isEmpty())
            assertFalse(result.isChanged)
        }

        @Test
        fun `text with no at sign is returned verbatim`(@TempDir tmp: Path) {
            val text = "hello world this has no trigger"
            val result = AtFileExpander.expand(text, tmp)
            assertEquals(text, result.expandedText)
            assertTrue(result.resolved.isEmpty())
            assertTrue(result.unresolved.isEmpty())
        }

        @Test
        fun `email address is NOT treated as a reference`(@TempDir tmp: Path) {
            val text = "contact user@example.com please"
            val result = AtFileExpander.expand(text, tmp)
            assertEquals(text, result.expandedText)
            assertTrue(result.resolved.isEmpty())
            // Not even recorded as unresolved — mid-word @ shouldn't fire at all.
            assertTrue(result.unresolved.isEmpty())
        }

        @Test
        fun `at mid word is not a reference`(@TempDir tmp: Path) {
            val text = "foo@bar is not a ref"
            val result = AtFileExpander.expand(text, tmp)
            assertEquals(text, result.expandedText)
            assertTrue(result.resolved.isEmpty())
            assertTrue(result.unresolved.isEmpty())
        }

        @Test
        fun `at followed by whitespace is not a reference`(@TempDir tmp: Path) {
            // "@" then space → zero-length token; kept literal, not recorded.
            val text = "hey @ then stuff"
            val result = AtFileExpander.expand(text, tmp)
            assertEquals(text, result.expandedText)
            assertTrue(result.resolved.isEmpty())
            assertTrue(result.unresolved.isEmpty())
        }

        @Test
        fun `trailing at with no path is kept literal`(@TempDir tmp: Path) {
            val text = "ends with @"
            val result = AtFileExpander.expand(text, tmp)
            assertEquals(text, result.expandedText)
            assertTrue(result.resolved.isEmpty())
            assertTrue(result.unresolved.isEmpty())
        }
    }

    @Nested
    inner class Resolves {
        @Test
        fun `single relative file expands into a code fence with path header`(@TempDir tmp: Path) {
            val file = tmp.resolve("hello.txt")
            file.writeText("greetings\n")

            val result = AtFileExpander.expand("look at @hello.txt now", tmp)

            assertTrue(result.isChanged)
            assertEquals(listOf("hello.txt"), result.resolved)
            assertTrue(result.unresolved.isEmpty())
            // Preamble / postamble preserved.
            assertTrue(
                result.expandedText.startsWith("look at "),
                "preamble must be preserved, got: ${result.expandedText}",
            )
            assertTrue(
                result.expandedText.endsWith(" now"),
                "postamble must be preserved, got: ${result.expandedText}",
            )
            // Contains the header + code fence.
            assertContains(result.expandedText, "`hello.txt`:")
            assertContains(result.expandedText, "```\ngreetings\n```")
        }

        @Test
        fun `absolute path also resolves and is used verbatim in header`(@TempDir tmp: Path) {
            val file = tmp.resolve("abs.txt")
            file.writeText("abs content")
            val abs = file.toAbsolutePath().toString()

            val result = AtFileExpander.expand("here: @$abs end", tmp)

            assertEquals(listOf(abs), result.resolved)
            assertTrue(result.unresolved.isEmpty())
            assertContains(result.expandedText, "`$abs`:")
            assertContains(result.expandedText, "abs content")
        }

        @Test
        fun `multiple references expand independently in order`(@TempDir tmp: Path) {
            val a = tmp.resolve("a.txt").apply { writeText("AAA\n") }
            val b = tmp.resolve("b.txt").apply { writeText("BBB\n") }

            val result = AtFileExpander.expand("compare @a.txt with @b.txt ok", tmp)

            assertEquals(listOf("a.txt", "b.txt"), result.resolved)
            assertTrue(result.unresolved.isEmpty())
            val aHeader = result.expandedText.indexOf("`a.txt`:")
            val bHeader = result.expandedText.indexOf("`b.txt`:")
            assertTrue(aHeader >= 0, "missing a header")
            assertTrue(bHeader > aHeader, "b header must come after a header")
            assertContains(result.expandedText, "AAA")
            assertContains(result.expandedText, "BBB")
            // Unused paths intentionally referenced so IntelliJ keeps imports happy.
            assertTrue(a.toFile().exists())
            assertTrue(b.toFile().exists())
        }

        @Test
        fun `reference at start of text works (no preceding whitespace)`(@TempDir tmp: Path) {
            val file = tmp.resolve("start.md")
            file.writeText("hi")

            val result = AtFileExpander.expand("@start.md", tmp)

            assertEquals(listOf("start.md"), result.resolved)
            assertContains(result.expandedText, "`start.md`:")
            assertContains(result.expandedText, "```\nhi\n```")
        }

        @Test
        fun `file not ending in newline gets trailing newline in fence`(@TempDir tmp: Path) {
            val file = tmp.resolve("no-nl.txt")
            file.writeText("abc") // no trailing \n

            val result = AtFileExpander.expand("@no-nl.txt", tmp)

            // Output must include "abc\n```" so the closing fence is on its own line.
            assertContains(result.expandedText, "abc\n```")
        }
    }

    @Nested
    inner class Unresolves {
        @Test
        fun `missing file kept literal and recorded`(@TempDir tmp: Path) {
            val text = "@does-not-exist.md here"
            val result = AtFileExpander.expand(text, tmp)

            assertEquals("@does-not-exist.md here", result.expandedText)
            assertTrue(result.resolved.isEmpty())
            assertEquals(listOf("does-not-exist.md"), result.unresolved)
        }

        @Test
        fun `directory is treated as unresolved`(@TempDir tmp: Path) {
            val dir = tmp.resolve("somedir")
            dir.createDirectory()

            val result = AtFileExpander.expand("look at @somedir here", tmp)

            assertEquals("look at @somedir here", result.expandedText)
            assertTrue(result.resolved.isEmpty())
            assertEquals(listOf("somedir"), result.unresolved)
        }

        @Test
        fun `file exceeding maxFileBytes is unresolved`(@TempDir tmp: Path) {
            val big = tmp.resolve("big.txt")
            big.writeText("x".repeat(1024))

            val result = AtFileExpander.expand("see @big.txt", tmp, maxFileBytes = 512)

            assertEquals("see @big.txt", result.expandedText)
            assertTrue(result.resolved.isEmpty())
            assertEquals(listOf("big.txt"), result.unresolved)
        }

        @Test
        fun `file at exactly maxFileBytes resolves`(@TempDir tmp: Path) {
            val ok = tmp.resolve("ok.txt")
            ok.writeText("x".repeat(512))

            val result = AtFileExpander.expand("@ok.txt", tmp, maxFileBytes = 512)

            assertEquals(listOf("ok.txt"), result.resolved)
        }

        @Test
        fun `mix of resolved and unresolved records each separately`(@TempDir tmp: Path) {
            tmp.resolve("good.txt").writeText("good")

            val result = AtFileExpander.expand("@good.txt and @bad.txt done", tmp)

            assertEquals(listOf("good.txt"), result.resolved)
            assertEquals(listOf("bad.txt"), result.unresolved)
            // Bad path kept literally.
            assertContains(result.expandedText, "@bad.txt")
            // Good path replaced.
            assertContains(result.expandedText, "`good.txt`:")
        }
    }

    @Nested
    inner class Subdirectories {
        @Test
        fun `subdirectory relative path resolves`(@TempDir tmp: Path) {
            val sub = tmp.resolve("sub/dir")
            sub.createDirectories()
            val file = sub.resolve("x.txt")
            file.writeText("hidden-content")

            val result = AtFileExpander.expand("@sub/dir/x.txt", tmp)

            assertEquals(listOf("sub/dir/x.txt"), result.resolved)
            assertContains(result.expandedText, "`sub/dir/x.txt`:")
            assertContains(result.expandedText, "hidden-content")
        }
    }
}
