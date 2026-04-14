package fraggle.agent.loop

import fraggle.agent.event.StreamDelta
import fraggle.agent.message.AgentMessage

/**
 * Bridge between the agent loop and the LLM.
 * Abstracts away the specifics of how LLM calls are made (Koog PromptExecutor,
 * direct HTTP, etc.).
 */
fun interface LlmBridge {
    /**
     * Send the conversation to the LLM and return the assistant response.
     *
     * @param systemPrompt The system prompt to use
     * @param messages The conversation history (all messages so far)
     * @param tools Tool definitions available for this call (JSON schemas)
     * @return The assistant's response message
     */
    suspend fun call(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<ToolDefinition>,
    ): AgentMessage.Assistant
}

/**
 * Extended bridge that supports streaming responses.
 * When the loop detects a [StreamingLlmBridge], it emits [AgentEvent.MessageUpdate]
 * deltas as chunks arrive.
 */
interface StreamingLlmBridge : LlmBridge {
    /**
     * Stream a response from the LLM, invoking [onDelta] for each chunk.
     * Returns the final assembled [AgentMessage.Assistant].
     */
    suspend fun callStreaming(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<ToolDefinition>,
        onDelta: suspend (StreamDelta, AgentMessage.Assistant) -> Unit,
    ): AgentMessage.Assistant
}

/**
 * Tool definition for the LLM (name + JSON schema).
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parametersSchema: String,
)
