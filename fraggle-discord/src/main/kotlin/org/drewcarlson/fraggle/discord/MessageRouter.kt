package org.drewcarlson.fraggle.discord

import org.drewcarlson.fraggle.chat.IncomingMessage
import org.drewcarlson.fraggle.chat.MessageContent
import org.slf4j.LoggerFactory

/**
 * Routes and filters incoming Discord messages based on configuration.
 * Handles trigger detection, channel filtering, and message preprocessing.
 */
class MessageRouter(private val config: DiscordConfig) {
    private val logger = LoggerFactory.getLogger(MessageRouter::class.java)

    /**
     * Determines if a message should be processed by the agent.
     */
    fun shouldProcess(message: IncomingMessage): Boolean {
        val channelId = message.chatId
        val content = (message.content as? MessageContent.Text)?.text ?: return false

        // Check allowed channels
        if (config.allowedChannelIds.isNotEmpty() && channelId !in config.allowedChannelIds) {
            logger.debug("Message from non-allowed channel: $channelId")
            return false
        }

        // Check registered chats
        val registeredChat = config.registeredChats.find { it.id == channelId }
        if (registeredChat != null && !registeredChat.enabled) {
            logger.debug("Message from disabled registered chat: $channelId")
            return false
        }

        // Check if this is a DM (starts with "dm:" prefix from bridge)
        val isDm = channelId.startsWith("dm:")
        if (isDm && config.respondToDirectMessages) {
            return true
        }

        // Check trigger prefix
        val trigger = registeredChat?.triggerOverride ?: config.triggerPrefix
        if (trigger != null) {
            val hasTriger = content.startsWith(trigger, ignoreCase = true)
            if (!hasTriger) {
                logger.debug("Message doesn't have trigger '$trigger': ${content.take(50)}")
            }
            return hasTriger
        }

        return true
    }

    /**
     * Process a message to extract the actual content (stripping trigger if present).
     * Returns null if the message should not be processed.
     */
    fun process(message: IncomingMessage): IncomingMessage? {
        if (!shouldProcess(message)) {
            return null
        }

        val content = (message.content as? MessageContent.Text)?.text ?: return null
        val channelId = message.chatId

        // Get the applicable trigger
        val registeredChat = config.registeredChats.find { it.id == channelId }
        val trigger = registeredChat?.triggerOverride ?: config.triggerPrefix

        // Strip trigger if present
        val processedText = if (trigger != null && content.startsWith(trigger, ignoreCase = true)) {
            content.removePrefix(trigger).trim()
        } else {
            content
        }

        if (processedText.isBlank()) {
            return null
        }

        return message.copy(
            content = MessageContent.Text(processedText)
        )
    }
}
