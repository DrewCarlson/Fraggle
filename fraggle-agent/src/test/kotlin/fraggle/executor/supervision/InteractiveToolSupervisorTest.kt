package fraggle.executor.supervision

import fraggle.models.ApprovalPolicy
import fraggle.models.ArgMatcher
import fraggle.models.ToolPolicy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class InteractiveToolSupervisorTest {

    @Nested
    inner class ToolPolicies {
        @Test
        fun `approves tool on policy list`() = runTest {
            val supervisor = InteractiveToolSupervisor(
                evaluator = ToolPolicyEvaluator(listOf(
                    ToolPolicy("read_file"),
                    ToolPolicy("list_files"),
                )),
                handler = FakeHandler(approve = false),
            )

            val result = supervisor.checkPermission("read_file", "{}", "chat1")

            assertIs<PermissionResult.Approved>(result)
        }

        @Test
        fun `does not call handler for policy-approved tools`() = runTest {
            val handler = FakeHandler(approve = false)
            val supervisor = InteractiveToolSupervisor(
                evaluator = ToolPolicyEvaluator(listOf(ToolPolicy("read_file"))),
                handler = handler,
            )

            supervisor.checkPermission("read_file", "{}", "chat1")

            assert(!handler.wasCalled)
        }

        @Test
        fun `delegates to handler when tool not in policy list`() = runTest {
            val handler = FakeHandler(approve = true)
            val supervisor = InteractiveToolSupervisor(
                evaluator = ToolPolicyEvaluator(listOf(ToolPolicy("read_file"))),
                handler = handler,
            )

            supervisor.checkPermission("write_file", "{}", "chat1")

            assert(handler.wasCalled)
        }

        @Test
        fun `empty policy list always delegates`() = runTest {
            val handler = FakeHandler(approve = true)
            val supervisor = InteractiveToolSupervisor(
                evaluator = ToolPolicyEvaluator(emptyList()),
                handler = handler,
            )

            supervisor.checkPermission("read_file", "{}", "chat1")

            assert(handler.wasCalled)
        }

        @Test
        fun `approves tool with matching arg pattern`() = runTest {
            val supervisor = InteractiveToolSupervisor(
                evaluator = ToolPolicyEvaluator(listOf(
                    ToolPolicy("write_file", args = listOf(ArgMatcher("path", listOf("/workspace/**")))),
                )),
                handler = FakeHandler(approve = false),
            )

            val result = supervisor.checkPermission(
                "write_file",
                """{"path":"/workspace/docs/readme.md"}""",
                "chat1",
            )

            assertIs<PermissionResult.Approved>(result)
        }

        @Test
        fun `delegates to handler when arg pattern does not match`() = runTest {
            val handler = FakeHandler(approve = true)
            val supervisor = InteractiveToolSupervisor(
                evaluator = ToolPolicyEvaluator(listOf(
                    ToolPolicy("write_file", args = listOf(ArgMatcher("path", listOf("/workspace/**")))),
                )),
                handler = handler,
            )

            supervisor.checkPermission(
                "write_file",
                """{"path":"/etc/passwd"}""",
                "chat1",
            )

            assert(handler.wasCalled)
        }
    }

    @Nested
    inner class PolicyHandling {
        @Test
        fun `deny rule returns Denied without calling handler`() = runTest {
            val handler = FakeHandler(approve = true)
            val supervisor = InteractiveToolSupervisor(
                evaluator = ToolPolicyEvaluator(listOf(
                    ToolPolicy("write_file", policy = ApprovalPolicy.DENY, args = listOf(
                        ArgMatcher("path", listOf("/etc/**")),
                    )),
                )),
                handler = handler,
            )

            val result = supervisor.checkPermission(
                "write_file",
                """{"path":"/etc/hosts"}""",
                "chat1",
            )

            assertIs<PermissionResult.Denied>(result)
            assertEquals("Denied by policy", result.reason)
            assert(!handler.wasCalled)
        }

        @Test
        fun `ask rule delegates to handler`() = runTest {
            val handler = FakeHandler(approve = true)
            val supervisor = InteractiveToolSupervisor(
                evaluator = ToolPolicyEvaluator(listOf(
                    ToolPolicy("execute_command", policy = ApprovalPolicy.ASK),
                )),
                handler = handler,
            )

            val result = supervisor.checkPermission("execute_command", "{}", "chat1")

            assertIs<PermissionResult.Approved>(result)
            assert(handler.wasCalled)
        }

        @Test
        fun `ask rule with handler denial returns Denied`() = runTest {
            val handler = FakeHandler(approve = false)
            val supervisor = InteractiveToolSupervisor(
                evaluator = ToolPolicyEvaluator(listOf(
                    ToolPolicy("execute_command", policy = ApprovalPolicy.ASK),
                )),
                handler = handler,
            )

            val result = supervisor.checkPermission("execute_command", "{}", "chat1")

            assertIs<PermissionResult.Denied>(result)
            assert(handler.wasCalled)
        }
    }

    @Nested
    inner class HandlerDelegation {
        @Test
        fun `returns approved when handler approves`() = runTest {
            val supervisor = InteractiveToolSupervisor(
                evaluator = ToolPolicyEvaluator(emptyList()),
                handler = FakeHandler(approve = true),
            )

            val result = supervisor.checkPermission("write_file", "{}", "chat1")

            assertIs<PermissionResult.Approved>(result)
        }

        @Test
        fun `returns denied when handler denies`() = runTest {
            val supervisor = InteractiveToolSupervisor(
                evaluator = ToolPolicyEvaluator(emptyList()),
                handler = FakeHandler(approve = false),
            )

            val result = supervisor.checkPermission("write_file", "{}", "chat1")

            assertIs<PermissionResult.Denied>(result)
        }

        @Test
        fun `passes correct arguments to handler`() = runTest {
            val handler = FakeHandler(approve = true)
            val supervisor = InteractiveToolSupervisor(
                evaluator = ToolPolicyEvaluator(emptyList()),
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
                evaluator = ToolPolicyEvaluator(emptyList()),
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
