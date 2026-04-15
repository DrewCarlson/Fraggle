package fraggle.coding.session

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionManagerTest {

    @Nested
    inner class CreateNew {
        @Test
        fun `createNew writes a root entry with session metadata`(@TempDir dir: Path) {
            val project = dir.resolve("proj").also { it.createDirectories() }
            val sessions = dir.resolve("sessions")
            val manager = deterministicManager(sessions, project)

            val session = manager.createNew(model = "qwen3-test")

            val entries = session.file.readAll()
            assertEquals(1, entries.size)
            val root = entries.single()
            assertNull(root.parentId)
            val payload = root.payload as SessionEntry.Payload.Root
            assertEquals("qwen3-test", payload.model)
            assertTrue(payload.projectRoot.endsWith("proj"))
        }

        @Test
        fun `createNew puts the file under a deterministic project directory`(@TempDir dir: Path) {
            val project = dir.resolve("proj").also { it.createDirectories() }
            val sessions = dir.resolve("sessions")
            val manager = SessionManager(sessions, project)

            val s1 = manager.createNew(model = "m")
            val s2 = manager.createNew(model = "m")

            // Both sessions land in the same project-hashed subdirectory
            assertEquals(s1.file.path.parent, s2.file.path.parent)
            // And the dir lives under the sessions root
            assertTrue(s1.file.path.parent.parent == sessions)
        }

        @Test
        fun `two managers with the same project path use the same subdirectory`(@TempDir dir: Path) {
            val project = dir.resolve("proj").also { it.createDirectories() }
            val sessions = dir.resolve("sessions")
            val m1 = SessionManager(sessions, project)
            val m2 = SessionManager(sessions, project)
            assertEquals(m1.projectDir, m2.projectDir)
        }

        @Test
        fun `different project paths use different subdirectories`(@TempDir dir: Path) {
            val a = dir.resolve("a").also { it.createDirectories() }
            val b = dir.resolve("b").also { it.createDirectories() }
            val sessions = dir.resolve("sessions")
            val ma = SessionManager(sessions, a)
            val mb = SessionManager(sessions, b)
            assertNotEquals(ma.projectDir, mb.projectDir)
        }
    }

    @Nested
    inner class OpenAndList {
        @Test
        fun `open reconstructs the tree from disk`(@TempDir dir: Path) {
            val project = dir.resolve("proj").also { it.createDirectories() }
            val sessions = dir.resolve("sessions")
            val manager = SessionManager(sessions, project)

            val created = manager.createNew(model = "m")
            created.record(
                SessionEntry(
                    id = "u1",
                    parentId = created.tree.root!!.id,
                    timestampMs = 1L,
                    payload = SessionEntry.Payload.User("hi"),
                ),
            )

            val reopened = manager.open(created.file.path)
            assertEquals(2, reopened.tree.size)
            assertEquals(listOf(created.tree.root!!.id, "u1"), reopened.tree.currentBranch().map { it.id })
        }

        @Test
        fun `list returns sessions sorted by last modified desc`(@TempDir dir: Path) {
            val project = dir.resolve("proj").also { it.createDirectories() }
            val sessions = dir.resolve("sessions")
            val manager = SessionManager(sessions, project)

            val a = manager.createNew(model = "m")
            Thread.sleep(10)
            val b = manager.createNew(model = "m")
            Thread.sleep(10)
            val c = manager.createNew(model = "m")

            val listed = manager.list()
            assertEquals(3, listed.size)
            // Most recent first
            assertEquals(c.file.path, listed[0].file)
            assertEquals(b.file.path, listed[1].file)
            assertEquals(a.file.path, listed[2].file)
        }

        @Test
        fun `list is empty when no sessions exist`(@TempDir dir: Path) {
            val project = dir.resolve("proj").also { it.createDirectories() }
            val sessions = dir.resolve("sessions")
            val manager = SessionManager(sessions, project)
            assertEquals(emptyList(), manager.list())
            assertNull(manager.mostRecent())
        }

        @Test
        fun `mostRecent returns the latest session`(@TempDir dir: Path) {
            val project = dir.resolve("proj").also { it.createDirectories() }
            val sessions = dir.resolve("sessions")
            val manager = SessionManager(sessions, project)
            manager.createNew(model = "m")
            Thread.sleep(10)
            val latest = manager.createNew(model = "m")
            assertEquals(latest.file.path, manager.mostRecent()?.file)
        }
    }

    @Nested
    inner class Fork {
        @Test
        fun `fork copies the branch up to the chosen entry into a new file`(@TempDir dir: Path) {
            val project = dir.resolve("proj").also { it.createDirectories() }
            val sessions = dir.resolve("sessions")
            val manager = SessionManager(sessions, project)

            val original = manager.createNew(model = "m")
            val root = original.tree.root!!
            original.record(SessionEntry("u1", root.id, 1L, SessionEntry.Payload.User("one")))
            original.record(SessionEntry("u2", "u1", 2L, SessionEntry.Payload.User("two")))
            original.record(SessionEntry("u3", "u2", 3L, SessionEntry.Payload.User("three")))

            // Fork at u2 — the new session should contain root, u1, u2 (not u3)
            val forked = manager.fork(original, original.tree.find("u2")!!)

            val ids = forked.tree.currentBranch().map { it.id }
            assertEquals(listOf(root.id, "u1", "u2"), ids)
        }

        @Test
        fun `fork creates a new session file with a new session id`(@TempDir dir: Path) {
            val project = dir.resolve("proj").also { it.createDirectories() }
            val sessions = dir.resolve("sessions")
            val manager = SessionManager(sessions, project)

            val original = manager.createNew(model = "m")
            val forked = manager.fork(original, original.tree.root!!)

            assertNotEquals(original.id, forked.id)
            assertNotEquals(original.file.path, forked.file.path)
            assertTrue(forked.file.path.exists())
            // Forked root still has kind=root
            val forkedRoot = forked.tree.root!!
            val payload = forkedRoot.payload as SessionEntry.Payload.Root
            assertEquals(forked.id, payload.sessionId)
        }
    }

    @Nested
    inner class RecordIntegrity {
        @Test
        fun `record persists to disk AND updates the in-memory tree`(@TempDir dir: Path) {
            val project = dir.resolve("proj").also { it.createDirectories() }
            val sessions = dir.resolve("sessions")
            val manager = SessionManager(sessions, project)

            val session = manager.createNew(model = "m")
            val rootId = session.tree.root!!.id

            val id = session.record(
                SessionEntry("u1", rootId, 1L, SessionEntry.Payload.User("hi")),
            )
            assertEquals("u1", id)
            assertEquals(2, session.tree.size)
            assertNotNull(session.tree.find("u1"))

            // And it's on disk
            val fresh = SessionFile(session.file.path).readAll()
            assertEquals(listOf(rootId, "u1"), fresh.map { it.id })
        }
    }

    /**
     * Manager with monotonically-increasing uuid() for deterministic ids in
     * tests that care about ordering.
     */
    private fun deterministicManager(sessions: Path, project: Path): SessionManager {
        val counter = AtomicLong(0)
        return SessionManager(
            sessionsRoot = sessions,
            projectRoot = project,
            clock = { counter.get() * 1000L },
            uuid = { "id-${counter.incrementAndGet()}" },
        )
    }
}
