package fraggle.executor.supervision

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertIs

class InteractiveToolSupervisorTest {

    @Nested
    inner class AutoApprove {
        @Test
        fun `approves tool on auto-approve list`() = runTest {
            val supervisor = InteractiveToolSupervisor(
                autoApproveTools = listOf("read_file", "list_files"),
                handler = FakeHandler(approve = false),
            )

            val result = supervisor.checkPermission("read_file", "{}", "chat1")

            assertIs<PermissionResult.Approved>(result)
        }

        @Test
        fun `does not call handler for auto-approved tools`() = runTest {
            val handler = FakeHandler(approve = false)
            val supervisor = InteractiveToolSupervisor(
                autoApproveTools = listOf("read_file"),
                handler = handler,
            )

            supervisor.checkPermission("read_file", "{}", "chat1")

            assert(!handler.wasCalled)
        }

        @Test
        fun `delegates to handler when tool not in auto-approve list`() = runTest {
            val handler = FakeHandler(approve = true)
            val supervisor = InteractiveToolSupervisor(
                autoApproveTools = listOf("read_file"),
                handler = handler,
            )

            supervisor.checkPermission("write_file", "{}", "chat1")

            assert(handler.wasCalled)
        }

        @Test
        fun `empty auto-approve list always delegates`() = runTest {
            val handler = FakeHandler(approve = true)
            val supervisor = InteractiveToolSupervisor(
                autoApproveTools = emptyList(),
                handler = handler,
            )

            supervisor.checkPermission("read_file", "{}", "chat1")

            assert(handler.wasCalled)
        }
    }

    @Nested
    inner class HandlerDelegation {
        @Test
        fun `returns approved when handler approves`() = runTest {
            val supervisor = InteractiveToolSupervisor(
                autoApproveTools = emptyList(),
                handler = FakeHandler(approve = true),
            )

            val result = supervisor.checkPermission("write_file", "{}", "chat1")

            assertIs<PermissionResult.Approved>(result)
        }

        @Test
        fun `returns denied when handler denies`() = runTest {
            val supervisor = InteractiveToolSupervisor(
                autoApproveTools = emptyList(),
                handler = FakeHandler(approve = false),
            )

            val result = supervisor.checkPermission("write_file", "{}", "chat1")

            assertIs<PermissionResult.Denied>(result)
        }

        @Test
        fun `passes correct arguments to handler`() = runTest {
            val handler = FakeHandler(approve = true)
            val supervisor = InteractiveToolSupervisor(
                autoApproveTools = emptyList(),
                handler = handler,
            )

            supervisor.checkPermission("my_tool", """{"key":"value"}""", "chat42")

            assert(handler.lastToolName == "my_tool")
            assert(handler.lastArgsJson == """{"key":"value"}""")
            assert(handler.lastChatId == "chat42")
        }
    }

    @Nested
    inner class ExceptionHandling {
        @Test
        fun `returns timeout when handler throws`() = runTest {
            val supervisor = InteractiveToolSupervisor(
                autoApproveTools = emptyList(),
                handler = ThrowingHandler(),
            )

            val result = supervisor.checkPermission("tool", "{}", "chat1")

            assertIs<PermissionResult.Timeout>(result)
        }
    }

    private class FakeHandler(private val approve: Boolean) : ToolPermissionHandler {
        var wasCalled = false
        var lastToolName: String? = null
        var lastArgsJson: String? = null
        var lastChatId: String? = null

        override suspend fun requestPermission(
            requestId: String,
            toolName: String,
            argsJson: String,
            chatId: String,
        ): Boolean {
            wasCalled = true
            lastToolName = toolName
            lastArgsJson = argsJson
            lastChatId = chatId
            return approve
        }
    }

    private class ThrowingHandler : ToolPermissionHandler {
        override suspend fun requestPermission(
            requestId: String,
            toolName: String,
            argsJson: String,
            chatId: String,
        ): Boolean {
            throw RuntimeException("Simulated failure")
        }
    }
}
