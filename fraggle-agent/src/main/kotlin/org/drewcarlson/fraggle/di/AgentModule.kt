package org.drewcarlson.fraggle.di

import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import org.drewcarlson.fraggle.FraggleEnvironment
import org.drewcarlson.fraggle.agent.FraggleAgent
import org.drewcarlson.fraggle.chat.BridgeInitializerRegistry
import org.drewcarlson.fraggle.chat.ChatBridgeManager
import org.drewcarlson.fraggle.db.ChatHistoryStore
import org.drewcarlson.fraggle.db.ExposedChatHistoryStore
import org.drewcarlson.fraggle.db.FraggleDatabase
import org.drewcarlson.fraggle.memory.FileMemoryStore
import org.drewcarlson.fraggle.memory.MemoryStore
import org.drewcarlson.fraggle.models.DatabaseConfig
import org.drewcarlson.fraggle.models.MemoryConfig
import org.drewcarlson.fraggle.models.ProviderConfig
import org.drewcarlson.fraggle.models.SandboxType
import org.drewcarlson.fraggle.models.SandboxConfig
import org.drewcarlson.fraggle.models.PromptsConfig
import org.drewcarlson.fraggle.prompt.PromptConfig
import org.drewcarlson.fraggle.prompt.PromptManager
import org.drewcarlson.fraggle.sandbox.PermissiveSandbox
import org.drewcarlson.fraggle.sandbox.Sandbox
import ai.koog.agents.core.tools.ToolRegistry
import kotlin.io.path.createDirectories
import org.drewcarlson.fraggle.models.AgentConfig as ModelsAgentConfig
import org.drewcarlson.fraggle.agent.AgentConfig as RuntimeAgentConfig

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
        fun provideSandbox(config: SandboxConfig): Sandbox {
            val workDir = FraggleEnvironment.resolvePath(config.workDir)
            workDir.createDirectories()
            return when (config.type) {
                SandboxType.PERMISSIVE -> PermissiveSandbox(workDir)
                SandboxType.DOCKER -> PermissiveSandbox(workDir) // fallback
                SandboxType.GVISOR -> PermissiveSandbox(workDir) // fallback
            }
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
            return SingleLLMPromptExecutor(client)
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
            promptExecutor: PromptExecutor,
            toolRegistry: ToolRegistry,
            memory: MemoryStore,
            sandbox: Sandbox,
            config: RuntimeAgentConfig,
            promptManager: PromptManager,
        ): FraggleAgent = FraggleAgent(
            promptExecutor = promptExecutor,
            toolRegistry = toolRegistry,
            memory = memory,
            sandbox = sandbox,
            config = config,
            promptManager = promptManager,
        )
    }
}
