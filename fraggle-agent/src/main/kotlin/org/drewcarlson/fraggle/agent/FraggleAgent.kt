package org.drewcarlson.fraggle.agent

import ai.koog.agents.core.agent.AIAgentService
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.drewcarlson.fraggle.chat.ChatPlatform
import org.drewcarlson.fraggle.chat.IncomingMessage
import org.drewcarlson.fraggle.chat.MessageContent
import org.drewcarlson.fraggle.memory.Fact
import org.drewcarlson.fraggle.memory.MemoryScope
import org.drewcarlson.fraggle.memory.MemoryStore
import org.drewcarlson.fraggle.prompt.PromptManager
import org.drewcarlson.fraggle.provider.Usage
import org.drewcarlson.fraggle.sandbox.Sandbox
import org.slf4j.LoggerFactory
import java.io.Closeable
import kotlin.time.Clock
import kotlin.time.Instant
import ai.koog.prompt.llm.LLMProvider as KoogLLMProvider

/**
 * Main agent orchestration class.
 * Uses Koog AIAgent framework for LLM interaction with native tool support.
 */
class FraggleAgent(
    promptExecutor: PromptExecutor,
    private val toolRegistry: ToolRegistry,
    private val memory: MemoryStore,
    private val sandbox: Sandbox,
    private val config: AgentConfig,
    promptManager: PromptManager,
) : Closeable {
    private val logger = LoggerFactory.getLogger(FraggleAgent::class.java)

    private val agentService = AIAgentService(
        promptExecutor = promptExecutor,
        llmModel = LLModel(
            provider = KoogLLMProvider.OpenAI,
            id = config.model,
            capabilities = listOf(
                LLMCapability.Temperature,
                LLMCapability.Tools,
                LLMCapability.Completion,
                LLMCapability.OpenAIEndpoint.Completions,
            ),
            contextLength = config.maxTokens,
        ),
        strategy = singleRunStrategy(),
        toolRegistry = toolRegistry,
        systemPrompt = buildBaseSystemPrompt(promptManager),
        temperature = config.temperature,
        maxIterations = config.maxIterations,
    )

    /**
     * Process an incoming message and generate a response.
     */
    suspend fun process(
        conversation: Conversation,
        message: IncomingMessage,
        platform: ChatPlatform? = null,
    ): AgentResponse {
        logger.info("Processing message from ${message.sender.id} in chat ${message.chatId}")

        val userInput = buildKoogInput(conversation, message, platform)

        // Create per-request execution context for tools
        val executionContext = ToolExecutionContext(
            chatId = message.chatId,
            userId = message.sender.id,
        )

        return try {
            val result = withContext(ToolExecutionContext.asContextElement(executionContext)) {
                agentService.createAgentAndRun(userInput)
            }

            if (config.autoMemory && result.isNotBlank()) {
                extractAndSaveMemory(message, result)
            }

            AgentResponse.Success(
                content = result,
                attachments = executionContext.attachments.toList(),
            )
        } catch (e: Exception) {
            logger.error("Koog agent error: ${e.message}", e)
            AgentResponse.Error("Failed to get response from LLM: ${e.message}")
        }
    }

    override fun close() {
        runBlocking { agentService.closeAll() }
    }

    /**
     * Build the static base system prompt (identity, instructions).
     * Called once during agent construction. Tool descriptions are handled natively by Koog.
     */
    private fun buildBaseSystemPrompt(promptManager: PromptManager): String = buildString {
        appendLine(promptManager.buildFullPrompt())
        appendLine()

        appendLine("# IMPORTANT: Conversation History")
        appendLine()
        appendLine("The conversation history above shows PREVIOUS exchanges that are ALREADY COMPLETED.")
        appendLine("Each user message is a separate request - focus only on what the user is asking NOW.")
    }

    /**
     * Build per-call input: platform context, memory, conversation history, current message.
     */
    private suspend fun buildKoogInput(
        conversation: Conversation,
        message: IncomingMessage,
        platform: ChatPlatform?,
    ): String = buildString {
        // Platform context
        if (platform != null) {
            appendLine("# Communication Channel")
            appendLine()
            appendLine("You are communicating via ${platform.name}.")

            platform.notes?.let {
                appendLine()
                appendLine(it)
            }

            if (platform.supportsAttachments) {
                appendLine()
                appendLine("IMAGE HANDLING:")
                appendLine("- To include an image in your response, use this syntax: [[image:URL]]")
                appendLine("- Example: [[image:https://example.com/photo.jpg]]")
                appendLine("- The image will be downloaded and sent as an attachment WITH your text in one cohesive message")
                appendLine("- Only ONE image can be sent per message on this platform")
                appendLine("- For screenshots, use the screenshot_page tool (it requires browser automation)")
                appendLine("- Do NOT use markdown image syntax like ![alt](url) - it won't display")
            }

            if (!platform.supportsInlineImages) {
                appendLine()
                appendLine("IMPORTANT: Raw image URLs or markdown image syntax will NOT display as images.")
                appendLine("Always use [[image:URL]] syntax to share images.")
            }
            appendLine()
        }

        // Memory context
        val globalStr = memory.load(MemoryScope.Global).toPromptString()
        val chatStr = memory.load(MemoryScope.Chat(message.chatId)).toPromptString()
        val userStr = memory.load(MemoryScope.User(message.sender.id)).toPromptString()

        if (globalStr.isNotBlank() || chatStr.isNotBlank() || userStr.isNotBlank()) {
            appendLine("# Relevant Memory")
            appendLine()
            if (globalStr.isNotBlank()) appendLine(globalStr)
            if (chatStr.isNotBlank()) appendLine(chatStr)
            if (userStr.isNotBlank()) appendLine(userStr)
        }

        // Conversation history
        val history = conversation.messages.takeLast(config.maxHistoryMessages)
        if (history.isNotEmpty()) {
            appendLine("[Previous conversation history]")
            for (msg in history) {
                val prefix = when (msg.role) {
                    ConversationRole.USER -> "User"
                    ConversationRole.ASSISTANT -> "Assistant"
                }
                appendLine("$prefix: ${msg.content}")
            }
            appendLine("[End of history - respond to the following message]")
            appendLine()
        }

        // Current message
        val userContent = when (val content = message.content) {
            is MessageContent.Text -> content.text
            is MessageContent.Image -> "[Image${content.caption?.let { ": $it" } ?: ""}]"
            is MessageContent.File -> "[File: ${content.filename}]"
            is MessageContent.Audio -> "[Audio message]"
            is MessageContent.Sticker -> "[Sticker: ${content.emoji ?: content.id}]"
            is MessageContent.Reaction -> "[Reaction: ${content.emoji}]"
        }
        append(userContent)
    }

    private suspend fun extractAndSaveMemory(message: IncomingMessage, response: String) {
        val userText = (message.content as? MessageContent.Text)?.text ?: return

        val patterns = listOf(
            "my name is" to "User's name:",
            "i work at" to "User works at:",
            "i live in" to "User lives in:",
            "i'm from" to "User is from:",
            "my favorite" to "User's favorite:",
        )

        for ((pattern, prefix) in patterns) {
            if (userText.lowercase().contains(pattern)) {
                val fact = Fact(
                    content = "$prefix ${userText.take(200)}",
                    source = "conversation",
                )
                memory.append(MemoryScope.User(message.sender.id), fact)
                logger.debug("Saved memory fact for user ${message.sender.id}")
                break
            }
        }
    }
}

