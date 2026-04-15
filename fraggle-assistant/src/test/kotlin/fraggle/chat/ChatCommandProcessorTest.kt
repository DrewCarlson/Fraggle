package fraggle.chat

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import fraggle.events.EventBus
import fraggle.models.FraggleEvent
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChatCommandProcessorTest {

    private val eventBus = EventBus()
    private val processor = ChatCommandProcessor(eventBus)

    @Nested
    inner class IsCommand {
        @Test
        fun `slash prefix is detected as command`() {
            assertTrue(processor.isCommand("/approve"))
            assertTrue(processor.isCommand("/deny"))
            assertTrue(processor.isCommand("/unknown"))
        }

        @Test
        fun `regular messages are not commands`() {
            assertFalse(processor.isCommand("hello"))
            assertFalse(processor.isCommand("approve"))
            assertFalse(processor.isCommand(""))
        }
    }

    @Nested
    inner class HandleCommand {
        @Test
        fun `approve resolves pending permission`() = runTest {
            processor.trackPermissionRequest("chat1", "req-1")

            val eventJob = launch {
                val event = eventBus.events.first { it is FraggleEvent.ToolPermissionGranted }
                val granted = event as FraggleEvent.ToolPermissionGranted
                assertEquals("req-1", granted.requestId)
                assertTrue(granted.approved)
            }

            val result = processor.handleCommand("chat1", "/approve")
            assertIs<CommandResult.Approved>(result)
            eventJob.join()
        }

        @Test
        fun `deny resolves pending permission`() = runTest {
            processor.trackPermissionRequest("chat1", "req-2")

            val eventJob = launch {
                val event = eventBus.events.first { it is FraggleEvent.ToolPermissionGranted }
                val granted = event as FraggleEvent.ToolPermissionGranted
                assertEquals("req-2", granted.requestId)
                assertFalse(granted.approved)
            }

            val result = processor.handleCommand("chat1", "/deny")
            assertIs<CommandResult.Denied>(result)
            eventJob.join()
        }

        @Test
        fun `approve with no pending request returns NoPermissionPending`() = runTest {
            val result = processor.handleCommand("chat1", "/approve")
            assertIs<CommandResult.NoPermissionPending>(result)
        }

        @Test
        fun `deny with no pending request returns NoPermissionPending`() = runTest {
            val result = processor.handleCommand("chat1", "/deny")
            assertIs<CommandResult.NoPermissionPending>(result)
        }

        @Test
        fun `unknown command returns Unknown with command name`() = runTest {
            val result = processor.handleCommand("chat1", "/foobar arg1")
            assertIs<CommandResult.Unknown>(result)
            assertEquals("/foobar", result.command)
        }

        @Test
        fun `approve is case-insensitive`() = runTest {
            processor.trackPermissionRequest("chat1", "req-3")
            val result = processor.handleCommand("chat1", "/Approve")
            assertIs<CommandResult.Approved>(result)
        }

        @Test
        fun `latest request overwrites previous for same chat`() = runTest {
            processor.trackPermissionRequest("chat1", "req-old")
            processor.trackPermissionRequest("chat1", "req-new")

            val eventJob = launch {
                val event = eventBus.events.first { it is FraggleEvent.ToolPermissionGranted }
                val granted = event as FraggleEvent.ToolPermissionGranted
                assertEquals("req-new", granted.requestId)
            }

            val result = processor.handleCommand("chat1", "/approve")
            assertIs<CommandResult.Approved>(result)
            eventJob.join()
        }

        @Test
        fun `different chats have independent pending requests`() = runTest {
            processor.trackPermissionRequest("chat1", "req-a")
            processor.trackPermissionRequest("chat2", "req-b")

            val eventJob = launch {
                val event = eventBus.events.first { it is FraggleEvent.ToolPermissionGranted }
                val granted = event as FraggleEvent.ToolPermissionGranted
                assertEquals("req-a", granted.requestId)
            }

            val result = processor.handleCommand("chat1", "/approve")
            assertIs<CommandResult.Approved>(result)
            eventJob.join()
        }
    }

    @Nested
    inner class ClearPermissionRequest {
        @Test
        fun `clearing a request makes approve return NoPermissionPending`() = runTest {
            processor.trackPermissionRequest("chat1", "req-1")
            processor.clearPermissionRequest("req-1")

            val result = processor.handleCommand("chat1", "/approve")
            assertIs<CommandResult.NoPermissionPending>(result)
        }

        @Test
        fun `clearing a non-existent request is a no-op`() {
            processor.clearPermissionRequest("does-not-exist")
        }
    }
}
