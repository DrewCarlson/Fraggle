package fraggle.di

import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import fraggle.FraggleEnvironment
import fraggle.agent.FraggleAgent
import fraggle.agent.skill.SkillCommandExpander
import fraggle.agent.skill.SkillRegistryLoader
import fraggle.agent.skill.SkillSecretsStore
import fraggle.agent.loop.LlmBridge
import fraggle.agent.loop.ProviderLlmBridge
import fraggle.agent.loop.ThinkingController
import fraggle.agent.loop.ToolCallExecutor
import fraggle.agent.tool.FraggleToolRegistry
import fraggle.agent.tool.SupervisedToolCallExecutor
import fraggle.provider.LMStudioProvider
import fraggle.agent.tool.AgentToolDef
import fraggle.chat.BridgeInitializerRegistry
import fraggle.chat.ChatBridgeManager
import fraggle.chat.ChatCommandProcessor
import fraggle.db.ChatHistoryStore
import fraggle.db.ExposedChatHistoryStore
import fraggle.db.ExposedScheduledTaskStore
import fraggle.db.FraggleDatabase
import fraggle.db.ScheduledTaskStore
import fraggle.events.EventBus
import fraggle.executor.RemoteToolClient
import fraggle.executor.supervision.ToolSupervisor
import fraggle.memory.FileMemoryStore
import fraggle.memory.MemoryStore
import fraggle.models.*
import fraggle.prompt.PromptConfig
import fraggle.prompt.PromptManager
import fraggle.scheduling.CancelTaskTool
import fraggle.scheduling.GetTaskTool
import fraggle.scheduling.ListTasksTool
import fraggle.scheduling.ScheduleTaskTool
import fraggle.scheduling.ScheduledTask
import fraggle.scheduling.SkipReplyTool
import fraggle.scheduling.TaskScheduler
import fraggle.tracing.TraceStore
import org.slf4j.LoggerFactory
import kotlin.io.path.createDirectories
import fraggle.agent.AgentConfig as RuntimeAgentConfig
import fraggle.models.AgentConfig as ModelsAgentConfig

private val assistantModuleLogger = LoggerFactory.getLogger("fraggle.di.AssistantModule")

/**
 * Provides assistant-specific services (memory, prompts, chat bridges, database,
 * and the FraggleAgent orchestrator itself). Generic runtime bindings (ToolExecutor,
 * ToolSupervisor, LMStudioProvider, TraceStore) live in [AgentCoreModule].
 */
@ContributesTo(AppScope::class)
interface AssistantModule {
    @Provides
    @SingleIn(AppScope::class)
    fun provideMemoryStore(config: MemoryConfig): MemoryStore {
        val baseDir = FraggleEnvironment.resolvePath(config.baseDir)
        baseDir.createDirectories()
        return FileMemoryStore(baseDir)
    }

    @Provides
    @SingleIn(AppScope::class)
    fun providePromptManager(config: PromptsConfig): PromptManager {
        val promptsDir = FraggleEnvironment.resolvePath(config.promptsDir)
        val manager = PromptManager(
            PromptConfig(
                promptsDir = promptsDir,
                maxFileChars = config.maxFileChars,
                autoCreateMissing = config.autoCreateMissing,
            )
        )
        manager.initialize()
        return manager
    }

