package fraggle.coding.tools

import fraggle.executor.LocalToolExecutor
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditFileToolTest {

    @Nested
    inner class SuccessPaths {
        @Test
        fun `exact single match is replaced`(@TempDir dir: Path) = runTest {
            val tool = EditFileTool(LocalToolExecutor(dir))
            val file = dir.resolve("src.kt").also {
                it.writeText("val greeting = \"hello\"\nfun main() { println(greeting) }\n")
            }

            val result = tool.execute(EditFileTool.Args(
                path = "src.kt",
                oldString = "\"hello\"",
                newString = "\"world\"",
            ))

            assertTrue(result.startsWith("Edited"), "result should indicate success, got: $result")
            assertTrue(result.contains("1 occurrence"), "should report 1 replacement: $result")
            assertEquals(
                "val greeting = \"world\"\nfun main() { println(greeting) }\n",
                file.readText(),
            )
        }

        @Test
        fun `empty new_string deletes the matched text`(@TempDir dir: Path) = runTest {
            val tool = EditFileTool(LocalToolExecutor(dir))
            dir.resolve("f.txt").writeText("before DELETE_ME after")

            val result = tool.execute(EditFileTool.Args(
                path = "f.txt",
                oldString = " DELETE_ME",
                newString = "",
            ))

            assertTrue(result.startsWith("Edited"))
            assertEquals("before after", dir.resolve("f.txt").readText())
        }

        @Test
        fun `identical old and new strings is a no-op with a clear message`(@TempDir dir: Path) = runTest {
            val tool = EditFileTool(LocalToolExecutor(dir))
            val original = "unchanged content"
            val file = dir.resolve("f.txt").also { it.writeText(original) }

            val result = tool.execute(EditFileTool.Args(
                path = "f.txt",
                oldString = "content",
                newString = "content",
            ))

            assertTrue(result.startsWith("No change"), "got: $result")
            assertEquals(original, file.readText())
        }

        @Test
        fun `multi-line old_string matches across newlines`(@TempDir dir: Path) = runTest {
            val tool = EditFileTool(LocalToolExecutor(dir))
            val file = dir.resolve("f.kt").also {
                it.writeText("fun foo() {\n    println(\"a\")\n    println(\"b\")\n}\n")
            }

            val result = tool.execute(EditFileTool.Args(
                path = "f.kt",
                oldString = "    println(\"a\")\n    println(\"b\")",
                newString = "    println(\"combined\")",
            ))

            assertTrue(result.startsWith("Edited"))
            assertEquals("fun foo() {\n    println(\"combined\")\n}\n", file.readText())
        }

        @Test
        fun `absolute path is accepted`(@TempDir dir: Path) = runTest {
            val tool = EditFileTool(LocalToolExecutor(dir))
            val file = dir.resolve("abs.txt").also { it.writeText("foo bar baz") }

            val result = tool.execute(EditFileTool.Args(
                path = file.toAbsolutePath().toString(),
                oldString = "bar",
                newString = "BAR",
            ))

            assertTrue(result.startsWith("Edited"))
            assertEquals("foo BAR baz", file.readText())
        }
    }

    @Nested
    inner class ReplaceAll {
        @Test
        fun `replace_all replaces every occurrence and reports the count`(@TempDir dir: Path) = runTest {
            val tool = EditFileTool(LocalToolExecutor(dir))
            val file = dir.resolve("f.kt").also {
                it.writeText("foo(x)\nfoo(y)\nbar()\nfoo(z)\n")
            }

            val result = tool.execute(EditFileTool.Args(
                path = "f.kt",
                oldString = "foo",
                newString = "baz",
                replaceAll = true,
            ))

            assertTrue(result.startsWith("Edited"))
            assertTrue(result.contains("3 occurrences"), "should report 3 replacements: $result")
            assertEquals("baz(x)\nbaz(y)\nbar()\nbaz(z)\n", file.readText())
        }

        @Test
        fun `replace_all on a single match still works and reports 1 occurrence`(@TempDir dir: Path) = runTest {
            val tool = EditFileTool(LocalToolExecutor(dir))
            val file = dir.resolve("f.txt").also { it.writeText("only one foo here") }

            val result = tool.execute(EditFileTool.Args(
                path = "f.txt",
                oldString = "foo",
                newString = "bar",
                replaceAll = true,
            ))

            assertTrue(result.contains("1 occurrence"), "should report singular 1: $result")
            assertEquals("only one bar here", file.readText())
        }
    }

    @Nested
    inner class ErrorPaths {
        @Test
        fun `no match returns a diagnostic and leaves the file untouched`(@TempDir dir: Path) = runTest {
            val tool = EditFileTool(LocalToolExecutor(dir))
            val original = "actual content"
            val file = dir.resolve("f.txt").also { it.writeText(original) }

            val result = tool.execute(EditFileTool.Args(
                path = "f.txt",
                oldString = "missing",
                newString = "present",
            ))

            assertTrue(result.startsWith("Error: old_string not found"), "got: $result")
            assertEquals(original, file.readText(), "file must not be modified on no-match")
        }

        @Test
        fun `ambiguous match without replace_all returns a diagnostic and leaves the file untouched`(@TempDir dir: Path) = runTest {
            val tool = EditFileTool(LocalToolExecutor(dir))
            val original = "foo\nfoo\nfoo\n"
            val file = dir.resolve("f.txt").also { it.writeText(original) }

            val result = tool.execute(EditFileTool.Args(
                path = "f.txt",
                oldString = "foo",
                newString = "bar",
            ))

            assertTrue(result.contains("matches 3 locations"), "expected ambiguity message, got: $result")
            assertTrue(result.contains("replace_all=true"), "should suggest replace_all: $result")
            assertEquals(original, file.readText(), "file must not be modified on ambiguous match")
        }

        @Test
        fun `missing file returns a not-found error`(@TempDir dir: Path) = runTest {
            val tool = EditFileTool(LocalToolExecutor(dir))

            val result = tool.execute(EditFileTool.Args(
                path = "does-not-exist.txt",
                oldString = "anything",
                newString = "else",
            ))

            assertTrue(result.startsWith("Error: File not found"), "got: $result")
        }

        @Test
        fun `directory path is rejected as not a regular file`(@TempDir dir: Path) = runTest {
            val tool = EditFileTool(LocalToolExecutor(dir))
            dir.resolve("subdir").createDirectories()

            val result = tool.execute(EditFileTool.Args(
                path = "subdir",
                oldString = "anything",
                newString = "else",
            ))

            assertTrue(result.startsWith("Error: Not a regular file"), "got: $result")
        }

        @Test
        fun `empty old_string is rejected as no match rather than accidentally matching everywhere`(@TempDir dir: Path) = runTest {
            val tool = EditFileTool(LocalToolExecutor(dir))
            dir.resolve("f.txt").writeText("some content")

            val result = tool.execute(EditFileTool.Args(
                path = "f.txt",
                oldString = "",
                newString = "INSERTED",
            ))

            // We want this to fail cleanly — not silently insert INSERTED between
            // every character. Empty old_string is almost certainly a bug in the
            // model's call, and we'd rather surface a clear error.
            assertTrue(
                result.startsWith("Error: old_string not found"),
                "empty old_string should surface as no-match, got: $result",
            )
        }
    }

    @Nested
    inner class Contextualization {
        @Test
        fun `unique match can be isolated by providing more surrounding context`(@TempDir dir: Path) = runTest {
            val tool = EditFileTool(LocalToolExecutor(dir))
            val file = dir.resolve("f.kt").also {
                it.writeText(
                    """
                    class Foo {
                        val x = 1
                    }

                    class Bar {
                        val x = 1
                    }
                    """.trimIndent() + "\n",
                )
            }

            // "val x = 1" appears twice; but "class Bar {\n    val x = 1" is unique.
            val result = tool.execute(EditFileTool.Args(
                path = "f.kt",
                oldString = "class Bar {\n    val x = 1",
                newString = "class Bar {\n    val x = 2",
            ))

            assertTrue(result.startsWith("Edited"), "got: $result")
            val updated = file.readText()
            assertTrue(updated.contains("class Bar {\n    val x = 2"))
            // The Foo one must be left alone
            assertTrue(updated.contains("class Foo {\n    val x = 1"))
        }
    }
}
