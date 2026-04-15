package fraggle.tracing

import fraggle.models.TraceEventRecord
import kotlin.time.Clock
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class TraceStoreTest {

    private val store = TraceStore(maxSessions = 5)

    private fun createEvent(sessionId: String, eventType: String = "test", phase: String = "started"): TraceEventRecord {
        return TraceEventRecord(
            id = Uuid.random().toString(),
            sessionId = sessionId,
            timestamp = Clock.System.now(),
            eventType = eventType,
            phase = phase,
        )
    }

    @Nested
    inner class StartSession {
        @Test
        fun `creates session with correct fields`() {
            val session = store.startSession("s1", "chat1")

            assertEquals("s1", session.id)
            assertEquals("chat1", session.chatId)
            assertEquals(0, session.eventCount)
            assertEquals("running", session.status)
            assertNotNull(session.startTime)
            assertNull(session.endTime)
        }

        @Test
        fun `session is retrievable after creation`() {
            store.startSession("s1", "chat1")

            val retrieved = store.getSession("s1")
            assertNotNull(retrieved)
            assertEquals("s1", retrieved.id)
        }

        @Test
        fun `multiple sessions are independent`() {
            store.startSession("s1", "chat1")
            store.startSession("s2", "chat2")

            assertEquals("chat1", store.getSession("s1")!!.chatId)
            assertEquals("chat2", store.getSession("s2")!!.chatId)
        }
    }

    @Nested
    inner class AddEvent {
        @Test
        fun `adds event to session`() {
            store.startSession("s1", "chat1")
            val event = createEvent("s1")

            store.addEvent("s1", event)

            val events = store.getSessionEvents("s1")
            assertEquals(1, events.size)
            assertEquals(event.id, events[0].id)
        }

        @Test
        fun `increments event count`() {
            store.startSession("s1", "chat1")

            store.addEvent("s1", createEvent("s1"))
            store.addEvent("s1", createEvent("s1"))
            store.addEvent("s1", createEvent("s1"))

            val session = store.getSession("s1")
            assertNotNull(session)
            assertEquals(3, session.eventCount)
        }

        @Test
        fun `ignores event for nonexistent session`() {
            store.addEvent("nonexistent", createEvent("nonexistent"))

            val events = store.getSessionEvents("nonexistent")
            assertTrue(events.isEmpty())
        }

        @Test
        fun `preserves event order`() {
            store.startSession("s1", "chat1")

            val e1 = createEvent("s1", eventType = "first")
            val e2 = createEvent("s1", eventType = "second")
            val e3 = createEvent("s1", eventType = "third")

            store.addEvent("s1", e1)
            store.addEvent("s1", e2)
            store.addEvent("s1", e3)

            val events = store.getSessionEvents("s1")
            assertEquals("first", events[0].eventType)
            assertEquals("second", events[1].eventType)
            assertEquals("third", events[2].eventType)
        }
    }

    @Nested
    inner class CompleteSession {
        @Test
        fun `sets end time and status`() {
            store.startSession("s1", "chat1")

            store.completeSession("s1")

            val session = store.getSession("s1")
            assertNotNull(session)
            assertNotNull(session.endTime)
            assertEquals("completed", session.status)
        }

        @Test
        fun `supports custom status`() {
            store.startSession("s1", "chat1")

            store.completeSession("s1", status = "error")

            val session = store.getSession("s1")
            assertNotNull(session)
            assertEquals("error", session.status)
        }

        @Test
        fun `handles nonexistent session gracefully`() {
            store.completeSession("nonexistent")
            // Should not throw
        }
    }

    @Nested
    inner class GetSessionEvents {
        @Test
        fun `returns empty list for session with no events`() {
            store.startSession("s1", "chat1")

            val events = store.getSessionEvents("s1")
            assertTrue(events.isEmpty())
        }

        @Test
        fun `returns empty list for nonexistent session`() {
            val events = store.getSessionEvents("nonexistent")
            assertTrue(events.isEmpty())
        }

        @Test
        fun `returns snapshot copy`() {
            store.startSession("s1", "chat1")
            store.addEvent("s1", createEvent("s1"))

            val events1 = store.getSessionEvents("s1")
            store.addEvent("s1", createEvent("s1"))
            val events2 = store.getSessionEvents("s1")

            assertEquals(1, events1.size)
            assertEquals(2, events2.size)
        }
    }

    @Nested
    inner class ListSessions {
        @Test
        fun `returns sessions in reverse order`() {
            store.startSession("s1", "chat1")
            store.startSession("s2", "chat2")
            store.startSession("s3", "chat3")

            val sessions = store.listSessions()

            assertEquals(3, sessions.size)
            assertEquals("s3", sessions[0].id)
            assertEquals("s2", sessions[1].id)
            assertEquals("s1", sessions[2].id)
        }

        @Test
        fun `respects limit parameter`() {
            store.startSession("s1", "chat1")
            store.startSession("s2", "chat2")
            store.startSession("s3", "chat3")

            val sessions = store.listSessions(limit = 2)

            assertEquals(2, sessions.size)
            assertEquals("s3", sessions[0].id)
            assertEquals("s2", sessions[1].id)
        }

        @Test
        fun `respects offset parameter`() {
            store.startSession("s1", "chat1")
            store.startSession("s2", "chat2")
            store.startSession("s3", "chat3")

            val sessions = store.listSessions(offset = 1)

            assertEquals(2, sessions.size)
            assertEquals("s2", sessions[0].id)
            assertEquals("s1", sessions[1].id)
        }

        @Test
        fun `returns empty list when no sessions`() {
            val sessions = store.listSessions()
            assertTrue(sessions.isEmpty())
        }

        @Test
        fun `limit and offset combined`() {
            store.startSession("s1", "chat1")
            store.startSession("s2", "chat2")
            store.startSession("s3", "chat3")

            val sessions = store.listSessions(limit = 1, offset = 1)

            assertEquals(1, sessions.size)
            assertEquals("s2", sessions[0].id)
        }
    }

    @Nested
    inner class GetSessionDetail {
        @Test
        fun `returns session with events`() {
            store.startSession("s1", "chat1")
            val event = createEvent("s1")
            store.addEvent("s1", event)

            val detail = store.getSessionDetail("s1")

            assertNotNull(detail)
            assertEquals("s1", detail.session.id)
            assertEquals(1, detail.events.size)
            assertEquals(event.id, detail.events[0].id)
        }

        @Test
        fun `returns null for nonexistent session`() {
            val detail = store.getSessionDetail("nonexistent")
            assertNull(detail)
        }
    }

    @Nested
    inner class Eviction {
        @Test
        fun `evicts oldest session when at capacity`() {
            store.startSession("s1", "chat1")
            store.startSession("s2", "chat2")
            store.startSession("s3", "chat3")
            store.startSession("s4", "chat4")
            store.startSession("s5", "chat5")

            // Adding a 6th session should evict s1
            store.startSession("s6", "chat6")

            assertNull(store.getSession("s1"))
            assertNotNull(store.getSession("s2"))
            assertNotNull(store.getSession("s6"))
        }

        @Test
        fun `evicts events along with session`() {
            store.startSession("s1", "chat1")
            store.addEvent("s1", createEvent("s1"))
            store.addEvent("s1", createEvent("s1"))

            // Fill to capacity + 1
            for (i in 2..6) {
                store.startSession("s$i", "chat$i")
            }

            assertTrue(store.getSessionEvents("s1").isEmpty())
        }

        @Test
        fun `maintains correct count after eviction`() {
            for (i in 1..10) {
                store.startSession("s$i", "chat$i")
            }

            val sessions = store.listSessions(limit = 100)
            assertEquals(5, sessions.size)
        }
    }
}
