package fraggle.agent.loop

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import fraggle.agent.ReasoningContentFilter
import fraggle.agent.message.AgentMessage
import fraggle.agent.message.ContentPart
import fraggle.agent.message.StopReason

/**
 * LLM bridge backed by Koog's [PromptExecutor].
 * Converts AgentMessages to Koog prompt format, executes, and converts back.
 *
 * Phase 1: non-streaming (full response after completion).
 */
class KoogLlmBridge(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
    private val chatParams: OpenAIChatParams = OpenAIChatParams(),
) : LlmBridge {

    override suspend fun call(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<ToolDefinition>,
    ): AgentMessage.Assistant {
        val koogPrompt = prompt("agent-loop", chatParams) {
            system(systemPrompt)

            for (msg in messages) {
                when (msg) {
                    is AgentMessage.User -> {
                        val text = msg.content.filterIsInstance<ContentPart.Text>()
                            .joinToString("") { it.text }
                        if (text.isNotBlank()) {
                            user(text)
                        }
                    }
                    is AgentMessage.Assistant -> {
                        val text = msg.textContent
                        if (text.isNotBlank()) {
                            assistant(text)
                        }
                    }
                    is AgentMessage.ToolResult -> {
                        // Tool results in Koog prompt format are user messages
                        user("[Tool result for ${msg.toolName}]: ${msg.textContent}")
                    }
                    is AgentMessage.Platform -> {
                        // Skip platform messages
                    }
                }
            }
        }

        return try {
            val responses = promptExecutor.execute(prompt = koogPrompt, model = model)
            val assistantContent = responses
                .filterIsInstance<Message.Assistant>()
                .firstOrNull()
                ?.content
                ?.let { ReasoningContentFilter.strip(it) }
                ?: ""

            AgentMessage.Assistant(
                content = if (assistantContent.isNotBlank()) {
                    listOf(ContentPart.Text(assistantContent))
                } else {
                    emptyList()
                },
                stopReason = StopReason.STOP,
            )
        } catch (e: Exception) {
            AgentMessage.Assistant(
                content = listOf(ContentPart.Text("LLM error: ${e.message}")),
                stopReason = StopReason.ERROR,
                errorMessage = e.message,
            )
        }
    }
}
