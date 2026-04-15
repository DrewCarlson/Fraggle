package fraggle.coding.session

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionFileTest {

    @Nested
    inner class AppendAndRead {
        @Test
        fun `append creates file and parent dirs on first write`(@TempDir dir: Path) {
            val file = SessionFile(dir.resolve("nested/sub/session.jsonl"))
            assertTrue(!file.path.exists())

            file.append(userEntry("hello"))

            assertTrue(file.path.exists())
            assertEquals(1, file.readAll().size)
        }

        @Test
        fun `append writes one json object per line`(@TempDir dir: Path) {
            val file = SessionFile(dir.resolve("s.jsonl"))
            file.append(userEntry("one"))
            file.append(userEntry("two"))
            file.append(userEntry("three"))

            val text = file.path.readText()
            val lines = text.trim().split('\n')
            assertEquals(3, lines.size)
            assertTrue(lines.all { it.startsWith("{") && it.endsWith("}") })
        }

        @Test
        fun `readAll returns entries in write order`(@TempDir dir: Path) {
            val file = SessionFile(dir.resolve("s.jsonl"))
            val first = userEntry("first", id = "a")
            val second = userEntry("second", id = "b", parentId = "a")
            val third = userEntry("third", id = "c", parentId = "b")

            file.append(first)
            file.append(second)
            file.append(third)

            val read = file.readAll()
            assertEquals(listOf("a", "b", "c"), read.map { it.id })
            assertEquals(listOf("first", "second", "third"), read.map { (it.payload as SessionEntry.Payload.User).text })
        }

        @Test
        fun `readAll returns empty list for non-existent file`(@TempDir dir: Path) {
            val file = SessionFile(dir.resolve("never-written.jsonl"))
            assertEquals(emptyList(), file.readAll())
        }

        @Test
        fun `readAll skips blank lines`(@TempDir dir: Path) {
            val path = dir.resolve("s.jsonl")
            path.createFile()
            path.appendText("\n")
            path.appendText("""{"id":"a","parentId":null,"timestampMs":1,"payload":{"kind":"user","text":"x"}}""" + "\n")
            path.appendText("\n\n")
            path.appendText("""{"id":"b","parentId":"a","timestampMs":2,"payload":{"kind":"user","text":"y"}}""" + "\n")
            path.appendText("\n")

            val entries = SessionFile(path).readAll()
            assertEquals(2, entries.size)
            assertEquals(listOf("a", "b"), entries.map { it.id })
        }
    }

    @Nested
    inner class SchemaVersioning {
        @Test
        fun `reading a future schema version throws`(@TempDir dir: Path) {
            val path = dir.resolve("future.jsonl")
            path.createFile()
            // schemaVersion far in the future
            path.appendText("""{"id":"a","parentId":null,"timestampMs":1,"payload":{"kind":"user","text":"x"},"schemaVersion":999}""" + "\n")

            assertThrows<SessionReadException> {
                SessionFile(path).readAll()
            }
        }

        @Test
        fun `writing a future schema version throws`(@TempDir dir: Path) {
            val file = SessionFile(dir.resolve("s.jsonl"))
            val entry = userEntry("x").copy(schemaVersion = 999)
            assertThrows<IllegalArgumentException> {
                file.append(entry)
            }
        }
    }

    @Nested
    inner class MalformedInput {
        @Test
        fun `corrupt line surfaces as SessionReadException with line number`(@TempDir dir: Path) {
            val path = dir.resolve("corrupt.jsonl")
            path.createFile()
            path.appendText("""{"id":"a","parentId":null,"timestampMs":1,"payload":{"kind":"user","text":"ok"}}""" + "\n")
            path.appendText("this is not json\n")
            path.appendText("""{"id":"b","parentId":"a","timestampMs":2,"payload":{"kind":"user","text":"never reached"}}""" + "\n")

            val ex = assertThrows<SessionReadException> {
                SessionFile(path).readAll()
            }
            assertTrue(ex.message!!.contains("line 2"), "expected line number in message, got: ${ex.message}")
        }
    }

    @Nested
    inner class AllPayloadKinds {
        @Test
        fun `round trip every payload kind`(@TempDir dir: Path) {
            val file = SessionFile(dir.resolve("kinds.jsonl"))
            val entries = listOf(
                SessionEntry("root", null, 1000L, SessionEntry.Payload.Root("sess1", "/proj", "qwen3", 1000L)),
                SessionEntry("u1", "root", 1100L, SessionEntry.Payload.User("hello", listOf("foo.kt"))),
                SessionEntry("t1", "u1", 1200L, SessionEntry.Payload.ToolResult("call-1", "read_file", "contents", null, 42L)),
                SessionEntry("m1", "t1", 1300L, SessionEntry.Payload.Meta(label = "checkpoint", summary = "refactor done")),
            )
            entries.forEach(file::append)

            val read = file.readAll()
            assertEquals(entries.map { it.id }, read.map { it.id })
            assertEquals(entries.map { it.payload }, read.map { it.payload })
        }
    }

    private fun userEntry(text: String, id: String = "u-${text}", parentId: String? = null): SessionEntry =
        SessionEntry(
            id = id,
            parentId = parentId,
            timestampMs = 0L,
            payload = SessionEntry.Payload.User(text),
        )
}
