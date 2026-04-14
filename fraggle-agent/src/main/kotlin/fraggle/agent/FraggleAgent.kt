package fraggle.agent

import ai.koog.agents.core.agent.AIAgentService
import ai.koog.agents.core.agent.ToolCalls
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.HistoryCompressionStrategy
import ai.koog.agents.core.dsl.extension.nodeLLMCompressHistory
import ai.koog.agents.core.dsl.extension.nodeLLMRequestMultiple
import ai.koog.agents.core.dsl.extension.nodeLLMSendMultipleToolResults
import ai.koog.agents.core.dsl.extension.nodeExecuteMultipleTools
import ai.koog.agents.core.dsl.extension.onMultipleAssistantMessages
import ai.koog.agents.core.dsl.extension.onMultipleToolCalls
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.tracing.feature.Tracing
import ai.koog.agents.memory.feature.AgentMemory
import ai.koog.agents.memory.providers.AgentMemoryProvider
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.ContentPart
import ai.koog.prompt.message.Message
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import fraggle.chat.ChatPlatform
import fraggle.chat.IncomingMessage
import fraggle.chat.MessageContent
import fraggle.memory.Fact
import fraggle.memory.Memory
import fraggle.memory.MemoryScope
import fraggle.memory.MemoryStore
import fraggle.prompt.PromptManager
import fraggle.provider.Usage
import fraggle.tracing.FraggleTraceProcessor
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetIn
import kotlinx.datetime.toLocalDateTime
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
    private val promptExecutor: PromptExecutor,
    private val toolRegistry: ToolRegistry,
    private val memory: MemoryStore,
    private val memoryProvider: AgentMemoryProvider,
    private val config: AgentConfig,
    private val promptManager: PromptManager,
    private val traceProcessor: FraggleTraceProcessor?,
) : Closeable {
    private val logger = LoggerFactory.getLogger(FraggleAgent::class.java)

    private val agentTemplate by lazy { promptManager.getTemplate(PromptManager.AGENT_FILE) }
    private val memoryTemplate by lazy { promptManager.getTemplate(PromptManager.MEMORY_FILE) }

    private val llmModel = LLModel(
        provider = KoogLLMProvider.OpenAI,
        id = config.model,
        capabilities = buildList {
            add(LLMCapability.Temperature)
            add(LLMCapability.Tools)
            add(LLMCapability.Completion)
            add(LLMCapability.OpenAIEndpoint.Completions)
            if (config.vision) add(LLMCapability.Vision.Image)
        },
        contextLength = config.maxTokens,
    )

    /**
     * Build OpenAI-compatible chat params from config, using additionalProperties
     * for LM Studio-specific sampling params (top_k, min_p, repeat_penalty).
     */
    private val chatParams: OpenAIChatParams by lazy {
        val extraProps = buildMap<String, JsonElement> {
            config.topK?.let { put("top_k", JsonPrimitive(it)) }
            config.minP?.let { put("min_p", JsonPrimitive(it)) }
            config.repeatPenalty?.let { put("repeat_penalty", JsonPrimitive(it)) }
        }
        OpenAIChatParams(
            temperature = if (config.topP == null) config.temperature else null,
            topP = config.topP,
            additionalProperties = extraProps.ifEmpty { null },
        )
    }

    private val agentService = AIAgentService(
        promptExecutor = promptExecutor,
        llmModel = llmModel,
        strategy = singleRunStrategy(ToolCalls.PARALLEL),
        toolRegistry = toolRegistry,
        systemPrompt = null,
        temperature = config.temperature,
        maxIterations = config.maxIterations,
    ) {
        install(AgentMemory) {
            this.memoryProvider = this@FraggleAgent.memoryProvider
            agentName = "fraggle"
            productName = "fraggle"
        }
        if (traceProcessor != null) {
            install(Tracing) {
                addMessageProcessor(traceProcessor)
            }
        }
    }

    private val memoryExtractionJson = Json { ignoreUnknownKeys = true }

    /**
     * Process an incoming message and generate a response.
     * Returns a [ProcessResult] containing the response and the updated conversation
     * (which may have been compressed if it exceeded [AgentConfig.maxHistoryMessages]).
     */
    suspend fun process(
        conversation: Conversation,
        message: IncomingMessage,
        platform: ChatPlatform? = null,
    ): ProcessResult {
        logger.info("Processing message from ${message.sender.id} in chat ${message.chatId}")

        // Pre-compress conversation if it exceeds the history limit
        val compressed = compressIfNeeded(conversation)

        // Create per-request execution context for tools
        val executionContext = ToolExecutionContext(
            chatId = message.chatId,
            userId = message.sender.id,
        )

        val response = try {
            val systemPrompt = buildBaseSystemPrompt(
                promptManager = promptManager,
                platform = platform,
                chatId = message.chatId,
                senderId = message.sender.id,
                historySummary = compressed.historySummary,
            )
            val imageAttachments = if (config.vision) message.imageAttachments else emptyList()
            val userInput = buildKoogInput(message)
            val agentPrompt = prompt(message.id, chatParams) {
                system(systemPrompt)

                compressed.messages.forEach { contextMessage ->
                    if (contextMessage.role == ConversationRole.USER) {
                        user(contextMessage.content)
                    } else {
                        assistant(contextMessage.content)
                    }
                }

                // Include image attachments with the user's text in a single multipart message.
                // The text will also be appended by Koog as agentInput, but combining them here
                // ensures the LLM sees images associated with the user's text in one message.
                if (imageAttachments.isNotEmpty()) {
                    user {
                        text(userInput)
                        for (attachment in imageAttachments) {
                            image(
                                ContentPart.Image(
                                    content = AttachmentContent.Binary.Bytes(attachment.data),
                                    format = attachment.mimeType.substringAfter("/", "jpeg"),
                                    mimeType = attachment.mimeType,
                                    fileName = attachment.filename,
                                )
                            )
                        }
                    }
                }
            }

            val result = withContext(ToolExecutionContext.asContextElement(executionContext)) {
                agentService.createAgentAndRun(
                    agentInput = userInput,
                    agentConfig = AIAgentConfig(
                        prompt = agentPrompt,
                        model = llmModel,
                        maxAgentIterations = agentService.agentConfig.maxAgentIterations,
                        missingToolsConversionStrategy = agentService.agentConfig.missingToolsConversionStrategy,
                        responseProcessor = agentService.agentConfig.responseProcessor,
                    )
                )
            }

            if (config.autoMemory && result.isNotBlank()) {
                extractMemoryViaLLM(message, result)
            }

            AgentResponse.Success(
                content = result,
                attachments = executionContext.attachments.toList(),
            )
        } catch (e: Exception) {
            logger.error("Koog agent error: ${e.message}", e)
            AgentResponse.Error("Failed to get response from LLM: ${e.message}")
        }

        return ProcessResult(response = response, conversation = compressed)
    }

    override fun close() {
        promptExecutor.close()
    }

    /**
     * Compress conversation history if it exceeds the configured limit.
     * Keeps the most recent 2/3 of messages verbatim and compresses older messages
     * into a TLDR summary. Existing summaries are included as context for cumulative compression.
     */
    private suspend fun compressIfNeeded(conversation: Conversation): Conversation {
        if (conversation.messages.size <= config.maxHistoryMessages) {
            return conversation
        }

        val keepCount = (config.maxHistoryMessages * 2) / 3
        val recentMessages = conversation.messages.takeLast(keepCount)
        val oldMessages = conversation.messages.dropLast(keepCount)

        if (oldMessages.isEmpty()) return conversation

        logger.info(
            "Compressing conversation history: {} total messages, keeping {} recent, compressing {}",
            conversation.messages.size, recentMessages.size, oldMessages.size,
        )

        val oldMessagesText = oldMessages.joinToString("\n") { msg ->
            val role = if (msg.role == ConversationRole.USER) "User" else "Assistant"
            "$role: ${msg.content}"
        }

        val existingSummaryBlock = conversation.historySummary?.let {
            "Previous conversation summary:\n$it\n\n"
        } ?: ""

        val compressionInput = buildString {
            append(existingSummaryBlock)
            appendLine("Messages to compress:")
            appendLine(oldMessagesText)
            appendLine()
            appendLine("Create a concise TL;DR summary that captures:")
            appendLine("- Key topics discussed and decisions made")
            appendLine("- Important facts, preferences, or requests from the user")
            appendLine("- Current status of any ongoing tasks or questions")
            appendLine("- Any context the assistant would need to continue the conversation naturally")
        }

        return try {
            val compressionPrompt = prompt("history-compression", OpenAIChatParams(temperature = 0.1, reasoningEffort = ReasoningEffort.NONE)) {
                system("You are a conversation summarizer. Produce a concise but comprehensive summary of the conversation history provided. Preserve all important context, decisions, and facts. Output only the summary, no preamble.")
                user(compressionInput)
            }

            val responses = promptExecutor.execute(prompt = compressionPrompt, model = llmModel)
            val summary = responses.filterIsInstance<Message.Assistant>().firstOrNull()?.content
                ?.let { ReasoningContentFilter.strip(it) }
                ?.takeIf { it.isNotBlank() }

            if (summary == null) {
                logger.warn("History compression returned empty result, keeping original conversation")
                return conversation
            }

            logger.info("History compressed successfully ({} chars summary)", summary.length)
            conversation.copy(
                messages = recentMessages,
                historySummary = summary,
            )
        } catch (e: Exception) {
            logger.warn("History compression failed, keeping original conversation: ${e.message}")
            conversation
        }
    }

    /**
     * Build the static base system prompt (identity, instructions).
     * Called once during agent construction. Tool descriptions are handled natively by Koog.
     */
    private suspend fun buildBaseSystemPrompt(
        promptManager: PromptManager,
        platform: ChatPlatform?,
        chatId: String,
        senderId: String,
        historySummary: String? = null,
    ): String = buildString {
        appendLine(promptManager.buildFullPrompt())
        appendLine()

        // Inject current host timestamp so the agent always knows the current date/time
        val now = Clock.System.now()
        val hostTz = TimeZone.currentSystemDefault()
        val local = now.toLocalDateTime(hostTz)
        val offset = now.offsetIn(hostTz)
        appendLine("Current time: ${local}${offset} ($hostTz)")
        appendLine()

        val historySection = agentTemplate?.renderSection("conversation history")
        if (historySection != null) {
            appendLine("# IMPORTANT: Conversation History")
            appendLine()
            appendLine(historySection)
        } else {
            logger.warn("AGENT.md missing 'Conversation History' section")
        }

        if (historySummary != null) {
            val compressedSection = agentTemplate?.renderSection(
                "compressed conversation history",
                mapOf("compressed_summary" to historySummary),
            )
            if (compressedSection != null) {
                appendLine()
                appendLine(compressedSection)
            } else {
                appendLine()
                appendLine("## Earlier Conversation Summary")
                appendLine(historySummary)
            }
            appendLine()
        }

        if (platform != null) {
            appendLine("# Communication Channel")
            appendLine()

            val platformContext = agentTemplate?.renderSection(
                "platform context",
                mapOf("platform_name" to platform.name),
            )
            appendLine(platformContext ?: "You are communicating via ${platform.name}.")

            platform.notes?.let {
                appendLine()
                appendLine(it)
            }

            if (platform.supportsAttachments) {
                appendLine()
                val imageHandling = agentTemplate?.renderSection("image handling")
                if (imageHandling == null) {
                    appendLine("IMAGE HANDLING:")
                    appendLine("- To include an image in your response, use this syntax: [[image:URL]]")
                    appendLine("- Example: [[image:https://example.com/photo.jpg]]")
                    appendLine("- The image will be downloaded and sent as an attachment WITH your text in one cohesive message")
                    appendLine("- Only ONE image can be sent per message on this platform")
                    appendLine("- For screenshots, use the screenshot_page tool (it requires browser automation)")
                    appendLine("- Do NOT use markdown image syntax like ![alt](url) - it won't display")
                } else {
                    appendLine(imageHandling)
                }
            }

            if (!platform.supportsInlineImages) {
                appendLine()
                val inlineWarning = agentTemplate?.renderSection("inline images warning")
                if (inlineWarning != null) {
                    appendLine(inlineWarning)
                } else {
                    appendLine("IMPORTANT: Raw image URLs or markdown image syntax will NOT display as images.")
                    appendLine("Always use [[image:URL]] syntax to share images.")
                }
            }
            appendLine()
        }

        // Memory context
        val globalStr = memory.load(MemoryScope.Global).toPromptString()
        val chatStr = memory.load(MemoryScope.Chat(chatId)).toPromptString()
        val userStr = memory.load(MemoryScope.User(senderId)).toPromptString()

        if (globalStr.isNotBlank() || chatStr.isNotBlank() || userStr.isNotBlank()) {
            appendLine("# Relevant Memory")
            appendLine()
            if (globalStr.isNotBlank()) appendLine(globalStr)
            if (chatStr.isNotBlank()) appendLine(chatStr)
            if (userStr.isNotBlank()) appendLine(userStr)
        }
    }

    private fun buildKoogInput(
        message: IncomingMessage,
    ): String = buildString {
        val userContent = when (val content = message.content) {
            is MessageContent.Text -> content.text
            is MessageContent.Image -> "[Image${content.caption?.let { ": $it" } ?: ""}]"
            is MessageContent.File -> "[File: ${content.filename}]"
            is MessageContent.Audio -> "[Audio message]"
            is MessageContent.Sticker -> "[Sticker: ${content.emoji ?: content.id}]"
            is MessageContent.Reaction -> "[Reaction: ${content.emoji}]"
        }
        append(userContent)

        // When vision is enabled and images are attached, add a hint
        if (config.vision && message.imageAttachments.isNotEmpty()) {
            val count = message.imageAttachments.size
            if (userContent.isBlank()) {
                append("[User sent ${if (count == 1) "an image" else "$count images"}]")
            }
        }
    }

    private suspend fun extractMemoryViaLLM(message: IncomingMessage, response: String) {
        val userText = buildKoogInput(message)

        try {
            val userScope = MemoryScope.User(message.sender.id)
            val existingMemory = memory.load(userScope)
            val existingFacts = existingMemory.facts

            // Phase 1: Extract new facts from the exchange
            val newFactStrings = extractNewFacts(userText, response, existingFacts.map { it.content })
            if (newFactStrings.isEmpty()) return

            val now = Clock.System.now()

            // Phase 2: Reconcile new facts with existing facts via LLM
            if (existingFacts.isNotEmpty()) {
                val changes = reconcileFacts(existingFacts.map { it.content }, newFactStrings)

                val deletions = changes.filter { it.status == "deleted" }

                // Apply changes to existing facts
                val factsToSave = existingFacts.toMutableList()

                // Apply deletions — match by exact content first, then fuzzy
                for (rf in deletions) {
                    val exactIndex = factsToSave.indexOfFirst { it.content == rf.fact }
                    if (exactIndex >= 0) {
                        factsToSave.removeAt(exactIndex)
                    } else {
                        val closest = findClosestFact(rf.fact, factsToSave)
                        if (closest != null) factsToSave.remove(closest)
                    }
                }

                // Apply updates — find closest existing fact and replace it
                for (rf in changes.filter { it.status == "updated" }) {
                    val original = findClosestFact(rf.fact, factsToSave)
                    if (original != null) {
                        val index = factsToSave.indexOf(original)
                        factsToSave[index] = Fact(
                            content = rf.fact,
                            timestamp = original.timestamp,
                            updatedAt = now,
                            source = original.source,
                        )
                    } else {
                        // No match found — treat as new
                        factsToSave.add(Fact(content = rf.fact, timestamp = now, source = "conversation"))
                    }
                }

                // Append new facts
                for (rf in changes.filter { it.status == "new" }) {
                    factsToSave.add(Fact(content = rf.fact, timestamp = now, source = "conversation"))
                }

                memory.save(userScope, Memory(scope = userScope, facts = factsToSave, lastUpdated = now))

                val updates = changes.count { it.status == "updated" }
                val newCount = changes.count { it.status == "new" }
                logger.debug(
                    "Memory reconciled for user {}: {} existing, {} updated, {} new, {} deleted → {} total",
                    message.sender.id, existingFacts.size, updates, newCount, deletions.size, factsToSave.size,
                )
            } else {
                // No existing facts — save new facts directly
                val factsToSave = newFactStrings.map { Fact(content = it, timestamp = now, source = "conversation") }
                memory.save(userScope, Memory(scope = userScope, facts = factsToSave, lastUpdated = now))

                logger.debug("Saved {} new memory facts for user {}", factsToSave.size, message.sender.id)
            }
        } catch (e: Exception) {
            logger.warn("Failed to extract memory facts: ${e.message}")
        }
    }

    /**
     * Find the existing fact most similar to a given text, for timestamp preservation.
     * Uses word overlap to find the best match.
     */
    private fun findClosestFact(text: String, existingFacts: List<Fact>): Fact? {
        val targetWords = text.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        if (targetWords.isEmpty()) return null

        return existingFacts.maxByOrNull { fact ->
            val words = fact.content.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
            targetWords.intersect(words).size
        }
    }

    /**
     * Phase 1: Use LLM to extract new personal facts from the exchange.
     * Returns only genuinely new facts (the LLM sees existing facts to avoid extraction of duplicates).
     */
    private suspend fun extractNewFacts(
        userText: String,
        response: String,
        existingFacts: List<String>,
    ): List<String> {
        val existingFactsBlock = if (existingFacts.isNotEmpty()) {
            val factsList = existingFacts.joinToString("\n") { "- $it" }
            "\nAlready stored facts about this user:\n$factsList\n"
        } else {
            ""
        }

        val tmpl = memoryTemplate
        val systemText = tmpl?.renderSection("extraction system")
        val inputText = tmpl?.renderSection(
            "extraction input",
            mapOf(
                "existing_facts_block" to existingFactsBlock,
                "user_text" to userText,
                "response" to response,
            ),
        )

        if (systemText == null || inputText == null) {
            logger.warn("MEMORY.md missing extraction sections, skipping fact extraction")
            return emptyList()
        }

        val extractionPrompt = prompt("memory-extraction", OpenAIChatParams(temperature = 0.1, reasoningEffort = ReasoningEffort.NONE)) {
            system(systemText)
            user(inputText)
        }

        val responses = promptExecutor.execute(prompt = extractionPrompt, model = llmModel)
        val result = responses.filterIsInstance<Message.Assistant>().firstOrNull()?.content
            ?.let { ReasoningContentFilter.strip(it) }
            ?: return emptyList()

        val factsJson = result
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()

        if (factsJson == "[]" || factsJson.isBlank()) return emptyList()

        return memoryExtractionJson.decodeFromString<List<String>>(factsJson)
    }

    /**
     * Phase 2: Use LLM to reconcile existing facts with newly extracted facts.
     *
     * The LLM merges related facts (e.g. two hobby lists become one), updates superseded facts
     * (e.g. new job replaces old), preserves historical context (e.g. "previously worked at"),
     * and removes duplicates — producing a single clean fact list with status annotations.
     */
    private suspend fun reconcileFacts(
        existingFacts: List<String>,
        newFacts: List<String>,
    ): List<ReconciledFact> {
        val existingBlock = existingFacts.joinToString("\n") { "- $it" }
        val newBlock = newFacts.joinToString("\n") { "- $it" }

        val fallback = { newFacts.map { ReconciledFact(it, "new") } }

        val tmpl = memoryTemplate
        val systemText = tmpl?.renderSection("reconciliation system")
        val inputText = tmpl?.renderSection(
            "reconciliation input",
            mapOf(
                "existing_block" to existingBlock,
                "new_block" to newBlock,
            ),
        )

        if (systemText == null || inputText == null) {
            logger.warn("MEMORY.md missing reconciliation sections, falling back to append")
            return fallback()
        }

        val reconcilePrompt = prompt("memory-reconciliation", OpenAIChatParams(temperature = 0.1, reasoningEffort = ReasoningEffort.NONE)) {
            system(systemText)
            user(inputText)
        }

        val responses = promptExecutor.execute(prompt = reconcilePrompt, model = llmModel)
        val result = responses.filterIsInstance<Message.Assistant>().firstOrNull()?.content
            ?.let { ReasoningContentFilter.strip(it) }
            ?: return fallback()

        val factsJson = result
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()

        if (factsJson.isBlank()) return fallback()

        return try {
            memoryExtractionJson.decodeFromString<List<ReconciledFact>>(factsJson)
        } catch (e: Exception) {
            logger.warn("Failed to parse reconciled facts, falling back to append: ${e.message}")
            fallback()
        }
    }
}

