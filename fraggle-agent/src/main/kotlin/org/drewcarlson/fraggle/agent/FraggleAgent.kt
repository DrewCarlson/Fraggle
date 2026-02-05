package org.drewcarlson.fraggle.agent

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
 * Implements a ReAct-style agent loop with tool calling.
 */
class FraggleAgent(
    private val provider: LLMProvider,
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

        // 3. Build message history
        val messages = buildMessages(systemPrompt, conversation, message)

        // 4. Get available tools
        val tools = skills.toOpenAITools()

        // 5. Execute agent loop
        val currentMessages = messages.toMutableList()
        var iterations = 0
        val collectedAttachments = mutableListOf<ResponseAttachment>()

        while (iterations < config.maxIterations) {
            iterations++
            logger.debug("Agent iteration $iterations")

            // Make LLM request
            val request = ChatRequest(
                model = config.model,
                messages = currentMessages,
                tools = tools.takeIf { it.isNotEmpty() },
                temperature = config.temperature,
                maxTokens = config.maxTokens,
            )

            val response = try {
                provider.chat(request)
            } catch (e: LLMProviderException) {
                logger.error("LLM provider error: ${e.message}")
                return AgentResponse.Error("Failed to get response from LLM: ${e.message}")
            }

            val choice = response.choices.firstOrNull()
                ?: return AgentResponse.Error("No response from LLM")

            val assistantMessage = choice.message

            // Check if we have tool calls
            if (!assistantMessage.toolCalls.isNullOrEmpty()) {
                logger.debug("Handling ${assistantMessage.toolCalls.size} tool calls")

                // Add assistant message with tool calls
                currentMessages.add(assistantMessage)

                // Create skill context for this execution
                val skillContext = SkillContext(
                    chatId = message.chatId,
                    userId = message.sender.id,
                )

                // Execute each tool call
                for (toolCall in assistantMessage.toolCalls) {
                    val (result, attachment) = executeToolCallWithAttachment(toolCall, skillContext)
                    currentMessages.add(Message.tool(toolCall.id, result))

                    // Collect any attachments from tool results
                    if (attachment != null) {
                        collectedAttachments.add(attachment)
                    }
                }

                // Continue loop to get next response
                continue
            }

            // No tool calls, we have a final response
            val responseText = assistantMessage.content ?: ""

            // Update memory if needed (extract facts from the conversation)
            if (config.autoMemory && responseText.isNotBlank()) {
                extractAndSaveMemory(message, responseText)
            }

            return AgentResponse.Success(
                content = responseText,
                usage = response.usage,
                iterations = iterations,
                attachments = collectedAttachments,
            )
        }

        logger.warn("Agent reached max iterations ($config.maxIterations)")
        return AgentResponse.Error("Agent reached maximum iterations without completing")
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
                if (!platform.supportsInlineImages) {
                    appendLine()
                    appendLine("IMAGE HANDLING:")
                    appendLine("- Do NOT include markdown image syntax like ![alt](url)")
                    appendLine("- Do NOT include raw image URLs expecting them to display as images")
                    appendLine("- When you use tools like send_image or screenshot_page, the image is AUTOMATICALLY sent as an attachment")
                    appendLine("- Simply confirm the action was completed; do not try to embed or reference the image in your text")
                }

                if (platform.supportsAttachments) {
                    appendLine()
                    appendLine("File and image attachments ARE supported and are sent automatically when using relevant tools.")
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
    val maxTokens: Int = 4096,
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
