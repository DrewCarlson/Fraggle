package fraggle

import ai.koog.agents.core.tools.ToolRegistry
import io.ktor.client.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import fraggle.api.*
import fraggle.chat.BridgeInitializer
import fraggle.chat.BridgeInitializerRegistry
import fraggle.chat.ChatBridgeManager
import fraggle.chat.InitStepResult
import fraggle.db.ChatHistoryStore
import fraggle.discord.DiscordBridge
import fraggle.discord.DiscordOAuth
import fraggle.events.EventBus
import fraggle.memory.MemoryStore
import fraggle.models.*
import fraggle.signal.SignalBridge
import fraggle.tools.scheduling.ScheduledTask
import fraggle.tools.scheduling.TaskScheduler
import fraggle.tools.scheduling.TaskStatus
import fraggle.tracing.TraceStore
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.readText
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Implementation of FraggleServices for the API backend.
 */
class FraggleServicesImpl(
    override val memory: MemoryStore,
    override val toolRegistry: ToolRegistry,
    override val bridges: ChatBridgeManager,
    private val taskScheduler: TaskScheduler,
    private val fraggleConfig: FraggleConfig,
    private val configPath: Path,
    private val initializerRegistry: BridgeInitializerRegistry,
    private val scope: CoroutineScope,
    private val httpClient: HttpClient,
    private val chatHistoryStore: ChatHistoryStore,
    private val discordBridge: DiscordBridge? = null,
    private val eventBus: EventBus,
    private val traceStore: TraceStore?,
    private val startTime: Instant = Clock.System.now(),
) : FraggleServices {

    private val logger = LoggerFactory.getLogger(FraggleServicesImpl::class.java)

    override val events: SharedFlow<FraggleEvent> = eventBus.events

    override val chatHistory: ChatHistoryService = ChatHistoryServiceImpl()

    override val scheduler: SchedulerService = SchedulerServiceImpl()

    override val config: ConfigService = ConfigServiceImpl()

    override val bridgeInit: BridgeInitService = BridgeInitServiceImpl()

    override val tracing: TracingService = TracingServiceImpl()

    override val discordOAuth: DiscordOAuthService? = discordBridge?.let { bridge ->
        val config = fraggleConfig.fraggle.bridges.discord
        if (config != null && config.clientSecret != null && config.oauthRedirectUri != null) {
            DiscordOAuthServiceImpl(bridge, config)
        } else {
            null
        }
    }

    override suspend fun getStatus(): SystemStatus {
        val runtime = Runtime.getRuntime()
        val registeredBridges = bridges.registeredBridges()
        val uninitialized = registeredBridges.filter { name ->
            !bridgeInit.isInitialized(name)
        }
        return SystemStatus(
            uptime = Clock.System.now() - startTime,
            totalChats = chatHistoryStore.countChats(),
            connectedBridges = registeredBridges.count { bridges.isConnected(it) },
            availableTools = toolRegistry.tools.size,
            scheduledTasks = taskScheduler.listPendingTasks().size,
            memoryUsage = MemoryUsage(
                heapUsed = runtime.totalMemory() - runtime.freeMemory(),
                heapMax = runtime.maxMemory(),
            ),
            uninitializedBridges = uninitialized,
        )
    }

    /**
     * Emit an event to WebSocket clients.
     */
    suspend fun emitEvent(event: FraggleEvent) {
        eventBus.emit(event)
    }

    /**
     * Resolve a pending tool permission request from a WebSocket client.
     */
    override suspend fun resolveToolPermission(requestId: String, approved: Boolean) {
        eventBus.emit(
            FraggleEvent.ToolPermissionGranted(
                timestamp = Clock.System.now(),
                requestId = requestId,
                approved = approved,
            )
        )
    }

    private inner class ChatHistoryServiceImpl : ChatHistoryService {
        override fun listChats(limit: Int, offset: Long): List<ChatSummary> {
            return chatHistoryStore.listChats(limit, offset.toInt().toLong()).map { chat ->
                ChatSummary(
                    id = chat.id,
                    platform = chat.platform,
                    externalId = chat.externalId,
                    name = chat.name,
                    isGroup = chat.isGroup,
                    messageCount = chatHistoryStore.countMessages(chat.id),
                    createdAt = chat.createdAt,
                    lastActiveAt = chat.lastActiveAt,
                )
            }
        }

        override fun getChat(id: Long): ChatDetail? {
            val chat = chatHistoryStore.getChatById(id) ?: return null
            val stats = chatHistoryStore.getChatStats(chat.id)
            return ChatDetail(
                id = chat.id,
                platform = chat.platform,
                externalId = chat.externalId,
                name = chat.name,
                isGroup = chat.isGroup,
                createdAt = chat.createdAt,
                lastActiveAt = chat.lastActiveAt,
                stats = ChatStatsInfo(
                    totalMessages = stats.totalMessages,
                    incomingMessages = stats.incomingMessages,
                    outgoingMessages = stats.outgoingMessages,
                    firstMessageAt = stats.firstMessageAt,
                    lastMessageAt = stats.lastMessageAt,
                    avgProcessingDuration = stats.avgProcessingDuration,
                ),
            )
        }

        override fun getMessages(chatId: Long, limit: Int, offset: Long): List<ChatMessageRecord> {
            return chatHistoryStore.getMessages(chatId, limit, offset).map { msg ->
                ChatMessageRecord(
                    id = msg.id,
                    senderId = msg.senderId,
                    senderName = msg.senderName,
                    senderIsBot = msg.senderIsBot,
                    contentType = msg.contentType.name,
                    direction = msg.direction.name,
                    timestamp = msg.timestamp,
                    processingDuration = msg.processingDuration,
                )
            }
        }

        override fun countChats(): Long = chatHistoryStore.countChats()
    }

    private inner class SchedulerServiceImpl : SchedulerService {
        override fun getTasks(): List<ScheduledTaskInfo> =
            taskScheduler.listTasks().map { it.toInfo() }

        override fun getTask(id: String): ScheduledTaskInfo? =
            taskScheduler.getTask(id)?.toInfo()

        override fun cancelTask(id: String): Boolean =
            taskScheduler.cancel(id)

        private fun ScheduledTask.toInfo(): ScheduledTaskInfo {
            val isActive = status == TaskStatus.PENDING || status == TaskStatus.RUNNING
            return ScheduledTaskInfo(
                id = id,
                name = name,
                chatId = chatId,
                action = action,
                schedule = if (repeatInterval != null) "every $repeatInterval" else "once",
                nextRun = if (isActive) nextRunTime else null,
                enabled = isActive,
            )
        }
    }

    private inner class ConfigServiceImpl : ConfigService {
        override fun getConfig(): ConfigResponse {
            val yaml = configPath.readText()
            // Mask the API key for security
            val settings = fraggleConfig.fraggle.copy(
                provider = fraggleConfig.fraggle.provider.copy(apiKey = null)
            )

            return ConfigResponse(
                yaml = yaml,
                config = settings,
            )
        }
    }

    /**
     * Session data for an active bridge initialization.
     */
    private data class InitSession(
        val bridgeName: String,
        val initializer: BridgeInitializer,
        var job: Job? = null,
    )

    private inner class BridgeInitServiceImpl : BridgeInitService {
        private val activeSessions = ConcurrentHashMap<String, InitSession>()

        override suspend fun startInit(bridgeName: String): String? {
            val initializer = initializerRegistry.get(bridgeName) ?: run {
                logger.warn("No initializer found for bridge: $bridgeName")
                return null
            }

            // Cancel any existing session for this bridge
            activeSessions.entries
                .filter { it.value.bridgeName == bridgeName }
                .forEach { (sessionId, session) ->
                    session.job?.cancel()
                    activeSessions.remove(sessionId)
                }

            val sessionId = UUID.randomUUID().toString()
            val session = InitSession(bridgeName, initializer)
            activeSessions[sessionId] = session

            logger.info("Starting bridge initialization for $bridgeName (session: $sessionId)")

            // Reset the initializer state and start
            initializer.reset()

            // Run the first step
            session.job = scope.launch {
                processInitStep(sessionId, session, null)
            }

            return sessionId
        }

        override suspend fun submitInput(sessionId: String, input: String) {
            val session = activeSessions[sessionId] ?: run {
                logger.warn("No active session found: $sessionId (active sessions: ${activeSessions.keys})")
                emitEvent(FraggleEvent.BridgeInitError(
                    timestamp = Clock.System.now(),
                    bridgeName = "unknown",
                    sessionId = sessionId,
                    message = "Session not found or expired",
                    recoverable = false,
                ))
                return
            }

            logger.info("Received input for session $sessionId (bridge: ${session.bridgeName}, input length: ${input.length})")

            // Cancel any existing job and start new one with input
            session.job?.cancel()
            session.job = scope.launch {
                processInitStep(sessionId, session, input)
            }
        }

        override fun cancelInit(sessionId: String) {
            val session = activeSessions.remove(sessionId) ?: return
            session.job?.cancel()
            session.initializer.reset()
            logger.info("Cancelled bridge initialization session: $sessionId")
        }

        override suspend fun isInitialized(bridgeName: String): Boolean {
            // First, check if the bridge is connected and can check via RPC
            // This avoids the config directory lock issue when signal-cli daemon is running
            val bridge = bridges.getBridge(bridgeName)
            if (bridge != null && bridge.isConnected()) {
                when (bridge) {
                    is SignalBridge -> {
                        val rpcResult = bridge.checkAccountInitialized()
                        if (rpcResult != null) {
                            return rpcResult
                        }
                    }
                }
            }

            // Fall back to initializer check (works when daemon is not running)
            return initializerRegistry.get(bridgeName)?.isInitialized() ?: false
        }

        override fun getInitializableBridges(): Set<String> {
            return initializerRegistry.names()
        }

        private suspend fun processInitStep(sessionId: String, session: InitSession, userInput: String?) {
            logger.info("Processing init step for ${session.bridgeName} (session: $sessionId, hasInput: ${userInput != null})")
            try {
                val result = session.initializer.initialize(userInput)
                logger.info("Init step result for ${session.bridgeName}: ${result::class.simpleName}")
                val now = Clock.System.now()

                when (result) {
                    is InitStepResult.Success -> {
                        logger.info("Init success for ${session.bridgeName}: ${result.message}")
                        result.message?.let { message ->
                            emitEvent(FraggleEvent.BridgeInitProgress(
                                timestamp = now,
                                bridgeName = session.bridgeName,
                                sessionId = sessionId,
                                message = message,
                            ))
                        }
                        // Continue to next step automatically
                        processInitStep(sessionId, session, null)
                    }

                    is InitStepResult.PromptRequired -> {
                        logger.info("Emitting BridgeInitPrompt for ${session.bridgeName}: ${result.prompt}")
                        emitEvent(FraggleEvent.BridgeInitPrompt(
                            timestamp = now,
                            bridgeName = session.bridgeName,
                            sessionId = sessionId,
                            prompt = result.prompt,
                            helpText = result.helpText,
                            sensitive = result.sensitive,
                        ))
                        logger.info("BridgeInitPrompt emitted successfully")
                    }

                    is InitStepResult.Error -> {
                        logger.warn("Init error for ${session.bridgeName}: ${result.message} (recoverable: ${result.recoverable})")
                        emitEvent(FraggleEvent.BridgeInitError(
                            timestamp = now,
                            bridgeName = session.bridgeName,
                            sessionId = sessionId,
                            message = result.message,
                            recoverable = result.recoverable,
                        ))

                        if (!result.recoverable) {
                            activeSessions.remove(sessionId)
                            session.initializer.reset()
                        }
                    }

                    is InitStepResult.Complete -> {
                        logger.info("Init complete for ${session.bridgeName}: ${result.message}")
                        emitEvent(FraggleEvent.BridgeInitComplete(
                            timestamp = now,
                            bridgeName = session.bridgeName,
                            sessionId = sessionId,
                            message = result.message,
                        ))

                        activeSessions.remove(sessionId)

                        // Auto-connect the bridge after successful initialization
                        val connected = try {
                            bridges.connect(session.bridgeName)
                            logger.info("Bridge auto-connected after initialization: ${session.bridgeName}")
                            true
                        } catch (e: Exception) {
                            logger.error("Failed to auto-connect bridge ${session.bridgeName}: ${e.message}", e)
                            false
                        }

                        emitEvent(FraggleEvent.BridgeStatusChanged(
                            timestamp = Clock.System.now(),
                            bridgeName = session.bridgeName,
                            connected = connected,
                            error = if (!connected) "Auto-connect failed" else null,
                        ))

                        logger.info("Bridge initialization complete for ${session.bridgeName}")
                    }
                }
            } catch (e: CancellationException) {
                logger.info("Init step cancelled for ${session.bridgeName}")
                throw e // Re-throw to properly cancel the coroutine
            } catch (e: Exception) {
                logger.error("Error during bridge initialization for ${session.bridgeName}: ${e.message}", e)
                emitEvent(FraggleEvent.BridgeInitError(
                    timestamp = Clock.System.now(),
                    bridgeName = session.bridgeName,
                    sessionId = sessionId,
                    message = e.message ?: "Unknown error",
                    recoverable = false,
                ))
                activeSessions.remove(sessionId)
                session.initializer.reset()
            }
        }
    }

    private inner class TracingServiceImpl : TracingService {
        override fun listSessions(limit: Int, offset: Int) =
            traceStore?.listSessions(limit, offset) ?: emptyList()

        override fun getSession(id: String) =
            traceStore?.getSessionDetail(id)
    }

    private inner class DiscordOAuthServiceImpl(
        private val bridge: DiscordBridge,
        private val discordConfig: DiscordBridgeConfig,
    ) : DiscordOAuthService {
        private var oauth: DiscordOAuth? = null

        private fun getOrCreateOAuth(): DiscordOAuth? {
            if (oauth != null) return oauth

            val clientId = discordConfig.clientId
                ?: discordConfig.token.split(".").firstOrNull()
                ?: return null
            val clientSecret = discordConfig.clientSecret ?: return null
            val redirectUri = discordConfig.oauthRedirectUri ?: return null

            oauth = DiscordOAuth(clientId, clientSecret, redirectUri, httpClient)
            return oauth
        }

        override fun isConfigured(): Boolean {
            return !discordConfig.clientSecret.isNullOrBlank() &&
                    !discordConfig.oauthRedirectUri.isNullOrBlank()
        }

        override fun getAuthorizationUrl(state: String?): String? {
            return getOrCreateOAuth()?.getAuthorizationUrl(state)
        }

        override suspend fun handleCallback(code: String, state: String?): OAuthCallbackResult {
            val oauthClient = getOrCreateOAuth()
                ?: return OAuthCallbackResult.Error("OAuth not configured")

            // Exchange code for token
            val tokenResponse = oauthClient.exchangeCode(code)
                ?: return OAuthCallbackResult.Error("Failed to exchange authorization code")

            // Get user info
            val user = oauthClient.getCurrentUser(tokenResponse.accessToken)
                ?: return OAuthCallbackResult.Error("Failed to get user information")

            // Send welcome DM using the bot
            if (!bridge.isConnected()) {
                logger.warn("Discord bridge not connected, cannot send welcome DM")
                return OAuthCallbackResult.Error("Discord bot not connected")
            }

            val dmSent = bridge.sendWelcomeDm(user.id)
            if (!dmSent) {
                logger.warn("Failed to send welcome DM to user ${user.id}")
                // Still return success - the user authorized, we just couldn't DM
            }

            logger.info("Discord OAuth callback completed for user ${user.username} (${user.id})")
            return OAuthCallbackResult.Success(user.id, user.username)
        }
    }
}
