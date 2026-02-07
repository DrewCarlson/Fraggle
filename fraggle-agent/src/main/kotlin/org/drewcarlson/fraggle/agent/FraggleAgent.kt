package org.drewcarlson.fraggle.agent

import ai.koog.agents.core.agent.AIAgentService
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.memory.feature.AgentMemory
import ai.koog.agents.memory.providers.AgentMemoryProvider
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.drewcarlson.fraggle.chat.ChatPlatform
import org.drewcarlson.fraggle.chat.IncomingMessage
import org.drewcarlson.fraggle.chat.MessageContent
import org.drewcarlson.fraggle.memory.Fact
import org.drewcarlson.fraggle.memory.Memory
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
    private val promptExecutor: PromptExecutor,
    private val toolRegistry: ToolRegistry,
    private val memory: MemoryStore,
    private val memoryProvider: AgentMemoryProvider,
    private val sandbox: Sandbox,
    private val config: AgentConfig,
    promptManager: PromptManager,
) : Closeable {
    private val logger = LoggerFactory.getLogger(FraggleAgent::class.java)

    private val llmModel = LLModel(
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

    private val agentService = AIAgentService(
        promptExecutor = promptExecutor,
        llmModel = llmModel,
        strategy = singleRunStrategy(),
        toolRegistry = toolRegistry,
        systemPrompt = buildBaseSystemPrompt(promptManager),
        temperature = config.temperature,
        maxIterations = config.maxIterations,
    ) {
        install(AgentMemory) {
            this.memoryProvider = this@FraggleAgent.memoryProvider
            agentName = "fraggle"
            productName = "fraggle"
        }
    }

    private val memoryExtractionJson = Json { ignoreUnknownKeys = true }

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

    private suspend fun extractMemoryViaLLM(message: IncomingMessage, response: String) {
        val userText = (message.content as? MessageContent.Text)?.text ?: return

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
                val reconciled = reconcileFacts(existingFacts.map { it.content }, newFactStrings)

                // Safety: verify the LLM didn't silently drop existing facts.
                // Each existing fact should be accounted for as "unchanged" or "updated".
                val accountedFor = reconciled.count { it.status == "unchanged" || it.status == "updated" }
                if (accountedFor < (existingFacts.size + 1) / 2) {
                    logger.warn(
                        "Reconciliation accounted for only {} of {} existing facts — falling back to append",
                        accountedFor, existingFacts.size,
                    )
                    val appended = existingFacts + newFactStrings.map {
                        Fact(content = it, timestamp = now, source = "conversation")
                    }
                    memory.save(userScope, Memory(scope = userScope, facts = appended, lastUpdated = now))
                    return
                }

                // Build a lookup from content → existing Fact for timestamp preservation
                val existingByContent = existingFacts.associateBy { it.content }

                val factsToSave = reconciled.map { rf ->
                    when (rf.status) {
                        "unchanged" -> {
                            // Preserve original timestamps — try exact match first, then fuzzy
                            existingByContent[rf.fact]
                                ?: findClosestFact(rf.fact, existingFacts)?.let { original ->
                                    original.copy(content = rf.fact)
                                }
                                ?: Fact(content = rf.fact, timestamp = now, source = "conversation")
                        }
                        "updated" -> {
                            // Find the closest existing fact to preserve its creation time
                            val original = findClosestFact(rf.fact, existingFacts)
                            Fact(
                                content = rf.fact,
                                timestamp = original?.timestamp ?: now,
                                updatedAt = now,
                                source = original?.source ?: "conversation",
                            )
                        }
                        else -> {
                            // "new" — brand new fact
                            Fact(content = rf.fact, timestamp = now, source = "conversation")
                        }
                    }
                }

                memory.save(userScope, Memory(scope = userScope, facts = factsToSave, lastUpdated = now))

                logger.debug(
                    "Memory reconciled for user {}: {} existing + {} new → {} total",
                    message.sender.id, existingFacts.size, newFactStrings.size, factsToSave.size,
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

        val extractionInput = buildString {
            appendLine("Extract personal facts the user revealed about themselves in this exchange.")
            appendLine()
            appendLine("Rules:")
            appendLine("- Use concise \"Key: Value\" format for each fact.")
            appendLine("- BAD: \"The user's name is Alice\" or \"User enjoys playing guitar\"")
            appendLine("- GOOD: \"Name: Alice\" or \"Hobbies: playing guitar\"")
            appendLine("- Group related items together (e.g., \"Hobbies: guitar, programming, snowboarding\").")
            appendLine("- Do NOT extract opinions, questions, or temporary states.")
            appendLine("- Do NOT extract facts that are already stored (see below).")
            appendLine("- Do NOT split a single piece of information into multiple near-identical facts.")
            appendLine("- Return a JSON array of strings, or [] if no NEW facts.")
            if (existingFactsBlock.isNotBlank()) {
                appendLine()
                append(existingFactsBlock)
            }
            appendLine()
            appendLine("Exchange:")
            appendLine("User: $userText")
            appendLine("Assistant: $response")
        }

        val extractionPrompt = prompt("memory-extraction", LLMParams(temperature = 0.1)) {
            system("You are a fact extraction assistant. Respond ONLY with a JSON array of strings. Use concise \"Key: Value\" format (e.g., \"Name: Alice\", \"Lives in: Berlin\").")
            user(extractionInput)
        }

        val responses = promptExecutor.execute(prompt = extractionPrompt, model = llmModel)
        val result = responses.firstOrNull()?.content?.trim() ?: return emptyList()

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

        val reconcileInput = buildString {
            appendLine("You are maintaining a personal fact store about a user. You have an EXISTING set of facts and NEW facts just extracted from a conversation.")
            appendLine()
            appendLine("IMPORTANT: Your output MUST account for EVERY existing fact. Do NOT drop any existing facts.")
            appendLine()
            appendLine("Produce a single, reconciled list of facts by following these rules:")
            appendLine("1. KEEP unrelated existing facts UNCHANGED — copy their exact text verbatim, do not reformat them.")
            appendLine("2. MERGE related facts into one (e.g., two separate hobby lists → one combined list).")
            appendLine("3. UPDATE facts when new information supersedes old (e.g., new job replaces old job).")
            appendLine("4. PRESERVE HISTORY: when a fact changes (e.g., user changed jobs), keep the old value as a separate historical fact (e.g., \"Previously worked at: Google\").")
            appendLine("5. REMOVE only exact duplicates where the meaning is identical.")
            appendLine()
            appendLine("For each fact, indicate its status:")
            appendLine("- \"unchanged\": the fact is identical to an existing fact (no modification at all)")
            appendLine("- \"updated\": the fact is a modified version of an existing fact (merged, expanded, or reworded)")
            appendLine("- \"new\": the fact is entirely new information (including new historical facts)")
            appendLine()
            appendLine("EXISTING facts:")
            appendLine(existingBlock)
            appendLine()
            appendLine("NEW facts:")
            appendLine(newBlock)
            appendLine()
            appendLine("Return a JSON array of objects, each with \"fact\" (string) and \"status\" (string). Example:")
            appendLine("""[{"fact": "Works at: Microsoft", "status": "new"}, {"fact": "Previously worked at: Google", "status": "new"}, {"fact": "Name: Alice", "status": "unchanged"}]""")
        }

        val reconcilePrompt = prompt("memory-reconciliation", LLMParams(temperature = 0.1)) {
            system("You are a fact reconciliation assistant. You MUST include every existing fact in your output (as unchanged, updated, or merged). Respond ONLY with a JSON array of objects. Each object has \"fact\" (string) and \"status\" (\"unchanged\", \"updated\", or \"new\").")
            user(reconcileInput)
        }

        val responses = promptExecutor.execute(prompt = reconcilePrompt, model = llmModel)
        val result = responses.firstOrNull()?.content?.trim()
            ?: return existingFacts.map { ReconciledFact(it, "unchanged") } +
                newFacts.map { ReconciledFact(it, "new") }

        val factsJson = result
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()

        if (factsJson.isBlank()) {
            return existingFacts.map { ReconciledFact(it, "unchanged") } +
                newFacts.map { ReconciledFact(it, "new") }
        }

        return try {
            memoryExtractionJson.decodeFromString<List<ReconciledFact>>(factsJson)
        } catch (e: Exception) {
            logger.warn("Failed to parse reconciled facts, falling back to append: ${e.message}")
            existingFacts.map { ReconciledFact(it, "unchanged") } +
                newFacts.map { ReconciledFact(it, "new") }
        }
    }
}

@Serializable
private data class ReconciledFact(
    val fact: String,
    val status: String,
)

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
