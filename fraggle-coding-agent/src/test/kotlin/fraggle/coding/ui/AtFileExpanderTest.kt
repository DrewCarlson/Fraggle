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
        fun `single relative file prepends a context block and preserves user text`(@TempDir tmp: Path) {
            val file = tmp.resolve("hello.txt")
            file.writeText("greetings\n")

            val userText = "look at @hello.txt now"
            val result = AtFileExpander.expand(userText, tmp)

            assertTrue(result.isChanged)
            assertEquals(listOf("hello.txt"), result.resolved)
            assertTrue(result.unresolved.isEmpty())

            // Must start with the context wrapper and end with the user's
            // literal text (@token preserved).
            assertTrue(result.expandedText.startsWith("<context>\n"))
            assertTrue(
                result.expandedText.endsWith("\n\n$userText"),
                "user text must appear verbatim after the context block",
            )
            // Context block contents.
            assertContains(result.expandedText, "`hello.txt`:")
            assertContains(result.expandedText, "```\ngreetings\n```")
            // Closing tag present exactly once.
            assertEquals(1, result.expandedText.split("</context>").size - 1)
        }

        @Test
        fun `absolute path also resolves and is used verbatim in context header`(@TempDir tmp: Path) {
            val file = tmp.resolve("abs.txt")
            file.writeText("abs content")
            val abs = file.toAbsolutePath().toString()

            val result = AtFileExpander.expand("here: @$abs end", tmp)

            assertEquals(listOf(abs), result.resolved)
            assertTrue(result.unresolved.isEmpty())
            assertContains(result.expandedText, "`$abs`:")
            assertContains(result.expandedText, "abs content")
            // User text with @token preserved.
            assertTrue(result.expandedText.endsWith("here: @$abs end"))
        }

        @Test
        fun `multiple references all appear in one context block, user tokens preserved`(@TempDir tmp: Path) {
            tmp.resolve("a.txt").writeText("AAA\n")
            tmp.resolve("b.txt").writeText("BBB\n")

            val userText = "compare @a.txt with @b.txt ok"
            val result = AtFileExpander.expand(userText, tmp)

            assertEquals(listOf("a.txt", "b.txt"), result.resolved)
            assertTrue(result.unresolved.isEmpty())
            // User text preserved verbatim at the tail.
            assertTrue(result.expandedText.endsWith("\n\n$userText"))
            // Both headers present inside the wrapper.
            val aHeader = result.expandedText.indexOf("`a.txt`:")
            val bHeader = result.expandedText.indexOf("`b.txt`:")
            assertTrue(aHeader in 0 until result.expandedText.indexOf("</context>"))
            assertTrue(bHeader in aHeader..result.expandedText.indexOf("</context>"))
            assertContains(result.expandedText, "AAA")
            assertContains(result.expandedText, "BBB")
        }

        @Test
        fun `reference at start of text works and preserves the literal @token`(@TempDir tmp: Path) {
            val file = tmp.resolve("start.md")
            file.writeText("hi")

            val result = AtFileExpander.expand("@start.md", tmp)

            assertEquals(listOf("start.md"), result.resolved)
            assertContains(result.expandedText, "`start.md`:")
            assertContains(result.expandedText, "```\nhi\n```")
            // Original token preserved at the end.
            assertTrue(result.expandedText.endsWith("\n\n@start.md"))
        }

        @Test
        fun `file not ending in newline gets trailing newline in fence`(@TempDir tmp: Path) {
            val file = tmp.resolve("no-nl.txt")
            file.writeText("abc")

            val result = AtFileExpander.expand("@no-nl.txt", tmp)

            assertContains(result.expandedText, "abc\n```")
        }

        @Test
        fun `duplicate references contribute only one entry to the context block`(@TempDir tmp: Path) {
            tmp.resolve("dup.txt").writeText("once\n")

            val userText = "compare @dup.txt with @dup.txt please"
            val result = AtFileExpander.expand(userText, tmp)

            assertEquals(listOf("dup.txt"), result.resolved)
            // Exactly one header in the context block.
            assertEquals(1, result.expandedText.split("`dup.txt`:").size - 1)
            // User text — including BOTH literal @dup.txt tokens — is preserved.
            assertTrue(result.expandedText.endsWith("\n\n$userText"))
        }
    }

    @Nested
    inner class Unresolves {
        @Test
        fun `missing file keeps user text verbatim and records as unresolved`(@TempDir tmp: Path) {
            val text = "@does-not-exist.md here"
            val result = AtFileExpander.expand(text, tmp)

            // No context block was built, so the text comes out unchanged.
            assertEquals(text, result.expandedText)
            assertTrue(result.resolved.isEmpty())
            assertEquals(listOf("does-not-exist.md"), result.unresolved)
        }

        @Test
        fun `directory is treated as unresolved and user text stays unchanged`(@TempDir tmp: Path) {
            val dir = tmp.resolve("somedir")
            dir.createDirectory()

            val userText = "look at @somedir here"
            val result = AtFileExpander.expand(userText, tmp)

            assertEquals(userText, result.expandedText)
            assertTrue(result.resolved.isEmpty())
            assertEquals(listOf("somedir"), result.unresolved)
        }

        @Test
        fun `file exceeding maxFileBytes is unresolved`(@TempDir tmp: Path) {
            val big = tmp.resolve("big.txt")
            big.writeText("x".repeat(1024))

            val userText = "see @big.txt"
            val result = AtFileExpander.expand(userText, tmp, maxFileBytes = 512)

            assertEquals(userText, result.expandedText)
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
        fun `mix of resolved and unresolved puts good file in context, bad stays in user text`(@TempDir tmp: Path) {
            tmp.resolve("good.txt").writeText("good")

            val userText = "@good.txt and @bad.txt done"
            val result = AtFileExpander.expand(userText, tmp)

            assertEquals(listOf("good.txt"), result.resolved)
            assertEquals(listOf("bad.txt"), result.unresolved)
            // Context block contains only the resolved file.
            assertContains(result.expandedText, "`good.txt`:")
            assertFalse(result.expandedText.contains("`bad.txt`:"))
            // User text preserved verbatim — both @tokens included.
            assertTrue(result.expandedText.endsWith("\n\n$userText"))
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

            val userText = "@sub/dir/x.txt"
            val result = AtFileExpander.expand(userText, tmp)

            assertEquals(listOf("sub/dir/x.txt"), result.resolved)
            assertContains(result.expandedText, "`sub/dir/x.txt`:")
            assertContains(result.expandedText, "hidden-content")
            assertTrue(result.expandedText.endsWith("\n\n$userText"))
        }
    }

    @Nested
    inner class StripContextBlock {
        @Test
        fun `strips a well-formed wrapper and surrounding blank lines`() {
            val wrapped = "<context>\n`foo`:\n```\ncontents\n```\n</context>\n\nwhat do you think?"
            assertEquals("what do you think?", AtFileExpander.stripContextBlock(wrapped))
        }

        @Test
        fun `no context block returns text unchanged`() {
            val plain = "no wrapper here"
            assertEquals(plain, AtFileExpander.stripContextBlock(plain))
        }

        @Test
        fun `partial open tag without close is left alone`() {
            val weird = "<context>\noh no did not finish"
            assertEquals(weird, AtFileExpander.stripContextBlock(weird))
        }

        @Test
        fun `idempotent on already-stripped text`() {
            val stripped = "what do you think?"
            assertEquals(stripped, AtFileExpander.stripContextBlock(AtFileExpander.stripContextBlock(stripped)))
        }

        @Test
        fun `round-trip with expand yields the original user text`(@TempDir tmp: Path) {
            tmp.resolve("a.txt").writeText("aaa")
            tmp.resolve("b.txt").writeText("bbb")
            val userText = "review @a.txt and @b.txt carefully"

            val expanded = AtFileExpander.expand(userText, tmp).expandedText
            val stripped = AtFileExpander.stripContextBlock(expanded)

            assertEquals(userText, stripped)
        }
    }
}
