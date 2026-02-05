package org.drewcarlson.fraggle.discord

import dev.kord.common.entity.ChannelType
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.entity.channel.DmChannel
import dev.kord.core.entity.channel.GuildMessageChannel
import dev.kord.core.entity.channel.MessageChannel
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.core.on
import dev.kord.gateway.Intent
import dev.kord.gateway.PrivilegedIntent
import io.ktor.client.request.forms.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import org.drewcarlson.fraggle.chat.*
import org.slf4j.LoggerFactory
import kotlin.time.Clock

/**
 * ChatBridge implementation for Discord using Kord.
 */
class DiscordBridge(
    private val config: DiscordConfig,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) : ChatBridge {
    private val logger = LoggerFactory.getLogger(DiscordBridge::class.java)

    private var kord: Kord? = null
    private val messageFlow = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 100)
    private var connected = false

    override val platform = ChatPlatform(
        name = "Discord",
        supportsMarkdown = true,
        supportsInlineImages = false, // Images should be sent as attachments
        supportsAttachments = true,
        supportsReactions = true,
        maxMessageLength = 2000,
        persistentActivation = true, // Always show setup to get OAuth link
        notes = """Discord is a chat platform with rich markdown support.

SUPPORTED FORMATTING (use these in your messages):
- **bold text** → displays as bold
- *italic text* or _italic text_ → displays as italic
- ~~strikethrough~~ → displays as strikethrough
- ||spoiler text|| → displays as a spoiler (hidden until clicked)
- `inline code` → displays in monospace font
- ```code blocks``` → displays as a code block
- > quote → displays as a block quote
- # Heading → large heading (Discord-specific)
- ## Subheading → medium heading
- ### Small heading → small heading
- - bullet list items work
- 1. numbered lists work

SENDING IMAGES:
- To include an image, use: [[image:URL]] (e.g., [[image:https://example.com/photo.jpg]])
- Multiple images can be sent per message (up to ${config.maxImagesPerMessage})
- For screenshots, use the screenshot_page tool

IMPORTANT LIMITATIONS:
- Maximum message length is 2000 characters
- Do NOT include raw image URLs expecting them to display inline - use [[image:URL]] syntax
- Links are automatically embedded when possible"""
    )

    override suspend fun connect() {
        logger.info("Connecting to Discord...")

        kord = Kord(config.token)

        // Set up message listener
        kord!!.on<MessageCreateEvent> {
            handleMessageCreate(this)
        }

        // Start the bot in a separate coroutine
        scope.launch {
            @OptIn(PrivilegedIntent::class)
            kord!!.login {
                intents += Intent.DirectMessages
                intents += Intent.MessageContent
            }
        }

        connected = true
        logger.info("Connected to Discord")
    }

    override suspend fun disconnect() {
        logger.info("Disconnecting from Discord...")
        connected = false
        kord?.logout()
        kord?.shutdown()
        kord = null
        logger.info("Disconnected from Discord")
    }

    override fun messages(): Flow<IncomingMessage> = messageFlow.asSharedFlow()

    override suspend fun send(chatId: String, message: OutgoingMessage) {
        val kordInstance = kord ?: throw IllegalStateException("Not connected to Discord")
        val channelId = Snowflake(chatId)
        val channel = kordInstance.getChannel(channelId) as? MessageChannel
            ?: throw IllegalArgumentException("Channel not found or not a message channel: $chatId")

        when (message) {
            is OutgoingMessage.Text -> {
                // Split long messages if needed
                val text = message.text
                if (text.length <= 2000) {
                    channel.createMessage {
                        content = text
                    }
                } else {
                    // Split into chunks of 2000 characters
                    text.chunked(2000).forEach { chunk ->
                        channel.createMessage {
                            content = chunk
                        }
                    }
                }
            }

            is OutgoingMessage.Image -> {
                // Check file size
                if (message.data.size > config.maxFileSizeBytes) {
                    logger.warn("Image too large (${message.data.size} bytes), max is ${config.maxFileSizeBytes}")
                    val caption = message.caption
                    val errorText = if (!caption.isNullOrBlank()) {
                        "$caption\n\n_(Image attachment was too large to send)_"
                    } else {
                        "_(Image attachment was too large to send)_"
                    }
                    channel.createMessage {
                        content = errorText
                    }
                    return
                }

                val extension = when {
                    message.mimeType.contains("png") -> "png"
                    message.mimeType.contains("gif") -> "gif"
                    message.mimeType.contains("webp") -> "webp"
                    else -> "jpg"
                }

                channel.createMessage {
                    content = message.caption ?: ""
                    addFile("image.$extension", ChannelProvider { ByteReadChannel(message.data) })
                }
            }

            is OutgoingMessage.File -> {
                // Check file size
                if (message.data.size > config.maxFileSizeBytes) {
                    logger.warn("File too large (${message.data.size} bytes), max is ${config.maxFileSizeBytes}")
                    channel.createMessage {
                        content = "_(File attachment '${message.filename}' was too large to send)_"
                    }
                    return
                }

                channel.createMessage {
                    addFile(message.filename, ChannelProvider { ByteReadChannel(message.data) })
                }
            }

            is OutgoingMessage.Reaction -> {
                // Reactions need the message ID, not just channel
                logger.warn("Reaction sending requires message tracking - not fully implemented for Discord")
            }
        }
    }

    /**
     * Send multiple images in a single message.
     * Discord allows up to 10 attachments per message.
     */
    suspend fun sendMultipleImages(
        chatId: String,
        images: List<Pair<ByteArray, String>>, // data to mimeType
        caption: String? = null,
    ) {
        val kordInstance = kord ?: throw IllegalStateException("Not connected to Discord")
        val channelId = Snowflake(chatId)
        val channel = kordInstance.getChannel(channelId) as? MessageChannel
            ?: throw IllegalArgumentException("Channel not found: $chatId")

        // Limit to configured max images
        val imagesToSend = images.take(config.maxImagesPerMessage)
        if (images.size > config.maxImagesPerMessage) {
            logger.warn("Truncating images from ${images.size} to ${config.maxImagesPerMessage}")
        }

        // Filter out images that are too large
        val validImages = imagesToSend.filter { (data, _) ->
            if (data.size > config.maxFileSizeBytes) {
                logger.warn("Skipping image that exceeds size limit (${data.size} bytes)")
                false
            } else {
                true
            }
        }

        if (validImages.isEmpty()) {
            logger.warn("No valid images to send after size filtering")
            if (caption != null) {
                channel.createMessage { content = caption }
            }
            return
        }

        channel.createMessage {
            content = caption ?: ""
            validImages.forEachIndexed { index, (data, mimeType) ->
                val extension = when {
                    mimeType.contains("png") -> "png"
                    mimeType.contains("gif") -> "gif"
                    mimeType.contains("webp") -> "webp"
                    else -> "jpg"
                }
                addFile("image_$index.$extension", ChannelProvider { ByteReadChannel(data) })
            }
        }

        logger.info("Sent ${validImages.size} images to channel $chatId")
    }

    override suspend fun setTyping(chatId: String, typing: Boolean) {
        if (!config.showTypingIndicator || !typing) return

        val kordInstance = kord ?: return
        try {
            val channelId = Snowflake(chatId)
            val channel = kordInstance.getChannel(channelId) as? MessageChannel ?: return
            channel.type()
        } catch (e: Exception) {
            logger.debug("Failed to send typing indicator: ${e.message}")
        }
    }

    override suspend fun getChatInfo(chatId: String): ChatInfo? {
        val kordInstance = kord ?: return null
        val channelId = Snowflake(chatId)
        val channel = kordInstance.getChannel(channelId) ?: return null

        return when (channel) {
            is DmChannel -> {
                val recipientName = channel.recipients.firstOrNull()?.username
                ChatInfo(
                    id = chatId,
                    name = recipientName,
                    isGroup = false,
                )
            }
            is GuildMessageChannel -> ChatInfo(
                id = chatId,
                name = channel.name,
                isGroup = true,
                memberCount = channel.guild.asGuild().memberCount,
            )
            else -> ChatInfo(
                id = chatId,
                name = null,
                isGroup = false,
            )
        }
    }

    override fun isConnected(): Boolean = connected && kord != null

    /**
     * Get the Kord instance for OAuth operations.
     * Returns null if not connected.
     */
    fun getKord(): Kord? = kord

    /**
     * Send a welcome DM to a user after OAuth authorization.
     * Creates a DM channel and sends a welcome message.
     *
     * @param userId The user's Discord ID
     * @return true if the DM was sent successfully
     */
    suspend fun sendWelcomeDm(userId: String): Boolean {
        val kordInstance = kord ?: return false
        return try {
            val userSnowflake = Snowflake(userId)
            val dmChannel = kordInstance.rest.user.createDM(
                dev.kord.rest.json.request.DMCreateRequest(userSnowflake)
            )

            kordInstance.rest.channel.createMessage(dmChannel.id) {
                content = buildString {
                    appendLine("**Welcome to Fraggle!**")
                    appendLine()
                    appendLine("I'm your personal AI assistant. You can chat with me right here in DMs!")
                    appendLine()
                    appendLine("Just send me a message to get started.")
                }
            }

            logger.info("Sent welcome DM to user $userId")
            true
        } catch (e: Exception) {
            logger.error("Failed to send welcome DM to user $userId: ${e.message}", e)
            false
        }
    }

    private suspend fun handleMessageCreate(event: MessageCreateEvent) {
        val message = event.message

        // Ignore bot messages (including our own)
        if (message.author?.isBot == true) return

        // Get channel info
        val channel = message.channel.asChannel()
        val channelId = channel.id.toString()
        val isDm = channel.type == ChannelType.DM

        // Check if we should process this message
        if (!shouldProcessMessage(channelId, isDm, message.content)) {
            return
        }

        // Extract message text (strip trigger if present)
        val messageText = extractMessageText(message.content, channelId)
        if (messageText.isBlank()) return

        // Get sender info
        val author = message.author ?: return
        val senderName = when (channel) {
            is GuildMessageChannel -> message.getAuthorAsMember()?.nickname ?: author.username
            else -> author.username
        }

        // Create incoming message
        val incomingMessage = IncomingMessage(
            id = message.id.toString(),
            chatId = channelId,
            sender = Sender(
                id = author.id.toString(),
                name = senderName,
                isBot = author.isBot,
            ),
            content = MessageContent.Text(messageText),
            timestamp = Clock.System.now(),
            replyTo = message.referencedMessage?.id?.toString(),
            mentions = message.mentionedUserIds.map { it.toString() },
        )

        messageFlow.emit(incomingMessage)
    }

    private fun shouldProcessMessage(channelId: String, isDm: Boolean, content: String): Boolean {
        // Check allowed channels
        if (config.allowedChannelIds.isNotEmpty() && channelId !in config.allowedChannelIds) {
            return false
        }

        // Check registered chats
        val registeredChat = config.registeredChats.find { it.id == channelId }
        if (registeredChat != null && !registeredChat.enabled) {
            return false
        }

        // DMs don't require trigger if configured
        if (isDm && config.respondToDirectMessages) {
            return true
        }

        // Check trigger prefix
        val trigger = registeredChat?.triggerOverride ?: config.triggerPrefix
        if (trigger != null) {
            return content.startsWith(trigger, ignoreCase = true)
        }

        return true
    }

    private fun extractMessageText(content: String, channelId: String): String {
        // Get the applicable trigger
        val registeredChat = config.registeredChats.find { it.id == channelId }
        val trigger = registeredChat?.triggerOverride ?: config.triggerPrefix

        return if (trigger != null && content.startsWith(trigger, ignoreCase = true)) {
            content.removePrefix(trigger).trim()
        } else {
            content
        }
    }
}
