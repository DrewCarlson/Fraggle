package fraggle.di

import dev.zacsweers.metro.ContributesTo
import fraggle.agent.tool.FraggleToolRegistry
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.ktor.client.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.CoroutineScope
import fraggle.FraggleServicesImpl
import fraggle.backend.createApiServer
import fraggle.chat.BridgeInitializerRegistry
import fraggle.chat.ChatBridgeManager
import fraggle.db.ChatHistoryStore
import fraggle.discord.DiscordBridge
import fraggle.events.EventBus
import fraggle.memory.MemoryStore
import fraggle.models.ApiConfig
import fraggle.models.DashboardConfig
import fraggle.models.FraggleConfig
import fraggle.tools.scheduling.TaskScheduler
import fraggle.tracing.TraceStore
import java.nio.file.Path

/**
 * Provides the API server and its dependencies.
 */
@ContributesTo(AppScope::class)
interface ApiModule {
    @Provides
    @SingleIn(AppScope::class)
    fun provideFraggleServices(
        memory: MemoryStore,
        toolRegistry: FraggleToolRegistry,
        bridges: ChatBridgeManager,
        taskScheduler: TaskScheduler,
        config: FraggleConfig,
        configPath: Path,
        initializerRegistry: BridgeInitializerRegistry,
        scope: CoroutineScope,
        @DefaultHttpClient httpClient: HttpClient,
        chatHistoryStore: ChatHistoryStore,
        discordBridge: DiscordBridge?,
        eventBus: EventBus,
        traceStore: TraceStore?,
    ): FraggleServicesImpl = FraggleServicesImpl(
        memory = memory,
        toolRegistry = toolRegistry,
        bridges = bridges,
        taskScheduler = taskScheduler,
        fraggleConfig = config,
        configPath = configPath,
        initializerRegistry = initializerRegistry,
        scope = scope,
        httpClient = httpClient,
        chatHistoryStore = chatHistoryStore,
        discordBridge = discordBridge,
        eventBus = eventBus,
        traceStore = traceStore,
    )

    @Provides
    @SingleIn(AppScope::class)
    fun provideApiServer(
        apiConfig: ApiConfig,
        dashboardConfig: DashboardConfig,
        services: FraggleServicesImpl,
    ): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? {
        if (!apiConfig.enabled) return null
        return createApiServer(services, apiConfig, dashboardConfig)
    }
}
