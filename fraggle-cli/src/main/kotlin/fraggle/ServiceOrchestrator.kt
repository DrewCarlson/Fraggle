package fraggle

import dev.zacsweers.metro.Inject
import fraggle.agent.tool.FraggleToolRegistry
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import fraggle.agent.*
import fraggle.chat.*
import fraggle.events.EventBus
import fraggle.db.*
import fraggle.discord.DiscordBridge
import fraggle.discord.DiscordBridgeInitializer
import fraggle.models.ApiConfig
import fraggle.models.ExecutorConfig
import fraggle.models.FraggleEvent
import fraggle.signal.MessageRouter
import fraggle.signal.SignalBridge
import fraggle.signal.SignalBridgeInitializer
import fraggle.scheduling.TaskScheduler
import fraggle.tools.web.PlaywrightFetcher
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
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
    private val toolRegistry: FraggleToolRegistry,
    private val bridgeManager: ChatBridgeManager,
    private val initializerRegistry: BridgeInitializerRegistry,
    private val messageRouter: MessageRouter?,
    private val inlineImageProcessor: InlineImageProcessor,
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
    private val chatCommandProcessor: ChatCommandProcessor,
    private val eventBus: EventBus,
    private val executorConfig: ExecutorConfig,
) {
    private val logger = LoggerFactory.getLogger(ServiceOrchestrator::class.java)
    private val conversations = ConcurrentHashMap<String, Conversation>()
    private val chatMutexes = ConcurrentHashMap<String, Mutex>()
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

        // Forward permission request events to the originating chat bridge
        scope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is FraggleEvent.ToolPermissionRequest -> {
                        if (executorConfig.bridgeApproval && event.chatId.isNotEmpty()) {
                            chatCommandProcessor.trackPermissionRequest(event.chatId, event.requestId)
                            val msg = "Tool **${event.toolName}** wants to execute.\n" +
                                "Args: `${event.argsJson}`\n\n" +
                                "Reply `/approve` or `/deny`"
                            try {
                                bridgeManager.send(event.chatId, OutgoingMessage.Text(msg))
                            } catch (e: Exception) {
                                logger.warn("Failed to send permission request to chat: ${e.message}")
                            }
                        }
                    }
                    is FraggleEvent.ToolPermissionGranted -> {
                        chatCommandProcessor.clearPermissionRequest(event.requestId)
                    }
                    is FraggleEvent.ToolPermissionTimeout -> {
                        chatCommandProcessor.clearPermissionRequest(event.requestId)
                    }
                    else -> {}
                }
            }
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
        val result = agent.process(conversation, message)

        // Update conversation history (using potentially compressed conversation)
        val responseText = result.response.contentOrError()
        val updatedMessages = result.conversation.messages +
            ConversationMessage(ConversationRole.USER, text) +
            // Don't persist LLM errors as assistant messages — they pollute the
            // context and can cause cascading failures on subsequent requests.
            if (result.response is AgentResponse.Success) {
                listOf(ConversationMessage(ConversationRole.ASSISTANT, responseText))
            } else {
                emptyList()
            }
        conversations[chatId] = result.conversation.copy(messages = updatedMessages)

        return responseText
    }

    /**
     * Get the agent for direct access.
     */
    fun getAgent(): FraggleAgent = agent

    /**
     * Get the tool registry.
     */
    fun getToolRegistry() = toolRegistry

    /**
     * Start the unified message processing loop for all bridges.
     */
    private fun startMessageLoop() {
        messageJob = scope.launch {
            bridgeManager.messages.collect { bridgedMessage ->
                launch {
                    val chatId = bridgedMessage.qualifiedChatId
                    val messageText = (bridgedMessage.message.content as? MessageContent.Text)?.text.orEmpty()
                    if (messageText.isNotEmpty() && chatCommandProcessor.isCommand(messageText)) {
                        val response = when (val result = chatCommandProcessor.handleCommand(chatId, messageText)) {
                            is CommandResult.Approved -> "Tool execution approved."
                            is CommandResult.Denied -> "Tool execution denied."
                            is CommandResult.NoPermissionPending -> "No pending permission request."
                            is CommandResult.Unknown -> "Unknown command: ${result.command}"
                        }
                        bridgeManager.send(chatId, OutgoingMessage.Text(response))
                        return@launch
                    }

                    val mutex = chatMutexes.getOrPut(chatId) { Mutex() }
                    mutex.withLock {
                        handleMessage(bridgedMessage)
                    }
                }
            }
        }
    }

    private suspend fun CoroutineScope.handleMessage(bridgedMessage: BridgedMessage) {
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
                return
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
            val messageText = (routedMessage.content as? MessageContent.Text)?.text.orEmpty()
            fraggleServices.emitEvent(FraggleEvent.MessageReceived(
                timestamp = Clock.System.now(),
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
            val (processResult, duration) = measureTimedValue {
                agent.process(conversation, routedMessage, platform)
            }
            val response = processResult.response

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
                timestamp = Clock.System.now(),
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

            // Update conversation (using potentially compressed conversation).
            // Don't persist LLM errors as assistant messages — they pollute the
            // context and can cause cascading failures on subsequent requests.
            val updatedMessages = processResult.conversation.messages +
                ConversationMessage(ConversationRole.USER, messageText) +
                if (response is AgentResponse.Success) {
                    listOf(ConversationMessage(ConversationRole.ASSISTANT, finalText))
                } else {
                    emptyList()
                }
            conversations[chatId] = processResult.conversation.copy(messages = updatedMessages)

            logger.info("Response sent to $chatId")
        } catch (e: Exception) {
            logger.error("Error processing message: ${e.message}", e)
            typingJob.cancel()

            // Emit error event
            fraggleServices.emitEvent(FraggleEvent.Error(
                timestamp = Clock.System.now(),
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

    private fun MessageContent.toContentType(): MessageContentType = when (this) {
        is MessageContent.Text -> MessageContentType.TEXT
        is MessageContent.Image -> MessageContentType.IMAGE
        is MessageContent.File -> MessageContentType.FILE
        is MessageContent.Audio -> MessageContentType.AUDIO
        is MessageContent.Sticker -> MessageContentType.STICKER
        is MessageContent.Reaction -> MessageContentType.REACTION
    }
}
