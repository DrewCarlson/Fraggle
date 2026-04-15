package fraggle.di

import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import fraggle.models.*

/**
 * Extracts sub-config objects from [FraggleConfig] so they can be individually injected.
 */
@ContributesTo(AppScope::class)
interface ConfigModule {
    @Provides
    @SingleIn(AppScope::class)
    fun provideSettings(config: FraggleConfig): FraggleSettings = config.fraggle

    @Provides
    @SingleIn(AppScope::class)
    fun provideProviderConfig(settings: FraggleSettings): ProviderConfig = settings.provider

    @Provides
    @SingleIn(AppScope::class)
    fun provideBridgesConfig(settings: FraggleSettings): BridgesConfig = settings.bridges

    @Provides
    @SingleIn(AppScope::class)
    fun provideSignalBridgeConfig(bridges: BridgesConfig): SignalBridgeConfig? = bridges.signal

    @Provides
    @SingleIn(AppScope::class)
    fun provideDiscordBridgeConfig(bridges: BridgesConfig): DiscordBridgeConfig? = bridges.discord

    @Provides
    @SingleIn(AppScope::class)
    fun provideMemoryConfig(settings: FraggleSettings): MemoryConfig = settings.memory

    @Provides
    @SingleIn(AppScope::class)
    fun provideExecutorConfig(settings: FraggleSettings): ExecutorConfig = settings.executor

    @Provides
    @SingleIn(AppScope::class)
    fun provideAgentConfig(settings: FraggleSettings): AgentConfig = settings.agent

    @Provides
    @SingleIn(AppScope::class)
    fun providePromptsConfig(settings: FraggleSettings): PromptsConfig = settings.prompts

    @Provides
    @SingleIn(AppScope::class)
    fun provideWebConfig(settings: FraggleSettings): WebConfig = settings.web

    @Provides
    @SingleIn(AppScope::class)
    fun providePlaywrightConfig(web: WebConfig): PlaywrightConfig? = web.playwright

    @Provides
    @SingleIn(AppScope::class)
    fun provideApiConfig(settings: FraggleSettings): ApiConfig = settings.api

    @Provides
    @SingleIn(AppScope::class)
    fun provideDashboardConfig(settings: FraggleSettings): DashboardConfig = settings.dashboard

    @Provides
    @SingleIn(AppScope::class)
    fun provideChatsConfig(settings: FraggleSettings): ChatsConfig = settings.chats

    @Provides
    @SingleIn(AppScope::class)
    fun provideDatabaseConfig(settings: FraggleSettings): DatabaseConfig = settings.database

    @Provides
    @SingleIn(AppScope::class)
    fun provideTracingConfig(settings: FraggleSettings): TracingConfig = settings.tracing
}
