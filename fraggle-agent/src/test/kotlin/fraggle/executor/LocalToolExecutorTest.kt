package fraggle.executor

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalToolExecutorTest {

    @TempDir
    lateinit var tempDir: Path

    @Nested
    inner class WorkDir {
        @Test
        fun `returns configured work directory`() {
            val executor = LocalToolExecutor(tempDir)

            assertEquals(tempDir, executor.workDir())
        }
    }

    @Nested
    inner class ResolvePath {
        @Test
        fun `resolves relative path against work directory`() {
            val executor = LocalToolExecutor(tempDir)

            val resolved = executor.resolvePath("subdir/file.txt")

            assertEquals(tempDir.resolve("subdir/file.txt").normalize(), resolved)
        }

        @Test
        fun `returns absolute path as-is`() {
            val executor = LocalToolExecutor(tempDir)
            val absolutePath = "/tmp/some/absolute/path.txt"

            val resolved = executor.resolvePath(absolutePath)

            assertTrue(resolved.isAbsolute)
            assertEquals(Path(absolutePath).normalize(), resolved)
        }

        @Test
        fun `normalizes relative paths with dot segments`() {
            val executor = LocalToolExecutor(tempDir)

            val resolved = executor.resolvePath("subdir/../other/./file.txt")

            assertEquals(tempDir.resolve("other/file.txt").normalize(), resolved)
        }

        @Test
        fun `normalizes absolute paths`() {
            val executor = LocalToolExecutor(tempDir)

            val resolved = executor.resolvePath("/tmp/foo/../bar/./baz.txt")

            assertEquals(Path("/tmp/bar/baz.txt"), resolved)
        }

        @Test
        fun `handles simple filename`() {
            val executor = LocalToolExecutor(tempDir)

            val resolved = executor.resolvePath("file.txt")

            assertEquals(tempDir.resolve("file.txt"), resolved)
        }
    }
}