/**
 * Agent configuration.
 */
data class AgentConfig(
    val model: String = "",
    val temperature: Double = 0.7,
    val maxTokens: Long = 4096,
    val maxIterations: Int = 10,
    val maxHistoryMessages: Int = 20,
    val autoMemory: Boolean = true,
)

/**
 * Conversation context.
 */
data class Conversation(
    val id: String,
    val chatId: String,
    val messages: List<ConversationMessage> = emptyList(),
)

data class ConversationMessage(
    val role: ConversationRole,
    val content: String,
    val timestamp: Instant = Clock.System.now(),
)

enum class ConversationRole {
    USER,
    ASSISTANT,
}

/**
 * Attachment to be sent with the response.
 */
sealed class ResponseAttachment {
    data class Image(
        val data: ByteArray,
        val mimeType: String,
        val caption: String? = null,
    ) : ResponseAttachment() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Image) return false
            return data.contentEquals(other.data) && mimeType == other.mimeType && caption == other.caption
        }
        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + mimeType.hashCode()
            result = 31 * result + (caption?.hashCode() ?: 0)
            return result
        }
    }

    data class File(
        val data: ByteArray,
        val filename: String,
        val mimeType: String? = null,
    ) : ResponseAttachment() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is File) return false
            return data.contentEquals(other.data) && filename == other.filename && mimeType == other.mimeType
        }
        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + filename.hashCode()
            result = 31 * result + (mimeType?.hashCode() ?: 0)
            return result
        }
    }
}

/**
 * Agent response.
 */
sealed class AgentResponse {
    data class Success(
        val content: String,
        val usage: Usage? = null,
        val iterations: Int = 1,
        val attachments: List<ResponseAttachment> = emptyList(),
    ) : AgentResponse()

    data class Error(
        val message: String,
    ) : AgentResponse()

    fun contentOrError(): String = when (this) {
        is Success -> content
        is Error -> "Error: $message"
    }

    fun collectAttachments(): List<ResponseAttachment> = when (this) {
        is Success -> attachments
        is Error -> emptyList()
    }
}
