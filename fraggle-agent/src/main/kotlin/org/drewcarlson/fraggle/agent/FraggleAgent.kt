package org.drewcarlson.fraggle.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider as KoogLLMProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.drewcarlson.fraggle.chat.ChatPlatform
import org.drewcarlson.fraggle.chat.IncomingMessage
import org.drewcarlson.fraggle.chat.MessageContent
import org.drewcarlson.fraggle.memory.Fact
import org.drewcarlson.fraggle.memory.Memory
import org.drewcarlson.fraggle.memory.MemoryScope
import org.drewcarlson.fraggle.memory.MemoryStore
import org.drewcarlson.fraggle.prompt.PromptManager
import org.drewcarlson.fraggle.provider.*
import org.drewcarlson.fraggle.sandbox.Sandbox
import org.drewcarlson.fraggle.skill.SkillContext
import org.drewcarlson.fraggle.skill.SkillParameters
import org.drewcarlson.fraggle.skill.SkillRegistry
import org.drewcarlson.fraggle.skill.SkillResult
import org.slf4j.LoggerFactory

/**
 * Main agent orchestration class.
 * Uses Koog AIAgent framework for LLM interaction.
 */
class FraggleAgent(
    private val promptExecutor: PromptExecutor,
    private val skills: SkillRegistry,
    private val memory: MemoryStore,
    private val sandbox: Sandbox,
    private val config: AgentConfig,
    private val promptManager: PromptManager,
) {
    private val logger = LoggerFactory.getLogger(FraggleAgent::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Process an incoming message and generate a response.
     *
     * @param conversation The conversation context
     * @param message The incoming message to process
     * @param platform Optional platform information for channel-aware responses
     */
    suspend fun process(
        conversation: Conversation,
        message: IncomingMessage,
        platform: ChatPlatform? = null,
    ): AgentResponse {
        logger.info("Processing message from ${message.sender.id} in chat ${message.chatId}")

        // 1. Load relevant memory
        val globalMemory = memory.load(MemoryScope.Global)
        val chatMemory = memory.load(MemoryScope.Chat(message.chatId))
        val userMemory = memory.load(MemoryScope.User(message.sender.id))

        // 2. Build system prompt with context
        val systemPrompt = buildSystemPrompt(globalMemory, chatMemory, userMemory, platform)

        // 3. Build Koog input (conversation history + current message)
        val userInput = buildKoogInput(conversation, message)

        // 4. Create LLModel for this call
        // TODO: Create LLModel in dependency graph, create capabilities list based on configured provider
        val llmModel = LLModel(
            provider = KoogLLMProvider.OpenAI,
            id = config.model,
            capabilities = listOf(
                LLMCapability.Temperature,
                LLMCapability.Tools,
                LLMCapability.Completion,
                LLMCapability.OpenAIEndpoint.Completions,
            ),
            contextLength = config.maxTokens,
        )

        // 5. Execute via Koog AIAgent
        val agent = AIAgent(
            promptExecutor = promptExecutor,
            llmModel = llmModel,
            strategy = singleRunStrategy(),
            toolRegistry = ToolRegistry.EMPTY,
            systemPrompt = systemPrompt,
            temperature = config.temperature,
            maxIterations = config.maxIterations,
        )

        return try {
            val result = agent.run(userInput)

            // Update memory if needed
            if (config.autoMemory && result.isNotBlank()) {
                extractAndSaveMemory(message, result)
            }

            AgentResponse.Success(content = result)
        } catch (e: Exception) {
            logger.error("Koog agent error: ${e.message}", e)
            AgentResponse.Error("Failed to get response from LLM: ${e.message}")
        } finally {
            agent.close()
        }
    }

    /**
     * Format conversation history and current message into a single string for Koog input.
     */
    private fun buildKoogInput(
        conversation: Conversation,
        message: IncomingMessage,
    ): String = buildString {
        // Add conversation history
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

    private fun buildSystemPrompt(
        globalMemory: Memory,
        chatMemory: Memory,
        userMemory: Memory,
        platform: ChatPlatform?,
    ): String {
        return buildString {
            // Use prompt manager's full prompt (required for normal operation)
            val basePrompt = promptManager.buildFullPrompt()
            appendLine(basePrompt)
            appendLine()

            // Add platform/channel context
            if (platform != null) {
                appendLine("# Communication Channel")
                appendLine()
                appendLine("You are communicating via ${platform.name}.")

                // Add platform-specific notes first (these contain formatting instructions)
                platform.notes?.let {
                    appendLine()
                    appendLine(it)
                }

                // Image/attachment handling
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

            // Add memory context
            val globalStr = globalMemory.toPromptString()
            val chatStr = chatMemory.toPromptString()
            val userStr = userMemory.toPromptString()

            if (globalStr.isNotBlank() || chatStr.isNotBlank() || userStr.isNotBlank()) {
                appendLine("# Relevant Memory")
                appendLine()
                if (globalStr.isNotBlank()) appendLine(globalStr)
                if (chatStr.isNotBlank()) appendLine(chatStr)
                if (userStr.isNotBlank()) appendLine(userStr)
            }

            // Add available skills context
            if (skills.all().isNotEmpty()) {
                appendLine("# Available Tools")
                appendLine()
                appendLine("You have access to the following tools:")
                for (skill in skills.all()) {
                    appendLine("- ${skill.name}: ${skill.description}")
                }
                appendLine()
                appendLine("Use tools when needed to help the user. Always prefer using tools over making things up.")
            }

            // Add critical instruction about conversation history
            appendLine()
            appendLine("# IMPORTANT: Conversation History")
            appendLine()
            appendLine("The conversation history above shows PREVIOUS exchanges that are ALREADY COMPLETED.")
            appendLine("Each user message is a separate request - focus only on what the user is asking NOW.")
        }
    }

    private fun buildMessages(
        systemPrompt: String,
        conversation: Conversation,
        message: IncomingMessage,
    ): List<Message> {
        val messages = mutableListOf<Message>()

        // System message
        messages.add(Message.system(systemPrompt))

        // Conversation history (previous completed exchanges)
        val history = conversation.messages.takeLast(config.maxHistoryMessages)
        if (history.isNotEmpty()) {
            // Add a marker for historical context
            messages.add(Message.system("[Previous conversation history - these requests are COMPLETED]"))

            for (msg in history) {
                when (msg.role) {
                    ConversationRole.USER -> messages.add(Message.user(msg.content))
                    ConversationRole.ASSISTANT -> messages.add(Message.assistant(msg.content))
                }
            }

            // Clear marker that history ends here
            messages.add(Message.system("[End of history - respond ONLY to the following NEW message]"))
        }

        // Current message (the one to actually respond to)
        val userContent = when (val content = message.content) {
            is MessageContent.Text -> content.text
            is MessageContent.Image -> "[Image${content.caption?.let { ": $it" } ?: ""}]"
            is MessageContent.File -> "[File: ${content.filename}]"
            is MessageContent.Audio -> "[Audio message]"
            is MessageContent.Sticker -> "[Sticker: ${content.emoji ?: content.id}]"
            is MessageContent.Reaction -> "[Reaction: ${content.emoji}]"
        }
        messages.add(Message.user(userContent))

        return messages
    }

    /**
     * Execute a tool call and return both the result string and any attachment.
     */
    private suspend fun executeToolCallWithAttachment(
        toolCall: ToolCall,
        context: SkillContext,
    ): Pair<String, ResponseAttachment?> {
        val skillName = toolCall.function.name
        val skill = skills.get(skillName)

        if (skill == null) {
            logger.warn("Unknown skill requested: $skillName")
            return "Error: Unknown tool '$skillName'" to null
        }

        // Parse arguments
        val arguments = try {
            if (toolCall.function.arguments.isBlank()) {
                emptyMap()
            } else {
                val jsonArgs = json.parseToJsonElement(toolCall.function.arguments) as? JsonObject
                    ?: return "Error: Invalid arguments format" to null

                jsonArgs.mapValues { (_, value) ->
                    // Convert JSON values to appropriate types
                    when (value) {
                        is JsonPrimitive if value.isString -> value.content
                        is JsonPrimitive -> {
                            value.content.toIntOrNull()
                                ?: value.content.toLongOrNull()
                                ?: value.content.toDoubleOrNull()
                                ?: value.content.toBooleanStrictOrNull()
                                ?: value.content
                        }

                        is JsonArray -> {
                            value.map { elem ->
                                if (elem is JsonPrimitive) elem.content
                                else elem.toString()
                            }
                        }

                        else -> value.toString()
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to parse tool arguments: ${e.message}")
            return "Error: Failed to parse arguments: ${e.message}" to null
        }

        logger.info("Executing skill: $skillName with args: $arguments")

        // Execute the skill with context
        val result = try {
            skill.execute(SkillParameters(arguments, context))
        } catch (e: Exception) {
            logger.error("Skill execution failed: ${e.message}")
            SkillResult.Error("Execution failed: ${e.message}")
        }

        // Extract attachment if present
        val attachment = when (result) {
            is SkillResult.ImageAttachment -> ResponseAttachment.Image(
                data = result.imageData,
                mimeType = result.mimeType,
                caption = result.caption,
            )
            is SkillResult.FileAttachment -> ResponseAttachment.File(
                data = result.fileData,
                filename = result.filename,
                mimeType = result.mimeType,
            )
            else -> null
        }

        return result.toResponseString() to attachment
    }

    private suspend fun extractAndSaveMemory(message: IncomingMessage, response: String) {
        // Simple heuristic: if the user shares personal info, save it
        // This is a placeholder - in production, you'd use more sophisticated extraction
        val userText = (message.content as? MessageContent.Text)?.text ?: return

        // Look for patterns like "my name is", "I work at", etc.
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
    val timestamp: Long = System.currentTimeMillis(),
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
