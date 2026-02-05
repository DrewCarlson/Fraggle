package org.drewcarlson.fraggle

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.drewcarlson.fraggle.agent.*
import org.drewcarlson.fraggle.backend.createApiServer
import org.drewcarlson.fraggle.chat.BridgeInitializerRegistry
import org.drewcarlson.fraggle.chat.ChatBridgeManager
import org.drewcarlson.fraggle.chat.MessageContent
import org.drewcarlson.fraggle.chat.OutgoingMessage
import org.drewcarlson.fraggle.memory.FileMemoryStore
import org.drewcarlson.fraggle.memory.MemoryStore
import org.drewcarlson.fraggle.models.FraggleConfig
import org.drewcarlson.fraggle.models.FraggleEvent
import org.drewcarlson.fraggle.models.ProviderType
import org.drewcarlson.fraggle.models.SandboxType
import org.drewcarlson.fraggle.models.SignalBridgeConfig
import org.drewcarlson.fraggle.prompt.PromptConfig
import org.drewcarlson.fraggle.prompt.PromptManager
import org.drewcarlson.fraggle.provider.LLMProvider
import org.drewcarlson.fraggle.provider.LMStudioProvider
import org.drewcarlson.fraggle.sandbox.PermissiveSandbox
import org.drewcarlson.fraggle.sandbox.Sandbox
import org.drewcarlson.fraggle.signal.MessageRouter
import org.drewcarlson.fraggle.signal.SignalBridge
import org.drewcarlson.fraggle.signal.SignalBridgeInitializer
import org.drewcarlson.fraggle.signal.SignalConfig
import org.drewcarlson.fraggle.skill.SkillRegistry
import org.drewcarlson.fraggle.skills.DefaultSkills
import org.drewcarlson.fraggle.skills.scheduling.TaskScheduler
import org.drewcarlson.fraggle.skills.web.PlaywrightConfig
import org.drewcarlson.fraggle.skills.web.PlaywrightFetcher
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Orchestrates all Fraggle services and manages their lifecycle.
 */
