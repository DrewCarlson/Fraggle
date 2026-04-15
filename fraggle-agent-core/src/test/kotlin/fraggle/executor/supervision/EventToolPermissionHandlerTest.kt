package fraggle.executor.supervision

import fraggle.events.EventBus
import fraggle.models.FraggleEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EventToolPermissionHandlerTest {

    private val eventBus = EventBus()
    private val handler = EventToolPermissionHandler(eventBus)

    @Nested
    inner class RequestPermission {
        @Test
        fun `emits permission request event`() = runTest {
            var receivedEvent: FraggleEvent? = null
            val job = launch {
                receivedEvent = eventBus.events.first { it is FraggleEvent.ToolPermissionRequest }
            }

            launch {
                // Give the collector time to subscribe
                kotlinx.coroutines.delay(10)
                // Immediately resolve so requestPermission returns
                launch {
                    kotlinx.coroutines.delay(20)
                    handler.resolvePermission("req1", true)
                }
                handler.requestPermission("req1", "my_tool", """{"key":"val"}""", "chat1")
            }

            job.join()

            val event = receivedEvent
            assertIs<FraggleEvent.ToolPermissionRequest>(event)
            assertEquals("req1", event.requestId)
            assertEquals("my_tool", event.toolName)
            assertEquals("""{"key":"val"}""", event.argsJson)
            assertEquals("chat1", event.chatId)
        }

        @Test
        fun `returns true when approved`() = runTest {
            launch {
                // Wait for the request event then approve
                eventBus.events.first { it is FraggleEvent.ToolPermissionRequest }
                handler.resolvePermission("req1", true)
            }

            val result = handler.requestPermission("req1", "tool", "{}", "chat1")

            assertTrue(result)
        }

        @Test
        fun `returns false when denied`() = runTest {
            launch {
                eventBus.events.first { it is FraggleEvent.ToolPermissionRequest }
                handler.resolvePermission("req1", false)
            }

            val result = handler.requestPermission("req1", "tool", "{}", "chat1")

            assertFalse(result)
        }
    }

    @Nested
    inner class ResolvePermission {
        @Test
        fun `emits granted event`() = runTest {
            var receivedEvent: FraggleEvent? = null
            val job = launch {
                receivedEvent = eventBus.events.first { it is FraggleEvent.ToolPermissionGranted }
            }

            handler.resolvePermission("req1", true)
            job.join()

            val event = receivedEvent
            assertIs<FraggleEvent.ToolPermissionGranted>(event)
            assertEquals("req1", event.requestId)
            assertTrue(event.approved)
        }

        @Test
        fun `emits denied event`() = runTest {
            var receivedEvent: FraggleEvent? = null
            val job = launch {
                receivedEvent = eventBus.events.first { it is FraggleEvent.ToolPermissionGranted }
            }

            handler.resolvePermission("req1", false)
            job.join()

            val event = receivedEvent
            assertIs<FraggleEvent.ToolPermissionGranted>(event)
            assertEquals("req1", event.requestId)
            assertFalse(event.approved)
        }
    }

    @Nested
    inner class RequestIdMatching {
        @Test
        fun `only resolves matching request id`() = runTest {
            launch {
                eventBus.events.first { it is FraggleEvent.ToolPermissionRequest }
                // Resolve a different request ID first - should be ignored
                handler.resolvePermission("wrong-id", true)
                // Then resolve the correct one
                handler.resolvePermission("req1", false)
            }

            val result = handler.requestPermission("req1", "tool", "{}", "chat1")

            assertFalse(result)
        }
    }
}
