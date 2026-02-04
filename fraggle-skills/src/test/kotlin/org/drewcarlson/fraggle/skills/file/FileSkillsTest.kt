package org.drewcarlson.fraggle.skills.file

import kotlinx.coroutines.test.runTest
import org.drewcarlson.fraggle.sandbox.PermissiveSandbox
import org.drewcarlson.fraggle.skill.SkillParameters
import org.drewcarlson.fraggle.skill.SkillResult
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileSkillsTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var sandbox: PermissiveSandbox

    @BeforeEach
    fun setup() {
        sandbox = PermissiveSandbox(tempDir)
    }

    @Nested
    inner class CreateSkillsTests {

        @Test
        fun `create returns seven file skills`() {
            val skills = FileSkills.create(sandbox)

            assertEquals(7, skills.size)
            assertTrue(skills.any { it.name == "read_file" })
            assertTrue(skills.any { it.name == "write_file" })
            assertTrue(skills.any { it.name == "list_files" })
            assertTrue(skills.any { it.name == "search_files" })
            assertTrue(skills.any { it.name == "file_exists" })
            assertTrue(skills.any { it.name == "delete_file" })
            assertTrue(skills.any { it.name == "append_file" })
        }
    }

    @Nested
    inner class ReadFileSkillTests {

        @Test
        fun `read_file returns file contents`() = runTest {
            val testFile = tempDir.resolve("test.txt")
            testFile.writeText("Hello, World!")

            val skill = FileSkills.readFile(sandbox)
            val params = SkillParameters(mapOf("path" to "test.txt"))

            val result = skill.execute(params)

            assertIs<SkillResult.Success>(result)
            assertEquals("Hello, World!", result.output)
        }

        @Test
        fun `read_file with absolute path works`() = runTest {
            val testFile = tempDir.resolve("absolute.txt")
            testFile.writeText("Absolute path content")

            val skill = FileSkills.readFile(sandbox)
            val params = SkillParameters(mapOf("path" to testFile.toString()))

            val result = skill.execute(params)

            assertIs<SkillResult.Success>(result)
            assertEquals("Absolute path content", result.output)
        }

        @Test
        fun `read_file respects max_lines parameter`() = runTest {
            val testFile = tempDir.resolve("multiline.txt")
            testFile.writeText("Line 1\nLine 2\nLine 3\nLine 4\nLine 5")

            val skill = FileSkills.readFile(sandbox)
            val params = SkillParameters(mapOf("path" to "multiline.txt", "max_lines" to 3))

            val result = skill.execute(params)

            assertIs<SkillResult.Success>(result)
            assertEquals("Line 1\nLine 2\nLine 3", result.output)
        }

        @Test
        fun `read_file returns error for nonexistent file`() = runTest {
            val skill = FileSkills.readFile(sandbox)
            val params = SkillParameters(mapOf("path" to "nonexistent.txt"))

            val result = skill.execute(params)

            assertIs<SkillResult.Error>(result)
            assertTrue(result.message.contains("not found"))
        }

        @Test
        fun `read_file returns error for directory`() = runTest {
            val dir = tempDir.resolve("subdir")
            dir.createDirectory()

            val skill = FileSkills.readFile(sandbox)
            val params = SkillParameters(mapOf("path" to "subdir"))

            val result = skill.execute(params)

            assertIs<SkillResult.Error>(result)
            assertTrue(result.message.contains("Not a regular file"))
        }
    }

    @Nested
    inner class WriteFileSkillTests {

        @Test
        fun `write_file creates new file`() = runTest {
            val skill = FileSkills.writeFile(sandbox)
            val params = SkillParameters(mapOf("path" to "new.txt", "content" to "New content"))

            val result = skill.execute(params)

            assertIs<SkillResult.Success>(result)
            assertTrue(result.output.contains("written successfully"))
            assertEquals("New content", tempDir.resolve("new.txt").readText())
        }

        @Test
        fun `write_file overwrites existing file`() = runTest {
            val testFile = tempDir.resolve("existing.txt")
            testFile.writeText("Original content")

            val skill = FileSkills.writeFile(sandbox)
            val params = SkillParameters(mapOf("path" to "existing.txt", "content" to "Replaced content"))

            val result = skill.execute(params)

            assertIs<SkillResult.Success>(result)
            assertEquals("Replaced content", testFile.readText())
        }

        @Test
        fun `write_file creates parent directories`() = runTest {
            val skill = FileSkills.writeFile(sandbox)
            val params = SkillParameters(mapOf("path" to "a/b/c/deep.txt", "content" to "Deep content"))

            val result = skill.execute(params)

            assertIs<SkillResult.Success>(result)
            assertTrue(tempDir.resolve("a/b/c/deep.txt").exists())
            assertEquals("Deep content", tempDir.resolve("a/b/c/deep.txt").readText())
        }
    }

    @Nested
    inner class AppendFileSkillTests {

        @Test
        fun `append_file adds content to existing file`() = runTest {
            val testFile = tempDir.resolve("append.txt")
            testFile.writeText("First line\n")

            val skill = FileSkills.appendFile(sandbox)
            val params = SkillParameters(mapOf("path" to "append.txt", "content" to "Second line"))

            val result = skill.execute(params)

            assertIs<SkillResult.Success>(result)
            assertTrue(result.output.contains("appended"))
            assertEquals("First line\nSecond line", testFile.readText())
        }

        @Test
        fun `append_file appends multiple times`() = runTest {
            val testFile = tempDir.resolve("multi_append.txt")
            testFile.writeText("Start")

            val skill = FileSkills.appendFile(sandbox)

            skill.execute(SkillParameters(mapOf("path" to "multi_append.txt", "content" to " - Middle")))
            skill.execute(SkillParameters(mapOf("path" to "multi_append.txt", "content" to " - End")))

            assertEquals("Start - Middle - End", testFile.readText())
        }
    }

    @Nested
    inner class ListFilesSkillTests {

        @Test
        fun `list_files shows directory contents`() = runTest {
            tempDir.resolve("file1.txt").writeText("1")
            tempDir.resolve("file2.txt").writeText("2")
            tempDir.resolve("subdir").createDirectory()

            val skill = FileSkills.listFiles(sandbox)
            val params = SkillParameters(mapOf("path" to "."))

            val result = skill.execute(params)

            assertIs<SkillResult.Success>(result)
            assertTrue(result.output.contains("file1.txt"))
            assertTrue(result.output.contains("file2.txt"))
            assertTrue(result.output.contains("[DIR]"))
            assertTrue(result.output.contains("subdir"))
        }

        @Test
        fun `list_files returns empty message for empty directory`() = runTest {
            val emptyDir = tempDir.resolve("empty")
            emptyDir.createDirectory()

            val skill = FileSkills.listFiles(sandbox)
            val params = SkillParameters(mapOf("path" to "empty"))

            val result = skill.execute(params)

            assertIs<SkillResult.Success>(result)
            assertTrue(result.output.contains("empty"))
        }

        @Test
        fun `list_files recursive shows nested files`() = runTest {
            tempDir.resolve("top.txt").writeText("top")
            val subdir = tempDir.resolve("nested")
            subdir.createDirectory()
            subdir.resolve("deep.txt").writeText("deep")

            val skill = FileSkills.listFiles(sandbox)
            val params = SkillParameters(mapOf("path" to ".", "recursive" to true))

            val result = skill.execute(params)

            assertIs<SkillResult.Success>(result)
            assertTrue(result.output.contains("top.txt"))
            assertTrue(result.output.contains("deep.txt"))
        }

        @Test
        fun `list_files returns error for nonexistent directory`() = runTest {
            val skill = FileSkills.listFiles(sandbox)
            val params = SkillParameters(mapOf("path" to "nonexistent"))

            val result = skill.execute(params)

            assertIs<SkillResult.Error>(result)
            assertTrue(result.message.contains("not found"))
        }

        @Test
        fun `list_files returns error for file path`() = runTest {
            tempDir.resolve("afile.txt").writeText("content")

            val skill = FileSkills.listFiles(sandbox)
            val params = SkillParameters(mapOf("path" to "afile.txt"))

            val result = skill.execute(params)

            assertIs<SkillResult.Error>(result)
            assertTrue(result.message.contains("Not a directory"))
        }
    }

    @Nested
    inner class SearchFilesSkillTests {

        @Test
        fun `search_files finds matching files`() = runTest {
            tempDir.resolve("test1.kt").writeText("kotlin 1")
            tempDir.resolve("test2.kt").writeText("kotlin 2")
            tempDir.resolve("test.txt").writeText("text")

            val skill = FileSkills.searchFiles(sandbox)
            val params = SkillParameters(mapOf("path" to ".", "pattern" to "*.kt"))

            val result = skill.execute(params)

            assertIs<SkillResult.Success>(result)
            assertTrue(result.output.contains("test1.kt"))
            assertTrue(result.output.contains("test2.kt"))
            assertTrue(result.output.contains("2 file(s)"))
        }

        @Test
        fun `search_files finds files in subdirectories`() = runTest {
            val subdir = tempDir.resolve("src")
            subdir.createDirectory()
            subdir.resolve("Main.java").writeText("java")
            tempDir.resolve("build.gradle").writeText("gradle")

            val skill = FileSkills.searchFiles(sandbox)
            val params = SkillParameters(mapOf("path" to ".", "pattern" to "*.java"))

            val result = skill.execute(params)

            assertIs<SkillResult.Success>(result)
            assertTrue(result.output.contains("Main.java"))
            assertTrue(result.output.contains("1 file(s)"))
        }

        @Test
        fun `search_files returns no matches message`() = runTest {
            tempDir.resolve("file.txt").writeText("text")

            val skill = FileSkills.searchFiles(sandbox)
            val params = SkillParameters(mapOf("path" to ".", "pattern" to "*.xyz"))

            val result = skill.execute(params)

            assertIs<SkillResult.Success>(result)
            assertTrue(result.output.contains("No files found"))
        }

        @Test
        fun `search_files with question mark wildcard`() = runTest {
            tempDir.resolve("test1.txt").writeText("1")
            tempDir.resolve("test2.txt").writeText("2")
            tempDir.resolve("test10.txt").writeText("10")

            val skill = FileSkills.searchFiles(sandbox)
            val params = SkillParameters(mapOf("path" to ".", "pattern" to "test?.txt"))

            val result = skill.execute(params)

            assertIs<SkillResult.Success>(result)
            assertTrue(result.output.contains("test1.txt"))
            assertTrue(result.output.contains("test2.txt"))
            // test10.txt should not match (? matches single character)
        }
    }

    @Nested
    inner class FileExistsSkillTests {

        @Test
        fun `file_exists returns true for existing file`() = runTest {
            tempDir.resolve("exists.txt").writeText("content")

            val skill = FileSkills.fileExists(sandbox)
            val params = SkillParameters(mapOf("path" to "exists.txt"))

            val result = skill.execute(params)

            assertIs<SkillResult.Success>(result)
            assertTrue(result.output.contains("exists"))
            assertTrue(result.output.contains("exists.txt"))
        }

        @Test
        fun `file_exists returns true for existing directory`() = runTest {
            tempDir.resolve("existsdir").createDirectory()

            val skill = FileSkills.fileExists(sandbox)
            val params = SkillParameters(mapOf("path" to "existsdir"))

            val result = skill.execute(params)

            assertIs<SkillResult.Success>(result)
            assertTrue(result.output.contains("exists"))
        }

        @Test
        fun `file_exists returns false for nonexistent path`() = runTest {
            val skill = FileSkills.fileExists(sandbox)
            val params = SkillParameters(mapOf("path" to "noexist"))

            val result = skill.execute(params)

            assertIs<SkillResult.Success>(result)
            assertTrue(result.output.contains("does not exist"))
        }
    }

    @Nested
    inner class DeleteFileSkillTests {

        @Test
        fun `delete_file removes existing file`() = runTest {
            val testFile = tempDir.resolve("todelete.txt")
            testFile.writeText("delete me")

            val skill = FileSkills.deleteFile(sandbox)
            val params = SkillParameters(mapOf("path" to "todelete.txt"))

            val result = skill.execute(params)

            assertIs<SkillResult.Success>(result)
            assertTrue(result.output.contains("deleted"))
            assertTrue(!testFile.exists())
        }

        @Test
        fun `delete_file succeeds for nonexistent file`() = runTest {
            val skill = FileSkills.deleteFile(sandbox)
            val params = SkillParameters(mapOf("path" to "nonexistent.txt"))

            val result = skill.execute(params)

            assertIs<SkillResult.Success>(result)
            assertTrue(result.output.contains("deleted"))
        }
    }

    @Nested
    inner class FormatSizeTests {

        @Test
        fun `list_files shows file sizes correctly`() = runTest {
            // Create a file with known size
            val smallFile = tempDir.resolve("small.txt")
            smallFile.writeText("X".repeat(100)) // 100 bytes

            val skill = FileSkills.listFiles(sandbox)
            val params = SkillParameters(mapOf("path" to "."))

            val result = skill.execute(params)

            assertIs<SkillResult.Success>(result)
            assertTrue(result.output.contains("B"))
        }
    }
}
