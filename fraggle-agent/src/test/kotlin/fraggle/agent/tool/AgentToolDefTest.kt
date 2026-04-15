package fraggle.agent.tool

import fraggle.agent.loop.ToolCallResult
import fraggle.agent.loop.ToolDefinition
import fraggle.agent.message.ToolCall
import fraggle.executor.supervision.NoOpToolSupervisor
import fraggle.executor.supervision.PermissionResult
import fraggle.executor.supervision.ToolSupervisor
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentToolDefTest {

    /** Simple test tool. */
    class AddTool : AgentToolDef<AddTool.Args>(
        name = "add",
        description = "Add two numbers",
        argsSerializer = Args.serializer(),
    ) {
        @Serializable
        data class Args(val a: Int, val b: Int)

        override suspend fun execute(args: Args): String = (args.a + args.b).toString()
    }

    class EchoTool : AgentToolDef<EchoTool.Args>(
        name = "echo",
        description = "Echo input back",
        argsSerializer = Args.serializer(),
    ) {
        @Serializable
        data class Args(val message: String, val uppercase: Boolean = false)

        override suspend fun execute(args: Args): String =
            if (args.uppercase) args.message.uppercase() else args.message
    }

    @Nested
    inner class BasicExecution {
        @Test
        fun `tool executes with correct args`() = runTest {
            val tool = AddTool()
            val result = tool.execute(AddTool.Args(3, 7))
            assertEquals("10", result)
        }

        @Test
        fun `tool has correct metadata`() {
            val tool = AddTool()
            assertEquals("add", tool.name)
            assertEquals("Add two numbers", tool.description)
            assertNotNull(tool.argsSerializer)
        }
    }

    @Nested
    inner class RegistryTests {
        @Test
        fun `findTool returns correct tool`() {
            val registry = FraggleToolRegistry(listOf(AddTool(), EchoTool()))
            val tool = registry.findTool("add")
            assertNotNull(tool)
            assertEquals("add", tool.name)
        }

        @Test
        fun `findTool returns null for unknown tool`() {
            val registry = FraggleToolRegistry(listOf(AddTool()))
            assertNull(registry.findTool("unknown"))
        }

        @Test
        fun `toToolDefinitions generates definitions for all tools`() {
            val registry = FraggleToolRegistry(listOf(AddTool(), EchoTool()))
            val defs = registry.toToolDefinitions()
            assertEquals(2, defs.size)
            assertEquals("add", defs[0].name)
            assertEquals("echo", defs[1].name)
        }
    }

    @Nested
    inner class SchemaGeneration {
        @Test
        fun `generates object schema with properties`() {
            val schema = FraggleToolRegistry.descriptorToJsonSchema(AddTool.Args.serializer().descriptor)
            val schemaStr = schema.toString()
            assertTrue(schemaStr.contains("\"type\":\"object\""))
            assertTrue(schemaStr.contains("\"a\""))
            assertTrue(schemaStr.contains("\"b\""))
            assertTrue(schemaStr.contains("\"integer\""))
        }

        @Test
        fun `marks required properties`() {
            val schema = FraggleToolRegistry.descriptorToJsonSchema(AddTool.Args.serializer().descriptor)
            val schemaStr = schema.toString()
            assertTrue(schemaStr.contains("\"required\""))
            assertTrue(schemaStr.contains("\"a\""))
            assertTrue(schemaStr.contains("\"b\""))
        }

        @Test
        fun `optional properties not in required`() {
            val schema = FraggleToolRegistry.descriptorToJsonSchema(EchoTool.Args.serializer().descriptor)
            val schemaStr = schema.toString()
            // message is required, uppercase is optional (has default)
            assertTrue(schemaStr.contains("\"message\""))
            assertTrue(schemaStr.contains("\"uppercase\""))
            assertTrue(schemaStr.contains("\"boolean\""))
            assertTrue(schemaStr.contains("\"string\""))
        }

        @Test
        fun `handles string type`() {
            val schema = FraggleToolRegistry.descriptorToJsonSchema(EchoTool.Args.serializer().descriptor)
            val schemaStr = schema.toString()
            assertTrue(schemaStr.contains("\"string\""))
        }

        @Test
        fun `handles nested object`() {
            @Serializable
            data class Inner(val x: Int)
            @Serializable
            data class Outer(val inner: Inner, val name: String)

            class NestedTool : AgentToolDef<Outer>(
                name = "nested", description = "test",
                argsSerializer = Outer.serializer(),
            ) {
                override suspend fun execute(args: Outer) = args.toString()
            }

            val schema = FraggleToolRegistry.descriptorToJsonSchema(Outer.serializer().descriptor)
            val schemaStr = schema.toString()
            assertTrue(schemaStr.contains("\"inner\""))
            assertTrue(schemaStr.contains("\"x\""))
        }

        @Test
        fun `handles list type`() {
            @Serializable
            data class WithList(val items: List<String>)

            val schema = FraggleToolRegistry.descriptorToJsonSchema(WithList.serializer().descriptor)
            val schemaStr = schema.toString()
            assertTrue(schemaStr.contains("\"array\""))
            assertTrue(schemaStr.contains("\"items\""))
        }
    }

    @Nested
    inner class SupervisedExecutorTests {
        @Test
        fun `approved tool call executes normally`() = runTest {
            val registry = FraggleToolRegistry(listOf(AddTool()))
            val executor = SupervisedToolCallExecutor(registry, NoOpToolSupervisor())

            val result = executor.execute(
                ToolCall("tc-1", "add", """{"a":3,"b":7}"""),
                chatId = "chat",
            )

            assertEquals("10", result.content)
            assertEquals(false, result.isError)
        }

        @Test
        fun `denied tool call returns error`() = runTest {
            val supervisor = object : ToolSupervisor {
                override suspend fun checkPermission(toolName: String, argsJson: String, chatId: String) =
                    PermissionResult.Denied("not allowed")
            }
            val registry = FraggleToolRegistry(listOf(AddTool()))
            val executor = SupervisedToolCallExecutor(registry, supervisor)

            val result = executor.execute(
                ToolCall("tc-1", "add", """{"a":1,"b":2}"""),
                chatId = "chat",
            )

            assertTrue(result.isError)
            assertTrue(result.content.contains("denied"))
        }

        @Test
        fun `timed out permission returns error`() = runTest {
            val supervisor = object : ToolSupervisor {
                override suspend fun checkPermission(toolName: String, argsJson: String, chatId: String) =
                    PermissionResult.Timeout
            }
            val registry = FraggleToolRegistry(listOf(AddTool()))
            val executor = SupervisedToolCallExecutor(registry, supervisor)

            val result = executor.execute(
                ToolCall("tc-1", "add", """{"a":1,"b":2}"""),
                chatId = "chat",
            )

            assertTrue(result.isError)
            assertTrue(result.content.contains("timed out"))
        }

        @Test
        fun `unknown tool returns error`() = runTest {
            val registry = FraggleToolRegistry(emptyList())
            val executor = SupervisedToolCallExecutor(registry, NoOpToolSupervisor())

            val result = executor.execute(
                ToolCall("tc-1", "nonexistent", "{}"),
                chatId = "chat",
            )

            assertTrue(result.isError)
            assertTrue(result.content.contains("not found"))
        }

        @Test
        fun `invalid args returns error`() = runTest {
            val registry = FraggleToolRegistry(listOf(AddTool()))
            val executor = SupervisedToolCallExecutor(registry, NoOpToolSupervisor())

            val result = executor.execute(
                ToolCall("tc-1", "add", """{"invalid":"json"}"""),
                chatId = "chat",
            )

            assertTrue(result.isError)
        }

        @Test
        fun `getToolDefinitions returns definitions`() {
            val registry = FraggleToolRegistry(listOf(AddTool(), EchoTool()))
            val executor = SupervisedToolCallExecutor(registry, NoOpToolSupervisor())

            val defs = executor.getToolDefinitions()
            assertEquals(2, defs.size)
        }
    }
}