@Serializable
private data class ReconciledFact(
    val fact: String,
    val status: String,
)

/**
 * Custom strategy that mirrors [singleRunStrategy] (sequential tool calls)
 * but adds a conditional [nodeLLMCompressHistory] node to handle cases where
 * tool-heavy runs accumulate many messages during a single agent execution.
 */
private fun compressingSingleRunStrategy() = strategy<String, String>("compressing_single_run") {
    val nodeCallLLM by nodeLLMRequestMultiple()
    val nodeExecuteTool by nodeExecuteMultipleTools(parallelTools = false)
    val nodeSendToolResult by nodeLLMSendMultipleToolResults()
    val nodeCompressHistory by nodeLLMCompressHistory<List<ReceivedToolResult>>(
        strategy = HistoryCompressionStrategy.WholeHistory,
        preserveMemory = false,
    )

    edge(nodeStart forwardTo nodeCallLLM)
    edge(nodeCallLLM forwardTo nodeExecuteTool onMultipleToolCalls { true })
    edge(
        nodeCallLLM forwardTo nodeFinish
            onMultipleAssistantMessages { true }
            transformed { ReasoningContentFilter.strip(it.joinToString("\n") { message -> message.content }) }
    )

    edge(nodeExecuteTool forwardTo nodeCompressHistory)
    edge(nodeCompressHistory forwardTo nodeSendToolResult)

    edge(nodeSendToolResult forwardTo nodeExecuteTool onMultipleToolCalls { true })
    edge(
        nodeSendToolResult forwardTo nodeFinish
            onMultipleAssistantMessages { true }
            transformed { ReasoningContentFilter.strip(it.joinToString("\n") { message -> message.content }) }
    )
}

/**
 * Agent configuration.
 */
data class AgentConfig(
    val model: String = "",
    val temperature: Double = 0.7,
    val topP: Double? = null,
    val topK: Int? = null,
    val minP: Double? = null,
    val repeatPenalty: Double? = null,
    val maxTokens: Long = 4096,
    val maxIterations: Int = 10,
    val maxHistoryMessages: Int = 20,
    val autoMemory: Boolean = true,
    val vision: Boolean = false,
)

/**
 * Result of processing a message, including the updated conversation state.
 */
data class ProcessResult(
    val response: AgentResponse,
    val conversation: Conversation,
)

/**
 * Conversation context.
 */
data class Conversation(
    val id: String,
    val chatId: String,
    val messages: List<ConversationMessage> = emptyList(),
    val historySummary: String? = null,
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
