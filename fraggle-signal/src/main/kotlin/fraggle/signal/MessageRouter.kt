package fraggle.signal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import fraggle.chat.IncomingMessage
import fraggle.chat.MessageContent
import org.slf4j.LoggerFactory

/**
 * Routes and filters incoming messages based on configuration.
 * Handles trigger detection, chat registration checks, and message preprocessing.
 */
class MessageRouter(
    private val config: SignalConfig,
) {
    private val logger = LoggerFactory.getLogger(MessageRouter::class.java)

    // Set of registered chat IDs for quick lookup
    private val registeredChatIds: Set<String> = config.registeredChats
        .filter { it.enabled }
        .map { it.id }
        .toSet()

    // Map of chat-specific trigger overrides
    private val chatTriggers: Map<String, String?> = config.registeredChats
        .filter { it.enabled && it.triggerOverride != null }
        .associate { it.id to it.triggerOverride }

    /**
     * Filter and preprocess incoming messages.
     * Returns only messages that should be processed by the agent.
     */
    fun route(messages: Flow<IncomingMessage>): Flow<RoutedMessage> {
        return messages
            .filter { shouldProcess(it) }
            .map { routeMessage(it) }
    }

    /**
     * Check if a message should be processed.
     */
    fun shouldProcess(message: IncomingMessage): Boolean {
        val chatId = message.chatId
        val isGroup = chatId.startsWith("group:")
        val isDirectMessage = !isGroup

        // Check registered chats if configured
        if (registeredChatIds.isNotEmpty() && chatId !in registeredChatIds) {
            logger.debug("Ignoring message from unregistered chat: $chatId")
            return false
        }

        // Get text content — allow through if message has image attachments
        val textContent = (message.content as? MessageContent.Text)?.text
        if (textContent == null && message.imageAttachments.isEmpty()) {
            logger.debug("Ignoring non-text message without attachments")
            return false
        }

        // Check trigger requirements
        val trigger = getTriggerForChat(chatId)
        val text = textContent ?: ""

        return when {
            // Direct messages with trigger
            isDirectMessage && trigger != null && text.startsWith(trigger) -> true

            // Direct messages without trigger (if respondToDirectMessages is enabled)
            isDirectMessage && trigger == null && config.respondToDirectMessages -> true

            // Direct messages with trigger requirement but no trigger
            isDirectMessage && trigger != null && !text.startsWith(trigger) -> {
                // Still process if respondToDirectMessages is true (trigger is optional for DMs)
                config.respondToDirectMessages
            }

            // Group messages require trigger
            isGroup && trigger != null && text.startsWith(trigger) -> true

            // Group messages without trigger requirement
            isGroup && trigger == null -> true

            // Group messages that don't match trigger
            isGroup && trigger != null && !text.startsWith(trigger) -> {
                logger.debug("Ignoring group message without trigger")
                false
            }

            else -> false
        }
    }

    /**
     * Route a message, extracting the actual content after trigger removal.
     */
    private fun routeMessage(message: IncomingMessage): RoutedMessage {
        val chatId = message.chatId
        val trigger = getTriggerForChat(chatId)

        val originalText = (message.content as? MessageContent.Text)?.text ?: ""

        // Remove trigger prefix if present
        val processedText = if (trigger != null && originalText.startsWith(trigger)) {
            originalText.removePrefix(trigger).trim()
        } else {
            originalText
        }

        // Create processed message with cleaned content
        val processedMessage = if (processedText != originalText) {
            message.copy(content = MessageContent.Text(processedText))
        } else {
            message
        }

        return RoutedMessage(
            original = message,
            processed = processedMessage,
            chatConfig = config.registeredChats.find { it.id == chatId },
            triggerUsed = trigger != null && originalText.startsWith(trigger),
        )
    }

    /**
     * Get the trigger prefix for a specific chat.
     */
    private fun getTriggerForChat(chatId: String): String? {
        // Check for chat-specific override first
        return chatTriggers[chatId] ?: config.triggerPrefix
    }

    /**
     * Process a message, stripping trigger prefix if present.
     * Returns the processed message or null if it shouldn't be processed.
     */
    fun process(message: IncomingMessage): IncomingMessage? {
        if (!shouldProcess(message)) {
            return null
        }
        return routeMessage(message).processed
    }

    /**
     * Check if a chat is registered.
     */
    fun isChatRegistered(chatId: String): Boolean {
        return registeredChatIds.isEmpty() || chatId in registeredChatIds
    }

    /**
     * Get configuration for a registered chat.
     */
    fun getChatConfig(chatId: String): RegisteredChat? {
        return config.registeredChats.find { it.id == chatId }
    }
}

/**
 * A routed message with preprocessing applied.
 */
data class RoutedMessage(
    /**
     * The original incoming message.
     */
    val original: IncomingMessage,

    /**
     * The processed message with trigger prefix removed.
     */
    val processed: IncomingMessage,

    /**
     * Chat configuration if this is a registered chat.
     */
    val chatConfig: RegisteredChat?,

    /**
     * Whether the trigger prefix was used in this message.
     */
    val triggerUsed: Boolean,
)
