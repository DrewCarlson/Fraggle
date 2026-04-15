package fraggle.agent.tool

import fraggle.agent.loop.ToolCallExecutor
import fraggle.agent.loop.ToolCallResult
import fraggle.agent.loop.ToolDefinition
import fraggle.agent.message.ToolCall
import fraggle.executor.supervision.PermissionResult
import fraggle.executor.supervision.ToolSupervisor
import kotlinx.serialization.json.Json

/**
 * Tool executor that integrates supervision (permission checks) into the loop.
 * Supervision moves from ManagedTool into this executor, matching the plan's
 * design where permission checking is a loop concern.
 */
class SupervisedToolCallExecutor(
    private val registry: FraggleToolRegistry,
    private val supervisor: ToolSupervisor,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ToolCallExecutor {

    override suspend fun execute(toolCall: ToolCall, chatId: String): ToolCallResult {
        val tool = registry.findTool(toolCall.name)
            ?: return ToolCallResult("Error: Tool '${toolCall.name}' not found", isError = true)

        // Check permission via supervisor
        val permission = supervisor.checkPermission(toolCall.name, toolCall.arguments, chatId)
        return when (permission) {
            is PermissionResult.Approved -> executeTool(tool, toolCall)
            is PermissionResult.Denied -> ToolCallResult("Error: Tool denied: ${permission.reason}", isError = true)
            is PermissionResult.Timeout -> ToolCallResult("Error: Permission timed out", isError = true)
        }
    }

    override fun getToolDefinitions(): List<ToolDefinition> = registry.toToolDefinitions()

    private suspend fun executeTool(tool: AgentToolDef<*>, toolCall: ToolCall): ToolCallResult {
        return try {
            @Suppress("UNCHECKED_CAST")
            val args = json.decodeFromString(tool.argsSerializer as kotlinx.serialization.KSerializer<Any>, toolCall.arguments)
            @Suppress("UNCHECKED_CAST")
            val result = (tool as AgentToolDef<Any>).execute(args)
            ToolCallResult(result)
        } catch (e: Exception) {
            ToolCallResult("Error: ${e.message}", isError = true)
        }
    }
}
