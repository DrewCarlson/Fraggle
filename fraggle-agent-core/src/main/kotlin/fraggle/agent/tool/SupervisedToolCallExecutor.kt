package fraggle.agent.tool

import fraggle.agent.loop.ToolCallExecutor
import fraggle.agent.loop.ToolCallResult
import fraggle.agent.loop.ToolDefinition
import fraggle.agent.message.ToolCall
import fraggle.executor.RemoteToolClient
import fraggle.executor.supervision.PermissionResult
import fraggle.executor.supervision.ToolSupervisor
import kotlinx.serialization.json.Json

/**
 * Tool executor that integrates supervision (permission checks) and optional
 * remote forwarding into the loop. When [remoteClient] is non-null, approved
 * tool calls are forwarded to the remote worker instead of being executed locally.
 */
class SupervisedToolCallExecutor(
    private val registry: FraggleToolRegistry,
    private val supervisor: ToolSupervisor,
    private val remoteClient: RemoteToolClient? = null,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ToolCallExecutor {

    override suspend fun execute(toolCall: ToolCall, chatId: String): ToolCallResult {
        val tool = registry.findTool(toolCall.name)
            ?: return ToolCallResult("Error: Tool '${toolCall.name}' not found", isError = true)

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
            remoteClient?.let { client ->
                val remote = client.execute(toolCall.name, toolCall.arguments)
                return ToolCallResult(remote)
            }
            @Suppress("UNCHECKED_CAST")
            val args = json.decodeFromString(
                tool.argsSerializer as kotlinx.serialization.KSerializer<Any>,
                toolCall.arguments,
            )
            @Suppress("UNCHECKED_CAST")
            val result = (tool as AgentToolDef<Any>).execute(args)
            ToolCallResult(result)
        } catch (e: Exception) {
            ToolCallResult("Error: ${e.message}", isError = true)
        }
    }
}
