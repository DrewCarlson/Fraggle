package org.drewcarlson.fraggle

import dev.zacsweers.metro.Inject
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.drewcarlson.fraggle.agent.*
import org.drewcarlson.fraggle.chat.BridgeInitializerRegistry
import org.drewcarlson.fraggle.chat.ChatBridgeManager
import org.drewcarlson.fraggle.chat.IncomingMessage
import org.drewcarlson.fraggle.chat.MessageContent
import org.drewcarlson.fraggle.chat.OutgoingMessage
import org.drewcarlson.fraggle.chat.Sender
import org.drewcarlson.fraggle.db.ChatHistoryStore
import org.drewcarlson.fraggle.db.FraggleDatabase
import org.drewcarlson.fraggle.db.MessageContentType
import org.drewcarlson.fraggle.db.MessageDirection
import org.drewcarlson.fraggle.db.MessageRecord
import org.drewcarlson.fraggle.discord.DiscordBridge
import org.drewcarlson.fraggle.discord.DiscordBridgeInitializer
import org.drewcarlson.fraggle.models.*
import org.drewcarlson.fraggle.signal.MessageRouter
import org.drewcarlson.fraggle.signal.SignalBridge
import org.drewcarlson.fraggle.signal.SignalBridgeInitializer
import org.drewcarlson.fraggle.skill.SkillRegistry
import org.drewcarlson.fraggle.skills.scheduling.TaskScheduler
import org.drewcarlson.fraggle.skills.web.PlaywrightFetcher
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.measureTimedValue

/**
 * Orchestrates all Fraggle services and manages their lifecycle.
 *
 * All services are constructor-injected. This class is responsible
 * for registering bridges, starting/stopping services, and the message loop.
 */
