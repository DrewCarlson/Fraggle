package org.drewcarlson.fraggle.di

import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.ktor.client.*
import kotlinx.coroutines.CoroutineScope
import org.drewcarlson.fraggle.FraggleEnvironment
import org.drewcarlson.fraggle.agent.Conversation
import org.drewcarlson.fraggle.agent.FraggleAgent
import org.drewcarlson.fraggle.chat.BridgeInitializerRegistry
import org.drewcarlson.fraggle.chat.ChatBridgeManager
import org.drewcarlson.fraggle.memory.FileMemoryStore
import org.drewcarlson.fraggle.memory.MemoryStore
import org.drewcarlson.fraggle.models.MemoryConfig
import org.drewcarlson.fraggle.models.ProviderConfig
import org.drewcarlson.fraggle.models.ProviderType
import org.drewcarlson.fraggle.models.SandboxType
import org.drewcarlson.fraggle.models.SandboxConfig
import org.drewcarlson.fraggle.models.PromptsConfig
import org.drewcarlson.fraggle.prompt.PromptConfig
import org.drewcarlson.fraggle.prompt.PromptManager
import org.drewcarlson.fraggle.provider.LLMProvider
import org.drewcarlson.fraggle.provider.LMStudioProvider
import org.drewcarlson.fraggle.sandbox.PermissiveSandbox
import org.drewcarlson.fraggle.sandbox.Sandbox
import org.drewcarlson.fraggle.skill.SkillRegistry
import java.util.concurrent.ConcurrentHashMap
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
        fun provideLLMProvider(
            config: ProviderConfig,
            @LlmHttpClient httpClient: HttpClient,
        ): LLMProvider {
            return when (config.type) {
                ProviderType.LMSTUDIO -> LMStudioProvider(
                    baseUrl = config.url,
                    defaultModel = config.model.takeIf { it.isNotBlank() },
                    httpClient = httpClient,
                )
                ProviderType.OPENAI -> error("OpenAI provider not yet implemented")
                ProviderType.ANTHROPIC -> error("Anthropic provider not yet implemented")
            }
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
        fun provideConversationMap(): ConcurrentHashMap<String, Conversation> =
            ConcurrentHashMap()

        @Provides
        @SingleIn(AppScope::class)
        fun provideFraggleAgent(
            provider: LLMProvider,
            skills: SkillRegistry,
            memory: MemoryStore,
            sandbox: Sandbox,
            config: RuntimeAgentConfig,
            promptManager: PromptManager,
        ): FraggleAgent = FraggleAgent(
            provider = provider,
            skills = skills,
            memory = memory,
            sandbox = sandbox,
            config = config,
            promptManager = promptManager,
        )
    }
}
