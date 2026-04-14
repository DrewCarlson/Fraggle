package fraggle.agent.loop

import fraggle.agent.message.ToolCall

/**
 * Interface for executing tool calls.
 * Implementations handle tool lookup, argument validation, supervision checks,
 * and actual execution.
 */
interface ToolCallExecutor {
    /**
     * Execute a tool call and return the result.
     *
     * @param toolCall The tool call to execute
     * @param chatId Chat ID for supervision context
     * @return The result text and whether it's an error
     */
    suspend fun execute(toolCall: ToolCall, chatId: String): ToolCallResult

    /**
     * Get tool definitions for the LLM.
     */
    fun getToolDefinitions(): List<ToolDefinition>
}

/**
 * Result of executing a tool call.
 */
data class ToolCallResult(
    val content: String,
    val isError: Boolean = false,
)