@Inject
class ServiceOrchestrator(
    private val scope: CoroutineScope,
    private val agent: FraggleAgent,
    private val skills: SkillRegistry,
    private val bridgeManager: ChatBridgeManager,
    private val initializerRegistry: BridgeInitializerRegistry,
    private val messageRouter: MessageRouter?,
    private val inlineImageProcessor: InlineImageProcessor,
    private val conversations: ConcurrentHashMap<String, Conversation>,
    private val fraggleServices: FraggleServicesImpl,
    private val taskScheduler: TaskScheduler,
    private val playwrightFetcher: PlaywrightFetcher?,
    private val fraggleDatabase: FraggleDatabase,
    private val chatHistoryStore: ChatHistoryStore,
    private val signalBridge: SignalBridge?,
    private val signalBridgeInitializer: SignalBridgeInitializer?,
    private val discordBridge: DiscordBridge?,
    private val discordBridgeInitializer: DiscordBridgeInitializer?,
    private val apiConfig: ApiConfig,
    private val apiServer: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>?,
) {
    private val logger = LoggerFactory.getLogger(ServiceOrchestrator::class.java)
    private var messageJob: Job? = null

    /**
     * Initialize all services: register bridges and initializers.
     */
    fun initialize() {
        logger.info("Initializing Fraggle services...")

        // Register bridges
        signalBridge?.let { bridge ->
            bridgeManager.register("signal", bridge)
            logger.info("Signal bridge registered")
        }
        signalBridgeInitializer?.let { init ->
            initializerRegistry.register("signal", init)
        }

        discordBridge?.let { bridge ->
            bridgeManager.register("discord", bridge)
            logger.info("Discord bridge registered")
        }
        discordBridgeInitializer?.let { init ->
            initializerRegistry.register("discord", init)
        }

        if (bridgeManager.registeredBridges().isEmpty()) {
            logger.info("No chat bridges configured")
        }

        if (apiServer != null) {
            logger.info("API server initialized on ${apiConfig.host}:${apiConfig.port}")
        } else {
            logger.info("API server disabled")
        }

        logger.info("All services initialized successfully")
    }

    /**
     * Start all services.
     */
    suspend fun start() {
        logger.info("Starting Fraggle services...")

        // Start API server first - always available even if bridges fail
        apiServer?.start(wait = false)

        // Try to connect all registered bridges, but don't fail on errors
        if (bridgeManager.registeredBridges().isNotEmpty()) {
            logger.info("Connecting chat bridges: ${bridgeManager.registeredBridges()}")
            try {
                bridgeManager.connectAll()
                startMessageLoop()
                logger.info("Chat bridges started")
            } catch (e: Exception) {
                logger.warn("Bridge connection failed: ${e.message}")
                logger.info("Bridges can be initialized via dashboard or 'fraggle init-bridge' command")
                // Start the message loop anyway - bridges may connect later
                startMessageLoop()
            }
        } else {
            logger.info("No chat bridges configured")
        }

        logger.info("Fraggle is running")
    }

    /**
     * Stop all services gracefully.
     */
    suspend fun stop() {
        logger.info("Stopping Fraggle services...")

        // Stop API server
        apiServer?.stop(1000, 5000)

        // Stop message processing
        messageJob?.cancelAndJoin()

        // Disconnect all bridges
        bridgeManager.disconnectAll()

        // Stop Playwright
        playwrightFetcher?.disconnect()

        // Stop scheduler
        taskScheduler.shutdown()

        // Close database
        fraggleDatabase.close()

        // Cancel scope
        scope.cancel()

        logger.info("Fraggle stopped")
    }

    /**
     * Process a message directly (for testing or alternative interfaces).
     */
    suspend fun processMessage(
        chatId: String,
        senderId: String,
        senderName: String?,
        text: String,
    ): String {
        // Get or create conversation
        val conversation = conversations.getOrPut(chatId) {
            Conversation(id = chatId, chatId = chatId)
        }

        // Create incoming message
        val now = Clock.System.now()
        val message = IncomingMessage(
            id = "${chatId}-${now.toEpochMilliseconds()}",
            chatId = chatId,
            sender = Sender(
                id = senderId,
                name = senderName,
            ),
            content = MessageContent.Text(text),
            timestamp = now,
        )

        // Process with agent
        val response = agent.process(conversation, message)

        // Update conversation history
        val responseText = response.contentOrError()
        conversations[chatId] = conversation.copy(
            messages = conversation.messages + listOf(
                ConversationMessage(ConversationRole.USER, text),
                ConversationMessage(ConversationRole.ASSISTANT, responseText),
            )
        )

        return responseText
    }

    /**
     * Get the agent for direct access.
     */
    fun getAgent(): FraggleAgent = agent

    /**
     * Get the skill registry.
     */
    fun getSkills() = skills

    /**
     * Start the unified message processing loop for all bridges.
     */
    private fun startMessageLoop() {
        messageJob = scope.launch {
            bridgeManager.messages.collectLatest { bridgedMessage ->
                val chatId = bridgedMessage.qualifiedChatId
                val message = bridgedMessage.message
                val platform = bridgedMessage.bridge.platform

                // Apply message routing (trigger filtering, etc.)
                val routedMessage = messageRouter?.let { router ->
                    // Convert back to original format for router
                    val (_, originalChatId) = bridgeManager.parseQualifiedChatId(chatId)
                    val originalMessage = message.copy(chatId = originalChatId)

                    // Check if message should be processed
                    val shouldProcess = router.shouldProcess(originalMessage)
                    if (!shouldProcess) {
                        logger.debug("Message filtered by router: $chatId")
                        return@collectLatest
                    }

                    // Get the processed message (with trigger stripped)
                    router.process(originalMessage)?.copy(chatId = chatId)
                } ?: message

                // Start a job that keeps the typing indicator active
                val typingJob = launch {
                    while (true) {
                        try {
                            bridgeManager.setTyping(chatId, true)
                        } catch (e: Exception) {
                            logger.debug("Failed to send typing indicator: ${e.message}")
                        }
                        delay(3.seconds)
                    }
                }

                try {
                    // Emit message received event
                    val messageText = (routedMessage.content as? MessageContent.Text)?.text ?: ""
                    fraggleServices.emitEvent(FraggleEvent.MessageReceived(
                        timestamp = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                        chatId = chatId,
                        senderId = routedMessage.sender.id,
                        senderName = routedMessage.sender.name,
                        content = messageText,
                    ))

                    // Record incoming message metadata
                    val chatRecord = chatHistoryStore.getOrCreateChat(
                        externalId = chatId,
                        platform = platform.name.lowercase(),
                        isGroup = false,
                    )
                    chatHistoryStore.recordMessage(MessageRecord(
                        chatId = chatRecord.id,
                        externalId = routedMessage.id,
                        senderId = routedMessage.sender.id,
                        senderName = routedMessage.sender.name,
                        senderIsBot = routedMessage.sender.isBot,
                        contentType = routedMessage.content.toContentType(),
                        direction = MessageDirection.INCOMING,
                        timestamp = routedMessage.timestamp,
                    ))

                    // Get or create conversation
                    val conversation = conversations.getOrPut(chatId) {
                        Conversation(id = chatId, chatId = chatId)
                    }

                    // Process message with platform context
                    logger.info("Processing message from ${routedMessage.sender.id} via ${platform.name}")
                    val (response, duration) = measureTimedValue {
                        agent.process(conversation, routedMessage, platform)
                    }

                    // Stop typing indicator job
                    typingJob.cancel()
                    bridgeManager.setTyping(chatId, false)

                    // Get response text and attachments
                    val responseText = response.contentOrError()
                    val toolAttachments = response.collectAttachments()

                    // Determine if platform supports multiple images (Discord = 10, Signal = 1)
                    // TODO: Derive this from bridge implementation
                    val maxImages = when (platform.name) {
                        "Discord" -> 10
                        else -> 1
                    }

                    // Process inline images (e.g., [[image:url]])
                    val (finalText, inlineImages) = if (platform.supportsAttachments) {
                        if (maxImages > 1) {
                            // Multi-image processing for Discord
                            val result = inlineImageProcessor.processAll(responseText, maxImages)
                            result.cleanedText to result.images
                        } else {
                            // Single-image processing for Signal
                            val result = inlineImageProcessor.process(responseText)
                            result.cleanedText to listOfNotNull(result.image)
                        }
                    } else {
                        // Strip inline image syntax if platform doesn't support attachments
                        inlineImageProcessor.stripInlineImages(responseText) to emptyList()
                    }

                    // Collect all images: inline images first, then tool-generated images
                    val toolImages = toolAttachments.filterIsInstance<ResponseAttachment.Image>()
                    val allImages = inlineImages.map { it.data to it.mimeType } +
                        toolImages.map { it.data to it.mimeType }

                    // Discord: Send multiple images in one message with text
                    // Signal: Send single image with text
                    if (allImages.isNotEmpty() && platform.supportsAttachments) {
                        val imagesToSend = allImages.take(maxImages)
                        if (allImages.size > maxImages) {
                            logger.warn("Truncating images from ${allImages.size} to $maxImages")
                        }

                        if (imagesToSend.size == 1) {
                            // Single image - use standard OutgoingMessage.Image
                            val (imageData, mimeType) = imagesToSend.first()
                            logger.info("Sending response with image (${imageData.size / 1024}KB)")
                            bridgeManager.send(chatId, OutgoingMessage.Image(
                                data = imageData,
                                mimeType = mimeType,
                                caption = finalText.takeIf { it.isNotBlank() },
                            ))
                        } else {
                            // Multiple images - for Discord, send first with caption, rest without
                            // TODO: Use discord bridge sendMultiple images method
                            logger.info("Sending response with ${imagesToSend.size} images")
                            imagesToSend.forEachIndexed { index, (imageData, mimeType) ->
                                bridgeManager.send(chatId, OutgoingMessage.Image(
                                    data = imageData,
                                    mimeType = mimeType,
                                    // Only first image gets the caption
                                    caption = if (index == 0) finalText.takeIf { it.isNotBlank() } else null,
                                ))
                            }
                        }
                    } else {
                        // Send text only
                        bridgeManager.send(chatId, OutgoingMessage.Text(finalText))
                    }

                    // Emit response sent event
                    fraggleServices.emitEvent(FraggleEvent.ResponseSent(
                        timestamp = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                        chatId = chatId,
                        content = finalText,
                    ))

                    // Send any remaining file attachments
                    for (attachment in toolAttachments) {
                        when (attachment) {
                            is ResponseAttachment.Image -> {
                                // Already handled above
                            }
                            is ResponseAttachment.File -> {
                                logger.info("Sending file attachment: ${attachment.filename}")
                                bridgeManager.send(chatId, OutgoingMessage.File(
                                    data = attachment.data,
                                    filename = attachment.filename,
                                    mimeType = attachment.mimeType,
                                ))
                            }
                        }
                    }

                    // Record outgoing message metadata
                    if (chatRecord != null) {
                        val outContentType = if (allImages.isNotEmpty() && platform.supportsAttachments) {
                            MessageContentType.IMAGE
                        } else {
                            MessageContentType.TEXT
                        }
                        chatHistoryStore.recordMessage(MessageRecord(
                            chatId = chatRecord.id,
                            senderId = "fraggle",
                            senderName = "Fraggle",
                            senderIsBot = true,
                            contentType = outContentType,
                            direction = MessageDirection.OUTGOING,
                            timestamp = Clock.System.now(),
                            processingDuration = duration,
                        ))
                    }

                    // Update conversation
                    conversations[chatId] = conversation.copy(
                        messages = conversation.messages + listOf(
                            ConversationMessage(ConversationRole.USER, messageText),
                            ConversationMessage(ConversationRole.ASSISTANT, finalText),
                        )
                    )

                    logger.info("Response sent to $chatId")
                } catch (e: Exception) {
                    logger.error("Error processing message: ${e.message}", e)
                    typingJob.cancel()

                    // Emit error event
                    fraggleServices.emitEvent(FraggleEvent.Error(
                        timestamp = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                        source = "message_processing",
                        message = e.message ?: "Unknown error",
                    ))

                    try {
                        bridgeManager.setTyping(chatId, false)
                        bridgeManager.send(
                            chatId,
                            OutgoingMessage.Text("Sorry, I encountered an error: ${e.message}")
                        )
                    } catch (sendError: Exception) {
                        logger.error("Failed to send error message: ${sendError.message}")
                    }
                }
            }
        }
    }

    private fun MessageContent.toContentType(): MessageContentType = when (this) {
        is MessageContent.Text -> MessageContentType.TEXT
        is MessageContent.Image -> MessageContentType.IMAGE
        is MessageContent.File -> MessageContentType.FILE
        is MessageContent.Audio -> MessageContentType.AUDIO
        is MessageContent.Sticker -> MessageContentType.STICKER
        is MessageContent.Reaction -> MessageContentType.REACTION
    }
}
