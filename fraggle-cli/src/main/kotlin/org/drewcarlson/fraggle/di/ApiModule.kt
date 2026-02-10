package org.drewcarlson.fraggle.di

import ai.koog.agents.core.tools.ToolRegistry
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.ktor.client.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.CoroutineScope
import org.drewcarlson.fraggle.FraggleServicesImpl
import org.drewcarlson.fraggle.backend.createApiServer
import org.drewcarlson.fraggle.chat.BridgeInitializerRegistry
import org.drewcarlson.fraggle.chat.ChatBridgeManager
import org.drewcarlson.fraggle.db.ChatHistoryStore
import org.drewcarlson.fraggle.discord.DiscordBridge
import org.drewcarlson.fraggle.events.EventBus
import org.drewcarlson.fraggle.memory.MemoryStore
import org.drewcarlson.fraggle.models.ApiConfig
import org.drewcarlson.fraggle.models.DashboardConfig
import org.drewcarlson.fraggle.models.FraggleConfig
import org.drewcarlson.fraggle.tools.scheduling.TaskScheduler
import org.drewcarlson.fraggle.tracing.TraceStore
import java.nio.file.Path

/**
 * Provides the API server and its dependencies.
 */
@ContributesTo(AppScope::class)
interface ApiModule {
    companion object {
        @Provides
        @SingleIn(AppScope::class)
        fun provideFraggleServices(
            memory: MemoryStore,
            toolRegistry: ToolRegistry,
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
            traceStore: TraceStore,
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
}