    /**
     * Session-scoped handle for the `/think` slash command. Mutated by the
     * chat-command processor, read by the LLM bridge. In-memory only —
     * nothing is persisted and it resets when the process restarts.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideThinkingController(): ThinkingController = ThinkingController()

    @Provides
    @SingleIn(AppScope::class)
    fun provideLlmBridge(
        provider: LMStudioProvider,
        runtimeConfig: RuntimeAgentConfig,
        thinkingController: ThinkingController,
    ): LlmBridge = ProviderLlmBridge(
        provider = provider,
        model = runtimeConfig.model,
        temperature = runtimeConfig.temperature,
        thinkingController = thinkingController,
    )

    @Provides
    @SingleIn(AppScope::class)
    fun provideToolCallExecutor(
        registry: FraggleToolRegistry,
        supervisor: ToolSupervisor,
        remoteClient: RemoteToolClient?,
    ): ToolCallExecutor = SupervisedToolCallExecutor(
        registry = registry,
        supervisor = supervisor,
        remoteClient = remoteClient,
    )

    @Provides
    @SingleIn(AppScope::class)
    fun provideAgentRuntimeConfig(
        providerConfig: ProviderConfig,
        agentConfig: ModelsAgentConfig,
    ): RuntimeAgentConfig {
        return RuntimeAgentConfig(
            model = providerConfig.model,
            temperature = agentConfig.temperature,
            topP = agentConfig.topP,
            topK = agentConfig.topK,
            minP = agentConfig.minP,
            repeatPenalty = agentConfig.repeatPenalty,
            maxTokens = agentConfig.maxTokens,
            maxIterations = agentConfig.maxIterations,
            maxHistoryMessages = agentConfig.maxHistoryMessages,
            contextLength = agentConfig.contextLength,
            vision = agentConfig.vision,
        )
    }

    @Provides
    @SingleIn(AppScope::class)
    fun provideChatBridgeManager(scope: CoroutineScope): ChatBridgeManager =
        ChatBridgeManager(scope)

    @Provides
    @SingleIn(AppScope::class)
    fun provideTaskScheduler(
        scope: CoroutineScope,
        bridgeManager: ChatBridgeManager,
        eventBus: EventBus,
        scheduledTaskStore: ScheduledTaskStore,
    ): TaskScheduler {
        val scheduler = TaskScheduler(
            scope = scope,
            store = scheduledTaskStore,
            onTaskTriggered = { task: ScheduledTask ->
                assistantModuleLogger.info("Task triggered: ${task.name} - ${task.action}")
                eventBus.emit(
                    FraggleEvent.TaskTriggered(
                        timestamp = kotlin.time.Clock.System.now(),
                        taskId = task.id,
                        taskName = task.name,
                        chatId = task.chatId,
                    )
                )
                if (bridgeManager.hasConnectedBridge()) {
                    try {
                        val framedAction = buildString {
                            appendLine("[Scheduled task: ${task.name}]")
                            appendLine(task.action)
                            appendLine()
                            append(
                                "If the task or its result suggest there is no new information " +
                                    "for the user to see, call skip_reply to end silently.",
                            )
                        }
                        bridgeManager.injectMessage(
                            qualifiedChatId = task.chatId,
                            text = framedAction,
                            senderName = "Scheduled Task: ${task.name}",
                            isScheduled = true,
                        )
                        assistantModuleLogger.info("Task action injected for ${task.chatId}: ${task.action}")
                    } catch (e: Exception) {
                        assistantModuleLogger.error("Failed to inject task action: ${e.message}", e)
                    }
                } else {
                    assistantModuleLogger.warn("Cannot inject task action: No chat bridge connected")
                }
            },
        )
        scheduler.restoreFromStore()
        return scheduler
    }

    /**
     * Final [FraggleToolRegistry] for the messenger assistant — the base registry
     * (filesystem/shell/web/time from [ToolsModule]) plus scheduling tools that
     * require [TaskScheduler] + [ChatBridgeManager].
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideAssistantFraggleToolRegistry(
        @BaseFraggleToolRegistry baseRegistry: FraggleToolRegistry,
        taskScheduler: TaskScheduler,
    ): FraggleToolRegistry {
        val schedulingTools: List<AgentToolDef<*>> = listOf(
            ScheduleTaskTool(taskScheduler),
            ListTasksTool(taskScheduler),
            CancelTaskTool(taskScheduler),
            GetTaskTool(taskScheduler),
            SkipReplyTool(),
        )
        return FraggleToolRegistry(baseRegistry.tools + schedulingTools)
    }

    @Provides
    @SingleIn(AppScope::class)
    fun provideBridgeInitializerRegistry(): BridgeInitializerRegistry =
        BridgeInitializerRegistry()

    @Provides
    @SingleIn(AppScope::class)
    fun provideChatCommandProcessor(
        eventBus: EventBus,
        skillRegistryLoader: SkillRegistryLoader,
        skillsConfig: SkillsConfig,
        thinkingController: ThinkingController,
    ): ChatCommandProcessor = ChatCommandProcessor(
        eventBus = eventBus,
        skillExpander = SkillCommandExpander { skillRegistryLoader.load(skillsConfig) },
        skillCommandsEnabled = skillsConfig.enabled && skillsConfig.enableSlashCommands,
        thinkingController = thinkingController,
    )

    @Provides
    @SingleIn(AppScope::class)
    fun provideFraggleDatabase(config: DatabaseConfig): FraggleDatabase {
        val dbPath = FraggleEnvironment.resolvePath(config.path)
        val db = FraggleDatabase(dbPath)
        db.connect()
        return db
    }

    @Provides
    @SingleIn(AppScope::class)
    fun provideChatHistoryStore(database: FraggleDatabase): ChatHistoryStore {
        return ExposedChatHistoryStore(database)
    }

    @Provides
    @SingleIn(AppScope::class)
    fun provideScheduledTaskStore(database: FraggleDatabase): ScheduledTaskStore {
        return ExposedScheduledTaskStore(database)
    }

    @Provides
    @SingleIn(AppScope::class)
    fun provideFraggleAgent(
        lmStudioProvider: LMStudioProvider,
        llmBridge: LlmBridge,
        toolCallExecutor: ToolCallExecutor,
        memory: MemoryStore,
        config: RuntimeAgentConfig,
        promptManager: PromptManager,
        skillRegistryLoader: SkillRegistryLoader,
        skillsConfig: SkillsConfig,
        skillSecretsStore: SkillSecretsStore,
        traceStore: TraceStore?,
        eventBus: EventBus,
        scope: CoroutineScope,
    ): FraggleAgent = FraggleAgent(
        lmStudioProvider = lmStudioProvider,
        llmBridge = llmBridge,
        toolCallExecutor = toolCallExecutor,
        memory = memory,
        config = config,
        promptManager = promptManager,
        skillRegistryLoader = skillRegistryLoader,
        skillsConfig = skillsConfig,
        skillSecretsStore = skillSecretsStore,
        traceStore = traceStore,
        eventBus = eventBus,
        scope = scope,
    )
}
