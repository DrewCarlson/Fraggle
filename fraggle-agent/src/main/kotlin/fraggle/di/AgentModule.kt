package fraggle.di

import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import fraggle.FraggleEnvironment
import fraggle.agent.FraggleAgent
import fraggle.agent.loop.LlmBridge
import fraggle.agent.loop.ProviderLlmBridge
import fraggle.agent.loop.ToolCallExecutor
import fraggle.agent.tool.FraggleToolRegistry
import fraggle.agent.tool.SupervisedToolCallExecutor
import fraggle.provider.LMStudioProvider
import fraggle.chat.BridgeInitializerRegistry
import fraggle.chat.ChatBridgeManager
import fraggle.chat.ChatCommandProcessor
import fraggle.db.ChatHistoryStore
import fraggle.db.ExposedChatHistoryStore
import fraggle.db.FraggleDatabase
import fraggle.events.EventBus
import fraggle.executor.RemoteToolClient
import fraggle.executor.supervision.ToolSupervisor
import fraggle.memory.FileMemoryStore
import fraggle.memory.MemoryStore
import fraggle.models.*
import fraggle.prompt.PromptConfig
import fraggle.prompt.PromptManager
import fraggle.tracing.TraceStore
import kotlin.io.path.createDirectories
import fraggle.agent.AgentConfig as RuntimeAgentConfig
import fraggle.models.AgentConfig as ModelsAgentConfig

/**
 * Provides assistant-specific services (memory, prompts, chat bridges, database,
 * and the FraggleAgent orchestrator itself). Generic runtime bindings (ToolExecutor,
 * ToolSupervisor, LMStudioProvider, TraceStore) live in [AgentCoreModule].
 */
@ContributesTo(AppScope::class)
interface AgentModule {
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

    @Provides
    @SingleIn(AppScope::class)
    fun provideLlmBridge(
        provider: LMStudioProvider,
        runtimeConfig: RuntimeAgentConfig,
    ): LlmBridge = ProviderLlmBridge(
        provider = provider,
        model = runtimeConfig.model,
        temperature = runtimeConfig.temperature,
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
            vision = agentConfig.vision,
        )
    }

    @Provides
    @SingleIn(AppScope::class)
    fun provideChatBridgeManager(scope: CoroutineScope): ChatBridgeManager =
        ChatBridgeManager(scope)

    @Provides
    @SingleIn(AppScope::class)
    fun provideBridgeInitializerRegistry(): BridgeInitializerRegistry =
        BridgeInitializerRegistry()

    @Provides
    @SingleIn(AppScope::class)
    fun provideChatCommandProcessor(eventBus: EventBus): ChatCommandProcessor =
        ChatCommandProcessor(eventBus)

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
        return ExposedChatHistoryStore(database.database)
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
        traceStore: TraceStore?,
        eventBus: EventBus,
    ): FraggleAgent = FraggleAgent(
        lmStudioProvider = lmStudioProvider,
        llmBridge = llmBridge,
        toolCallExecutor = toolCallExecutor,
        memory = memory,
        config = config,
        promptManager = promptManager,
        traceStore = traceStore,
        eventBus = eventBus,
    )
}
