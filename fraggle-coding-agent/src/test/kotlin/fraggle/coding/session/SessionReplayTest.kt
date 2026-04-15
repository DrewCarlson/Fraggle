package fraggle.coding.session

import fraggle.agent.message.AgentMessage
import fraggle.agent.message.ContentPart
import fraggle.agent.message.StopReason
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionReplayTest {

    private fun managerAt(dir: Path): SessionManager {
        val project = dir.resolve("proj").also { it.createDirectories() }
        return SessionManager(
            sessionsRoot = dir.resolve("sessions"),
            projectRoot = project,
        )
    }

    @Test
    fun `empty tree replays to empty list`() {
        assertEquals(emptyList(), SessionTree.EMPTY.replayCurrentBranch())
    }

    @Test
    fun `root-only session replays to empty list`(@TempDir dir: Path) {
        val manager = managerAt(dir)
        val session = manager.createNew(model = "m")
        // Just the root entry exists — no conversation yet
        assertEquals(emptyList(), session.tree.replayCurrentBranch())
    }

    @Test
    fun `single user turn round-trips to AgentMessage User`(@TempDir dir: Path) {
        val manager = managerAt(dir)
        val session = manager.createNew(model = "m")
        val rootId = session.tree.root!!.id
        session.record(
            SessionEntry(
                id = "u1",
                parentId = rootId,
                timestampMs = 100L,
                payload = SessionEntry.Payload.User(text = "hello there", attachments = emptyList()),
            ),
        )

        val replayed = session.tree.replayCurrentBranch()
        assertEquals(1, replayed.size)
        val user = assertIs<AgentMessage.User>(replayed.single())
        val text = user.content.filterIsInstance<ContentPart.Text>().single().text
        assertEquals("hello there", text)
    }

    @Test
    fun `full conversation with tool calls round-trips faithfully`(@TempDir dir: Path) {
        val manager = managerAt(dir)
        val session = manager.createNew(model = "m")
        val rootId = session.tree.root!!.id

        session.record(SessionEntry("u1", rootId, 1L,
            SessionEntry.Payload.User("what's in foo.kt?")))

        session.record(SessionEntry("a1", "u1", 2L,
            SessionEntry.Payload.Assistant(
                text = "I'll read the file",
                toolCalls = listOf(
                    SessionEntry.ToolCallSnapshot(
                        id = "call-1",
                        name = "read_file",
                        argsJson = """{"path":"foo.kt"}""",
                    ),
                ),
                stopReason = "STOP",
                errorMessage = null,
                usage = SessionEntry.UsageSnapshot(inputTokens = 10, outputTokens = 5, totalTokens = 15),
            ),
        ))

        session.record(SessionEntry("t1", "a1", 3L,
            SessionEntry.Payload.ToolResult(
                callId = "call-1",
                toolName = "read_file",
                output = "fun main() { println(\"hello\") }",
                error = null,
            ),
        ))

        session.record(SessionEntry("a2", "t1", 4L,
            SessionEntry.Payload.Assistant(
                text = "The file defines a main function that prints hello.",
                toolCalls = emptyList(),
                stopReason = "STOP",
                errorMessage = null,
                usage = null,
            ),
        ))

        val replayed = session.tree.replayCurrentBranch()

        // User + Assistant + ToolResult + Assistant = 4 messages (root is skipped)
        assertEquals(4, replayed.size)

        assertIs<AgentMessage.User>(replayed[0])

        val a1 = assertIs<AgentMessage.Assistant>(replayed[1])
        assertEquals("I'll read the file", a1.textContent)
        assertEquals(1, a1.toolCalls.size)
        assertEquals("call-1", a1.toolCalls[0].id)
        assertEquals("read_file", a1.toolCalls[0].name)
        assertEquals("""{"path":"foo.kt"}""", a1.toolCalls[0].arguments)
        assertEquals(StopReason.STOP, a1.stopReason)
        assertEquals(15, a1.usage?.totalTokens)

        val tr = assertIs<AgentMessage.ToolResult>(replayed[2])
        assertEquals("call-1", tr.toolCallId)
        assertEquals("read_file", tr.toolName)
        assertEquals("fun main() { println(\"hello\") }", tr.textContent)
        assertTrue(!tr.isError)

        val a2 = assertIs<AgentMessage.Assistant>(replayed[3])
        assertEquals("The file defines a main function that prints hello.", a2.textContent)
        assertNull(a2.usage)
    }

    @Test
    fun `Meta entries are skipped during replay`(@TempDir dir: Path) {
        val manager = managerAt(dir)
        val session = manager.createNew(model = "m")
        val rootId = session.tree.root!!.id
        session.record(SessionEntry("u1", rootId, 1L, SessionEntry.Payload.User("hi")))
        session.record(SessionEntry("m1", "u1", 2L, SessionEntry.Payload.Meta(label = "compaction", summary = "skipped")))
        session.record(SessionEntry("a1", "m1", 3L, SessionEntry.Payload.Assistant(text = "hello")))

        val replayed = session.tree.replayCurrentBranch()
        // Meta skipped → User + Assistant only
        assertEquals(2, replayed.size)
        assertIs<AgentMessage.User>(replayed[0])
        assertIs<AgentMessage.Assistant>(replayed[1])
    }

    @Test
    fun `unknown stop reason falls back to STOP`(@TempDir dir: Path) {
        val manager = managerAt(dir)
        val session = manager.createNew(model = "m")
        val rootId = session.tree.root!!.id
        session.record(SessionEntry("a1", rootId, 1L,
            SessionEntry.Payload.Assistant(
                text = "response",
                stopReason = "FROM_THE_FUTURE",
            ),
        ))
        val replayed = session.tree.replayCurrentBranch()
        val a = assertIs<AgentMessage.Assistant>(replayed.single())
        assertEquals(StopReason.STOP, a.stopReason)
    }

    @Test
    fun `tool result with error flag round-trips`(@TempDir dir: Path) {
        val manager = managerAt(dir)
        val session = manager.createNew(model = "m")
        val rootId = session.tree.root!!.id
        session.record(SessionEntry("t1", rootId, 1L,
            SessionEntry.Payload.ToolResult(
                callId = "c",
                toolName = "execute_command",
                output = "command not found",
                error = "exit code 127",
            ),
        ))
        val replayed = session.tree.replayCurrentBranch()
        val tr = assertIs<AgentMessage.ToolResult>(replayed.single())
        assertTrue(tr.isError, "error field present → isError should be true")
    }
}