class ServiceOrchestrator(
    private val config: FraggleConfig,
    private val configPath: Path,
) {
    private val logger = LoggerFactory.getLogger(ServiceOrchestrator::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Services
    private lateinit var provider: LLMProvider
    private lateinit var sandbox: Sandbox
    private lateinit var memory: MemoryStore
    private lateinit var promptManager: PromptManager
    private lateinit var skills: SkillRegistry
    private lateinit var agent: FraggleAgent
    private lateinit var taskScheduler: TaskScheduler

    // Chat bridge management
    private lateinit var bridgeManager: ChatBridgeManager
    private lateinit var initializerRegistry: BridgeInitializerRegistry
    private var messageRouter: MessageRouter? = null
    private var messageJob: Job? = null

    // Playwright integration (optional)
    private var playwrightFetcher: PlaywrightFetcher? = null

    // Conversation tracking
    private val conversations = ConcurrentHashMap<String, Conversation>()

    // API server (optional)
    private var apiServer: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private var fraggleServices: FraggleServicesImpl? = null
    private val startTime = Clock.System.now()

    /**
     * Initialize all services.
     */
    fun initialize() {
        logger.info("Initializing Fraggle services...")

        // Initialize provider
        provider = createProvider()
        logger.info("LLM Provider initialized: ${provider.name}")

        // Initialize sandbox
        sandbox = createSandbox()
        logger.info("Sandbox initialized: ${config.fraggle.sandbox.type}")

        // Initialize memory
        memory = createMemoryStore()
        logger.info("Memory store initialized")

        // Initialize prompt manager
        promptManager = createPromptManager()
        promptManager.initialize()
        logger.info("Prompt manager initialized")

        // Initialize chat bridge manager
        bridgeManager = ChatBridgeManager(scope)

        // Initialize bridge initializer registry
        initializerRegistry = BridgeInitializerRegistry()

        // Initialize task scheduler with message sending callback
        taskScheduler = TaskScheduler(scope) { task ->
            logger.info("Task triggered: ${task.name} - ${task.action}")

            // Send the task action as a message to the originating chat
            if (bridgeManager.hasConnectedBridge()) {
                try {
                    bridgeManager.send(task.chatId, OutgoingMessage.Text(task.action))
                    logger.info("Task message sent to ${task.chatId}: ${task.action}")
                } catch (e: Exception) {
                    logger.error("Failed to send task message: ${e.message}", e)
                }
            } else {
                logger.warn("Cannot send task message: No chat bridge connected")
            }
        }

        // Initialize Playwright if configured
        config.fraggle.web.playwright?.let { pwConfig ->
            playwrightFetcher = PlaywrightFetcher(
                PlaywrightConfig(
                    wsEndpoint = pwConfig.wsEndpoint,
                    navigationTimeout = pwConfig.navigationTimeout,
                    waitAfterLoad = pwConfig.waitAfterLoad,
                    viewportWidth = pwConfig.viewportWidth,
                    viewportHeight = pwConfig.viewportHeight,
                    userAgent = pwConfig.userAgent,
                )
            )
            logger.info("Playwright fetcher configured: ${pwConfig.wsEndpoint}")
        }

        // Initialize skills
        skills = DefaultSkills.createRegistry(sandbox, taskScheduler, playwrightFetcher)
        logger.info("Skill registry initialized with ${skills.all().size} skills")

        // Initialize agent
        agent = createAgent()
        logger.info("Agent initialized")

        // Initialize chat bridges based on configuration
        initializeBridges()

        // Initialize API server if enabled
        initializeApiServer()

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
        apiServer = null

        // Stop message processing
        messageJob?.cancelAndJoin()

        // Disconnect all bridges
        bridgeManager.disconnectAll()

        // Stop Playwright
        playwrightFetcher?.disconnect()

        // Stop scheduler
        taskScheduler.shutdown()

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
        val message = org.drewcarlson.fraggle.chat.IncomingMessage(
            id = "${chatId}-${System.currentTimeMillis()}",
            chatId = chatId,
            sender = org.drewcarlson.fraggle.chat.Sender(
                id = senderId,
                name = senderName,
            ),
            content = MessageContent.Text(text),
            timestamp = Clock.System.now(),
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
    fun getSkills(): SkillRegistry = skills

    private fun createProvider(): LLMProvider {
        val settings = config.fraggle.provider

        return when (settings.type) {
            ProviderType.LMSTUDIO -> LMStudioProvider(
                baseUrl = settings.url,
                defaultModel = settings.model.takeIf { it.isNotBlank() },
            )
            ProviderType.OPENAI -> {
                // Could add OpenAI support here
                throw ConfigurationException("OpenAI provider not yet implemented")
            }
            ProviderType.ANTHROPIC -> {
                // Could add Anthropic support here
                throw ConfigurationException("Anthropic provider not yet implemented")
            }
        }
    }

    private fun createSandbox(): Sandbox {
        val settings = config.fraggle.sandbox
        val workDir = resolvePath(settings.workDir)
        workDir.createDirectories()

        return when (settings.type) {
            SandboxType.PERMISSIVE -> PermissiveSandbox(workDir)
            SandboxType.DOCKER -> {
                logger.warn("Docker sandbox not yet implemented, falling back to permissive")
                PermissiveSandbox(workDir)
            }
            SandboxType.GVISOR -> {
                logger.warn("gVisor sandbox not yet implemented, falling back to permissive")
                PermissiveSandbox(workDir)
            }
        }
    }

    private fun createMemoryStore(): MemoryStore {
        val baseDir = resolvePath(config.fraggle.memory.baseDir)
        baseDir.createDirectories()
        return FileMemoryStore(baseDir)
    }

    private fun createAgent(): FraggleAgent {
        val settings = config.fraggle.agent

        val agentConfig = AgentConfig(
            model = config.fraggle.provider.model,
            temperature = settings.temperature,
            maxTokens = settings.maxTokens,
            maxIterations = settings.maxIterations,
            maxHistoryMessages = settings.maxHistoryMessages,
        )

        return FraggleAgent(
            provider = provider,
            skills = skills,
            memory = memory,
            sandbox = sandbox,
            config = agentConfig,
            promptManager = promptManager,
        )
    }

    private fun createPromptManager(): PromptManager {
        val settings = config.fraggle.prompts
        val promptsDir = resolvePath(settings.promptsDir)

        return PromptManager(
            PromptConfig(
                promptsDir = promptsDir,
                maxFileChars = settings.maxFileChars,
                autoCreateMissing = settings.autoCreateMissing,
            )
        )
    }

    /**
     * Initialize all chat bridges based on configuration.
     */
    private fun initializeBridges() {
        // Initialize Signal bridge if configured
        config.fraggle.bridges.signal?.let { signalConfig ->
            if (signalConfig.enabled) {
                initializeSignalBridge(signalConfig)
            }
        }

        // Future: Add other bridge initializations here
        // config.fraggle.bridges.discord?.let { ... }
        // config.fraggle.bridges.telegram?.let { ... }

        if (bridgeManager.registeredBridges().isEmpty()) {
            logger.info("No chat bridges configured")
        }
    }

    private fun initializeSignalBridge(settings: SignalBridgeConfig) {
        val signalConfig = SignalConfig(
            phoneNumber = settings.phone,
            configDir = resolvePath(settings.configDir).toString(),
            triggerPrefix = settings.trigger,
            signalCliPath = settings.signalCliPath,
            autoInstall = settings.autoInstall,
            signalCliVersion = settings.signalCliVersion,
            appsDir = FraggleEnvironment.dataDir.resolve("apps").toString(),
            respondToDirectMessages = settings.respondToDirectMessages,
            showTypingIndicator = settings.showTypingIndicator,
            registeredChats = config.fraggle.chats.registered.map { it.toRegisteredChat() },
        )

        val bridge = SignalBridge(signalConfig, scope)
        bridgeManager.register("signal", bridge)
        messageRouter = MessageRouter(signalConfig)

        // Register the bridge initializer for interactive setup
        initializerRegistry.register("signal", SignalBridgeInitializer(signalConfig))

        logger.info("Signal bridge initialized for ${settings.phone}")
    }

    /**
     * Initialize the API server if enabled in configuration.
     */
    private fun initializeApiServer() {
        val apiConfig = config.fraggle.api
        val dashboardConfig = config.fraggle.dashboard

        if (!apiConfig.enabled) {
            logger.info("API server disabled")
            return
        }

        // Create the services implementation
        fraggleServices = FraggleServicesImpl(
            memory = memory,
            skills = skills,
            bridges = bridgeManager,
            taskScheduler = taskScheduler,
            conversationMap = conversations,
            fraggleConfig = config,
            configPath = configPath,
            initializerRegistry = initializerRegistry,
            scope = scope,
            startTime = startTime,
        )

        // Create the server
        apiServer = createApiServer(fraggleServices!!, apiConfig, dashboardConfig)
        logger.info("API server initialized on ${apiConfig.host}:${apiConfig.port}")
    }

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
                        delay(3000) // Refresh every 3 seconds
                    }
                }

                try {
                    // Emit message received event
                    val messageText = (routedMessage.content as? MessageContent.Text)?.text ?: ""
                    fraggleServices?.emitEvent(FraggleEvent.MessageReceived(
                        timestamp = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                        chatId = chatId,
                        senderId = routedMessage.sender.id,
                        senderName = routedMessage.sender.name,
                        content = messageText,
                    ))

                    // Get or create conversation
                    val conversation = conversations.getOrPut(chatId) {
                        Conversation(id = chatId, chatId = chatId)
                    }

                    // Process message with platform context
                    logger.info("Processing message from ${routedMessage.sender.id} via ${platform.name}")
                    val response = agent.process(conversation, routedMessage, platform)

                    // Stop typing indicator job
                    typingJob.cancel()
                    bridgeManager.setTyping(chatId, false)

                    // Send response
                    val responseText = response.contentOrError()
                    bridgeManager.send(chatId, OutgoingMessage.Text(responseText))

                    // Emit response sent event
                    fraggleServices?.emitEvent(FraggleEvent.ResponseSent(
                        timestamp = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                        chatId = chatId,
                        content = responseText,
                    ))

                    // Send any attachments
                    for (attachment in response.collectAttachments()) {
                        when (attachment) {
                            is ResponseAttachment.Image -> {
                                logger.info("Sending image attachment (${attachment.data.size / 1024}KB)")
                                bridgeManager.send(chatId, OutgoingMessage.Image(
                                    data = attachment.data,
                                    mimeType = attachment.mimeType,
                                    caption = attachment.caption,
                                ))
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

                    // Update conversation
                    conversations[chatId] = conversation.copy(
                        messages = conversation.messages + listOf(
                            ConversationMessage(ConversationRole.USER, messageText),
                            ConversationMessage(ConversationRole.ASSISTANT, responseText),
                        )
                    )

                    logger.info("Response sent to $chatId")
                } catch (e: Exception) {
                    logger.error("Error processing message: ${e.message}", e)
                    typingJob.cancel()

                    // Emit error event
                    fraggleServices?.emitEvent(FraggleEvent.Error(
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

    private fun resolvePath(path: String): Path {
        return FraggleEnvironment.resolvePath(path)
    }
}
