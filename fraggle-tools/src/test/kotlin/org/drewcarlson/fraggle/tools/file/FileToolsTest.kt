package org.drewcarlson.fraggle.tools.file

import kotlinx.coroutines.test.runTest
import org.drewcarlson.fraggle.executor.LocalToolExecutor
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileToolsTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var toolExecutor: LocalToolExecutor

    @BeforeEach
    fun setup() {
        toolExecutor = LocalToolExecutor(tempDir)
    }

    @Nested
    inner class ReadFileToolTests {

        @Test
        fun `read_file returns file contents`() = runTest {
            tempDir.resolve("test.txt").writeText("Hello, World!")

            val tool = ReadFileTool(toolExecutor)
            val result = tool.execute(ReadFileTool.Args(path = "test.txt"))

            assertEquals("Hello, World!", result)
        }

        @Test
        fun `read_file with absolute path works`() = runTest {
            val testFile = tempDir.resolve("absolute.txt")
            testFile.writeText("Absolute path content")

            val tool = ReadFileTool(toolExecutor)
            val result = tool.execute(ReadFileTool.Args(path = testFile.toString()))

            assertEquals("Absolute path content", result)
        }

        @Test
        fun `read_file respects max_lines parameter`() = runTest {
            tempDir.resolve("multiline.txt").writeText("Line 1\nLine 2\nLine 3\nLine 4\nLine 5")

            val tool = ReadFileTool(toolExecutor)
            val result = tool.execute(ReadFileTool.Args(path = "multiline.txt", maxLines = 3))

            assertEquals("Line 1\nLine 2\nLine 3", result)
        }

        @Test
        fun `read_file returns error for nonexistent file`() = runTest {
            val tool = ReadFileTool(toolExecutor)
            val result = tool.execute(ReadFileTool.Args(path = "nonexistent.txt"))

            assertTrue(result.startsWith("Error:"))
            assertTrue(result.contains("not found"))
        }

        @Test
        fun `read_file returns error for directory`() = runTest {
            tempDir.resolve("subdir").createDirectory()

            val tool = ReadFileTool(toolExecutor)
            val result = tool.execute(ReadFileTool.Args(path = "subdir"))

            assertTrue(result.startsWith("Error:"))
            assertTrue(result.contains("Not a regular file"))
        }
    }

    @Nested
    inner class WriteFileToolTests {

        @Test
        fun `write_file creates new file`() = runTest {
            val tool = WriteFileTool(toolExecutor)
            val result = tool.execute(WriteFileTool.Args(path = "new.txt", content = "New content"))

            assertTrue(result.contains("written successfully"))
            assertEquals("New content", tempDir.resolve("new.txt").readText())
        }

        @Test
        fun `write_file overwrites existing file`() = runTest {
            val testFile = tempDir.resolve("existing.txt")
            testFile.writeText("Original content")

            val tool = WriteFileTool(toolExecutor)
            tool.execute(WriteFileTool.Args(path = "existing.txt", content = "Replaced content"))

            assertEquals("Replaced content", testFile.readText())
        }

        @Test
        fun `write_file creates parent directories`() = runTest {
            val tool = WriteFileTool(toolExecutor)
            tool.execute(WriteFileTool.Args(path = "a/b/c/deep.txt", content = "Deep content"))

            assertTrue(tempDir.resolve("a/b/c/deep.txt").exists())
            assertEquals("Deep content", tempDir.resolve("a/b/c/deep.txt").readText())
        }
    }

    @Nested
    inner class AppendFileToolTests {

        @Test
        fun `append_file adds content to existing file`() = runTest {
            tempDir.resolve("append.txt").writeText("First line\n")

            val tool = AppendFileTool(toolExecutor)
            val result = tool.execute(AppendFileTool.Args(path = "append.txt", content = "Second line"))

            assertTrue(result.contains("appended"))
            assertEquals("First line\nSecond line", tempDir.resolve("append.txt").readText())
        }

        @Test
        fun `append_file appends multiple times`() = runTest {
            tempDir.resolve("multi_append.txt").writeText("Start")

            val tool = AppendFileTool(toolExecutor)
            tool.execute(AppendFileTool.Args(path = "multi_append.txt", content = " - Middle"))
            tool.execute(AppendFileTool.Args(path = "multi_append.txt", content = " - End"))

            assertEquals("Start - Middle - End", tempDir.resolve("multi_append.txt").readText())
        }
    }

    @Nested
    inner class ListFilesToolTests {

        @Test
        fun `list_files shows directory contents`() = runTest {
            tempDir.resolve("file1.txt").writeText("1")
            tempDir.resolve("file2.txt").writeText("2")
            tempDir.resolve("subdir").createDirectory()

            val tool = ListFilesTool(toolExecutor)
            val result = tool.execute(ListFilesTool.Args(path = "."))

            assertTrue(result.contains("file1.txt"))
            assertTrue(result.contains("file2.txt"))
            assertTrue(result.contains("subdir/"))
        }

        @Test
        fun `list_files returns empty message for empty directory`() = runTest {
            tempDir.resolve("empty").createDirectory()

            val tool = ListFilesTool(toolExecutor)
            val result = tool.execute(ListFilesTool.Args(path = "empty"))

            assertTrue(result.contains("empty"))
        }

        @Test
        fun `list_files recursive shows nested files`() = runTest {
            tempDir.resolve("top.txt").writeText("top")
            val subdir = tempDir.resolve("nested")
            subdir.createDirectory()
            subdir.resolve("deep.txt").writeText("deep")

            val tool = ListFilesTool(toolExecutor)
            val result = tool.execute(ListFilesTool.Args(path = ".", recursive = true))

            assertTrue(result.contains("top.txt"))
            assertTrue(result.contains("deep.txt"))
        }

        @Test
        fun `list_files returns error for nonexistent directory`() = runTest {
            val tool = ListFilesTool(toolExecutor)
            val result = tool.execute(ListFilesTool.Args(path = "nonexistent"))

            assertTrue(result.startsWith("Error:"))
            assertTrue(result.contains("not found"))
        }

        @Test
        fun `list_files returns error for file path`() = runTest {
            tempDir.resolve("afile.txt").writeText("content")

            val tool = ListFilesTool(toolExecutor)
            val result = tool.execute(ListFilesTool.Args(path = "afile.txt"))

            assertTrue(result.startsWith("Error:"))
            assertTrue(result.contains("Not a directory"))
        }
    }

    @Nested
    inner class SearchFilesToolTests {

        @Test
        fun `search_files finds matching files`() = runTest {
            tempDir.resolve("test1.kt").writeText("kotlin 1")
            tempDir.resolve("test2.kt").writeText("kotlin 2")
            tempDir.resolve("test.txt").writeText("text")

            val tool = SearchFilesTool(toolExecutor)
            val result = tool.execute(SearchFilesTool.Args(path = ".", pattern = "*.kt"))

            assertTrue(result.contains("test1.kt"))
            assertTrue(result.contains("test2.kt"))
            assertTrue(result.contains("2 file(s)"))
        }

        @Test
        fun `search_files finds files in subdirectories`() = runTest {
            val subdir = tempDir.resolve("src")
            subdir.createDirectory()
            subdir.resolve("Main.java").writeText("java")
            tempDir.resolve("build.gradle").writeText("gradle")

            val tool = SearchFilesTool(toolExecutor)
            val result = tool.execute(SearchFilesTool.Args(path = ".", pattern = "*.java"))

            assertTrue(result.contains("Main.java"))
            assertTrue(result.contains("1 file(s)"))
        }

        @Test
        fun `search_files returns no matches message`() = runTest {
            tempDir.resolve("file.txt").writeText("text")

            val tool = SearchFilesTool(toolExecutor)
            val result = tool.execute(SearchFilesTool.Args(path = ".", pattern = "*.xyz"))

            assertTrue(result.contains("No files found"))
        }
    }

    @Nested
    inner class FileExistsToolTests {

        @Test
        fun `file_exists returns true for existing file`() = runTest {
            tempDir.resolve("exists.txt").writeText("content")

            val tool = FileExistsTool(toolExecutor)
            val result = tool.execute(FileExistsTool.Args(path = "exists.txt"))

            assertTrue(result.contains("exists"))
            assertTrue(result.contains("exists.txt"))
        }

        @Test
        fun `file_exists returns true for existing directory`() = runTest {
            tempDir.resolve("existsdir").createDirectory()

            val tool = FileExistsTool(toolExecutor)
            val result = tool.execute(FileExistsTool.Args(path = "existsdir"))

            assertTrue(result.contains("exists"))
        }

        @Test
        fun `file_exists returns false for nonexistent path`() = runTest {
            val tool = FileExistsTool(toolExecutor)
            val result = tool.execute(FileExistsTool.Args(path = "noexist"))

            assertTrue(result.contains("does not exist"))
        }
    }

    @Nested
    inner class DeleteFileToolTests {

        @Test
        fun `delete_file removes existing file`() = runTest {
            val testFile = tempDir.resolve("todelete.txt")
            testFile.writeText("delete me")

            val tool = DeleteFileTool(toolExecutor)
            val result = tool.execute(DeleteFileTool.Args(path = "todelete.txt"))

            assertTrue(result.contains("deleted"))
            assertTrue(!testFile.exists())
        }

        @Test
        fun `delete_file succeeds for nonexistent file`() = runTest {
            val tool = DeleteFileTool(toolExecutor)
            val result = tool.execute(DeleteFileTool.Args(path = "nonexistent.txt"))

            assertTrue(result.contains("deleted"))
        }
    }

    @Nested
    inner class TreeOutputTests {

        @Test
        fun `list_files produces tree output`() = runTest {
            tempDir.resolve("small.txt").writeText("X".repeat(100))

            val tool = ListFilesTool(toolExecutor)
            val result = tool.execute(ListFilesTool.Args(path = "."))

            assertTrue(result.contains("└── small.txt"))
        }
    }
}
