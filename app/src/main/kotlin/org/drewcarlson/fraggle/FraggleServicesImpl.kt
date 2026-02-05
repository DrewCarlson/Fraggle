package org.drewcarlson.fraggle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.drewcarlson.fraggle.agent.Conversation
import org.drewcarlson.fraggle.api.BridgeInitService
import org.drewcarlson.fraggle.api.ConfigService
import org.drewcarlson.fraggle.api.ConversationService
import org.drewcarlson.fraggle.api.FraggleServices
import org.drewcarlson.fraggle.api.SchedulerService
import org.drewcarlson.fraggle.chat.BridgeInitializer
import org.drewcarlson.fraggle.chat.BridgeInitializerRegistry
import org.drewcarlson.fraggle.chat.ChatBridgeManager
import org.drewcarlson.fraggle.chat.InitStepResult
import org.drewcarlson.fraggle.memory.MemoryStore
import org.drewcarlson.fraggle.models.*
import org.drewcarlson.fraggle.skill.SkillRegistry
import org.drewcarlson.fraggle.skills.scheduling.ScheduledTask
import org.drewcarlson.fraggle.skills.scheduling.TaskScheduler
import org.drewcarlson.fraggle.skills.scheduling.TaskStatus
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.readText
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Implementation of FraggleServices for the API backend.
 */
class FraggleServicesImpl(
    override val memory: MemoryStore,
    override val skills: SkillRegistry,
    override val bridges: ChatBridgeManager,
    private val taskScheduler: TaskScheduler,
    private val conversationMap: ConcurrentHashMap<String, Conversation>,
    private val fraggleConfig: FraggleConfig,
    private val configPath: Path,
    private val initializerRegistry: BridgeInitializerRegistry,
    private val scope: CoroutineScope,
    private val startTime: Instant = Clock.System.now(),
) : FraggleServices {

    private val logger = LoggerFactory.getLogger(FraggleServicesImpl::class.java)

    private val _events = MutableSharedFlow<FraggleEvent>(replay = 1, extraBufferCapacity = 100)
    override val events: SharedFlow<FraggleEvent> = _events.asSharedFlow()

    override val conversations: ConversationService = ConversationServiceImpl()

    override val scheduler: SchedulerService = SchedulerServiceImpl()

    override val config: ConfigService = ConfigServiceImpl()

    override val bridgeInit: BridgeInitService = BridgeInitServiceImpl()

    override suspend fun getStatus(): SystemStatus {
        val runtime = Runtime.getRuntime()
        val registeredBridges = bridges.registeredBridges()
        val uninitialized = registeredBridges.filter { name ->
            !bridgeInit.isInitialized(name)
        }
        return SystemStatus(
            uptime = Clock.System.now() - startTime,
            activeConversations = conversationMap.size,
            connectedBridges = registeredBridges.count { bridges.isConnected(it) },
            availableSkills = skills.all().size,
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
        _events.emit(event)
    }

    private inner class ConversationServiceImpl : ConversationService {
        override fun getAll(): List<Conversation> = conversationMap.values.toList()

        override fun get(id: String): Conversation? = conversationMap[id]

        override fun getByChat(chatId: String): Conversation? =
            conversationMap.values.find { it.chatId == chatId }

        override fun clear(id: String): Boolean {
            val conversation = conversationMap[id] ?: return false
            conversationMap[id] = conversation.copy(messages = emptyList())
            return true
        }
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

                        // Also emit bridge status changed event to refresh UI
                        emitEvent(FraggleEvent.BridgeStatusChanged(
                            timestamp = now,
                            bridgeName = session.bridgeName,
                            connected = false,
                            error = null,
                        ))

                        activeSessions.remove(sessionId)
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
}
