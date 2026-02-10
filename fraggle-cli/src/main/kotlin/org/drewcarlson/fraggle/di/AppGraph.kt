package org.drewcarlson.fraggle.di

import ai.koog.agents.core.tools.ToolRegistry
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import io.ktor.client.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.CoroutineScope
import org.drewcarlson.fraggle.FraggleServicesImpl
import org.drewcarlson.fraggle.ServiceOrchestrator
import org.drewcarlson.fraggle.agent.FraggleAgent
import org.drewcarlson.fraggle.agent.InlineImageProcessor
import org.drewcarlson.fraggle.chat.BridgeInitializerRegistry
import org.drewcarlson.fraggle.chat.ChatBridgeManager
import org.drewcarlson.fraggle.chat.ChatCommandProcessor
import org.drewcarlson.fraggle.db.ChatHistoryStore
import org.drewcarlson.fraggle.db.FraggleDatabase
import org.drewcarlson.fraggle.discord.DiscordBridge
import org.drewcarlson.fraggle.discord.DiscordBridgeInitializer
import org.drewcarlson.fraggle.events.EventBus
import org.drewcarlson.fraggle.executor.supervision.ToolPermissionHandler
import org.drewcarlson.fraggle.memory.MemoryStore
import org.drewcarlson.fraggle.models.ApiConfig
import org.drewcarlson.fraggle.models.FraggleConfig
import org.drewcarlson.fraggle.signal.MessageRouter
import org.drewcarlson.fraggle.signal.SignalBridge
import org.drewcarlson.fraggle.signal.SignalBridgeInitializer
import org.drewcarlson.fraggle.tools.scheduling.TaskScheduler
import org.drewcarlson.fraggle.tools.web.PlaywrightFetcher
import java.nio.file.Path

/**
 * Main application dependency graph.
 *
 * This graph provides all application-scoped dependencies including:
 * - HTTP clients (default and LLM-optimized)
 * - Configuration and sub-configs
 * - Agent, tools, memory, executor
 * - Chat bridges (Signal, Discord)
 * - API server
 *
 * Usage:
 * ```
 * val graph = createAppGraph { create(config, configPath, eventBus, permissionHandler) }
 * ```
 */
@DependencyGraph(AppScope::class)
interface AppGraph {
    /** General-purpose HTTP client for web requests */
    @get:DefaultHttpClient
    val defaultHttpClient: HttpClient

    /** HTTP client with extended timeouts for LLM API calls */
    @get:LlmHttpClient
    val llmHttpClient: HttpClient

    /** Application configuration */
    val config: FraggleConfig

    /** Path to the configuration file */
    val configPath: Path

    /** Application-scoped CoroutineScope */
    val appScope: CoroutineScope

    /** Core agent */
    val agent: FraggleAgent

    /** Memory store */
    val memoryStore: MemoryStore

    /** Tool registry */
    val toolRegistry: ToolRegistry

    /** Chat bridge manager */
    val chatBridgeManager: ChatBridgeManager

    /** Bridge initializer registry */
    val bridgeInitializerRegistry: BridgeInitializerRegistry

    /** Task scheduler */
    val taskScheduler: TaskScheduler

    /** Inline image processor (auto-constructed via @Inject) */
    val inlineImageProcessor: InlineImageProcessor

    /** API services implementation */
    val fraggleServices: FraggleServicesImpl

    /** API configuration */
    val apiConfig: ApiConfig

    /** API server (null if API disabled) */
    val apiServer: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>?

    /** Signal bridge (null if unconfigured) */
    val signalBridge: SignalBridge?

    /** Signal bridge initializer (null if unconfigured) */
    val signalBridgeInitializer: SignalBridgeInitializer?

    /** Signal message router (null if unconfigured) */
    val messageRouter: MessageRouter?

    /** Discord bridge (null if unconfigured) */
    val discordBridge: DiscordBridge?

    /** Discord bridge initializer (null if unconfigured) */
    val discordBridgeInitializer: DiscordBridgeInitializer?

    /** Playwright fetcher (null if unconfigured) */
    val playwrightFetcher: PlaywrightFetcher?

    /** Database */
    val fraggleDatabase: FraggleDatabase

    /** Chat history store*/
    val chatHistoryStore: ChatHistoryStore

    /** Chat command processor for slash commands in chat bridges */
    val chatCommandProcessor: ChatCommandProcessor

    /** Event bus */
    val eventBus: EventBus

    val serviceOrchestrator: ServiceOrchestrator

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides config: FraggleConfig,
            @Provides configPath: Path,
            @Provides eventBus: EventBus,
            @Provides permissionHandler: ToolPermissionHandler?,
        ): AppGraph
    }
}
