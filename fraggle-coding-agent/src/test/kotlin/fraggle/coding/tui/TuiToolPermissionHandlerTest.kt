package fraggle.coding.tui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TuiToolPermissionHandlerTest {

    @Nested
    inner class ApproveAndDeny {
        @Test
        fun `approve resolves the pending request as true`() = runTest {
            val handler = TuiToolPermissionHandler()
            val deferred = async {
                handler.requestPermission(
                    requestId = "req-1",
                    toolName = "read_file",
                    argsJson = """{"path":"foo.kt"}""",
                    chatId = "session-abc",
                )
            }

            // Wait for the handler to publish the pending approval.
            advanceUntilIdle()
            val pending = handler.pending.value
            assertNotNull(pending)
            assertEquals("read_file", pending.toolName)
            assertEquals("""{"path":"foo.kt"}""", pending.argsJson)
            assertEquals("req-1", pending.requestId)

            handler.approve()
            assertTrue(deferred.await(), "approve should resolve as true")

            // After resolving, pending should be cleared.
            assertNull(handler.pending.value)
        }

        @Test
        fun `deny resolves the pending request as false`() = runTest {
            val handler = TuiToolPermissionHandler()
            val deferred = async {
                handler.requestPermission("req", "write_file", "{}", "chat")
            }
            advanceUntilIdle()
            assertNotNull(handler.pending.value)

            handler.deny()
            assertFalse(deferred.await(), "deny should resolve as false")
            assertNull(handler.pending.value)
        }
    }

    @Nested
    inner class Serialization {
        @Test
        fun `second request waits for the first to resolve`() = runTest {
            val handler = TuiToolPermissionHandler()

            // Start the first request and wait for it to publish.
            val first = async { handler.requestPermission("req-1", "tool1", "{}", "c") }
            advanceUntilIdle()
            assertEquals("tool1", handler.pending.value?.toolName)

            // Start a second request — it should be parked on the mutex
            // behind the first one. The pending state should NOT flip to
            // tool2 yet.
            val second = async { handler.requestPermission("req-2", "tool2", "{}", "c") }
            advanceUntilIdle()
            assertEquals(
                "tool1",
                handler.pending.value?.toolName,
                "first request is still pending while the mutex is held",
            )

            // Resolve the first request; the second should now become pending.
            handler.approve()
            assertTrue(first.await())
            advanceUntilIdle()
            assertEquals("tool2", handler.pending.value?.toolName)

            // Resolve the second one too.
            handler.deny()
            assertFalse(second.await())
            assertNull(handler.pending.value)
        }
    }

    @Nested
    inner class Idempotency {
        @Test
        fun `approve with nothing pending is a no-op`() = runTest {
            val handler = TuiToolPermissionHandler()
            handler.approve()  // shouldn't throw
            handler.deny()     // shouldn't throw
            assertNull(handler.pending.value)
        }
    }
}
