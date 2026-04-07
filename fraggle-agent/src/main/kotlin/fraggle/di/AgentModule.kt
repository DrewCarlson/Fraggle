package fraggle.di

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.memory.providers.AgentMemoryProvider
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.ktor.client.*
import kotlinx.coroutines.CoroutineScope
import fraggle.FraggleEnvironment
import fraggle.agent.FraggleAgent
import fraggle.chat.BridgeInitializerRegistry
import fraggle.chat.ChatBridgeManager
import fraggle.chat.ChatCommandProcessor
import fraggle.db.ChatHistoryStore
import fraggle.db.ExposedChatHistoryStore
import fraggle.db.FraggleDatabase
import fraggle.events.EventBus
import fraggle.executor.LocalToolExecutor
import fraggle.executor.RemoteToolClient
import fraggle.executor.ToolExecutor
import fraggle.executor.supervision.InteractiveToolSupervisor
import fraggle.executor.supervision.NoOpToolSupervisor
import fraggle.executor.supervision.ToolArgTypes
import fraggle.executor.supervision.ToolPermissionHandler
import fraggle.executor.supervision.ToolPolicyEvaluator
import fraggle.executor.supervision.ToolSupervisor
import fraggle.memory.FileMemoryStore
import fraggle.memory.FraggleMemoryProvider
import fraggle.memory.MemoryStore
import fraggle.models.*
import fraggle.prompt.PromptConfig
import fraggle.prompt.PromptManager
import fraggle.models.TracingConfig
import fraggle.models.TracingLevel
import fraggle.tracing.FraggleTraceProcessor
import fraggle.tracing.TraceStore
import kotlin.io.path.createDirectories
import fraggle.agent.AgentConfig as RuntimeAgentConfig
import fraggle.models.AgentConfig as ModelsAgentConfig

/**
 * Provides core agent services.
 */
@ContributesTo(AppScope::class)
interface AgentModule {
    companion object {
        @Provides
        @SingleIn(AppScope::class)
        fun provideMemoryStore(config: MemoryConfig): MemoryStore {
            val baseDir = FraggleEnvironment.resolvePath(config.baseDir)
            baseDir.createDirectories()
            return FileMemoryStore(baseDir)
        }

        @Provides
        @SingleIn(AppScope::class)
        fun provideToolExecutor(config: ExecutorConfig): ToolExecutor {
            val workDir = FraggleEnvironment.resolvePath(config.workDir)
            workDir.createDirectories()
            return LocalToolExecutor(workDir)
        }

        @Provides
        @SingleIn(AppScope::class)
        fun provideToolSupervisor(
            config: ExecutorConfig,
            handler: ToolPermissionHandler?,
            argTypes: ToolArgTypes,
        ): ToolSupervisor {
            return when (config.supervision) {
                SupervisionMode.NONE -> NoOpToolSupervisor()
                SupervisionMode.SUPERVISED -> {
                    val permHandler = handler ?: return NoOpToolSupervisor()
                    val evaluator = ToolPolicyEvaluator(config.toolPolicies, argTypes)
                    InteractiveToolSupervisor(evaluator, permHandler)
                }
            }
        }

        @Provides
        @SingleIn(AppScope::class)
        fun provideRemoteToolClient(
            config: ExecutorConfig,
            @DefaultHttpClient httpClient: HttpClient,
        ): RemoteToolClient? {
            if (config.type != ExecutorType.REMOTE || config.remoteUrl.isBlank()) return null
            return RemoteToolClient(httpClient, config.remoteUrl)
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
        fun providePromptExecutor(
            config: ProviderConfig,
            @LlmHttpClient httpClient: HttpClient
        ): PromptExecutor {
            val client = OpenAILLMClient(
                apiKey = config.apiKey ?: "stub",
                settings = OpenAIClientSettings(baseUrl = config.url),
                baseClient = httpClient,
            )
            return MultiLLMPromptExecutor(client)
        }

        @Provides
        @SingleIn(AppScope::class)
        fun provideAgentRuntimeConfig(
            providerConfig: ProviderConfig,
            agentConfig: ModelsAgentConfig,
        ): RuntimeAgentConfig {
            return RuntimeAgentConfig(
                model = providerConfig.model,
                temperature = agentConfig.temperature,
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
        fun provideMemoryProvider(memory: MemoryStore): AgentMemoryProvider =
            FraggleMemoryProvider(memory)

        @Provides
        @SingleIn(AppScope::class)
        fun provideTraceStore(config: TracingConfig): TraceStore? {
            if (config.level == TracingLevel.OFF) return null
            return TraceStore()
        }

        @Provides
        @SingleIn(AppScope::class)
        fun provideFraggleTraceProcessor(
            config: TracingConfig,
            traceStore: TraceStore?,
            eventBus: EventBus,
        ): FraggleTraceProcessor? {
            if (config.level == TracingLevel.OFF || traceStore == null) return null
            return FraggleTraceProcessor(config.level, traceStore, eventBus)
        }

        @Provides
        @SingleIn(AppScope::class)
        fun provideFraggleAgent(
            promptExecutor: PromptExecutor,
            toolRegistry: ToolRegistry,
            memory: MemoryStore,
            memoryProvider: AgentMemoryProvider,
            config: RuntimeAgentConfig,
            promptManager: PromptManager,
            traceProcessor: FraggleTraceProcessor?,
        ): FraggleAgent = FraggleAgent(
            promptExecutor = promptExecutor,
            toolRegistry = toolRegistry,
            memory = memory,
            memoryProvider = memoryProvider,
            config = config,
            promptManager = promptManager,
            traceProcessor = traceProcessor,
        )
    }
}
