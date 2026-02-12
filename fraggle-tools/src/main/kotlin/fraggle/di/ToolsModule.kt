package fraggle.di

import ai.koog.agents.core.tools.ToolRegistry
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.ktor.client.*
import kotlinx.coroutines.CoroutineScope
import fraggle.chat.ChatBridgeManager
import fraggle.chat.OutgoingMessage
import fraggle.executor.RemoteToolClient
import fraggle.executor.ToolExecutor
import fraggle.executor.supervision.ToolArgTypes
import fraggle.executor.supervision.ToolSupervisor
import fraggle.tools.DefaultTools
import fraggle.tools.scheduling.TaskScheduler
import fraggle.tools.web.PlaywrightFetcher
import org.slf4j.LoggerFactory
import fraggle.models.PlaywrightConfig as ModelsPlaywrightConfig
import fraggle.tools.web.PlaywrightConfig as RuntimePlaywrightConfig

/**
 * Provides tool-related services.
 */
@ContributesTo(AppScope::class)
interface ToolsModule {
    companion object {
        private val logger = LoggerFactory.getLogger(ToolsModule::class.java)

        @Provides
        @SingleIn(AppScope::class)
        fun providePlaywrightFetcher(config: ModelsPlaywrightConfig?): PlaywrightFetcher? {
            if (config == null) return null
            val fetcher = PlaywrightFetcher(
                RuntimePlaywrightConfig(
                    wsEndpoint = config.wsEndpoint,
                    navigationTimeout = config.navigationTimeout,
                    waitAfterLoad = config.waitAfterLoad,
                    viewportWidth = config.viewportWidth,
                    viewportHeight = config.viewportHeight,
                    userAgent = config.userAgent,
                )
            )
            logger.info("Playwright fetcher configured: ${config.wsEndpoint}")
            return fetcher
        }

        private fun createTaskCallback(bridgeManager: ChatBridgeManager): suspend (fraggle.tools.scheduling.ScheduledTask) -> Unit = { task ->
            logger.info("Task triggered: ${task.name} - ${task.action}")
            if (bridgeManager.hasConnectedBridge()) {
                try {
                    bridgeManager.send(task.chatId, OutgoingMessage.Text(task.action))
                    logger.info("Task message sent to ${task.chatId}: ${task.action}")
                } catch (e: Exception) {
                    logger.error("Failed to send task message: ${e.message}", e)
                }
            } else {
                logger.warn("Cannot send task message: No chat bridge connected")
            }
        }

        @Provides
        @SingleIn(AppScope::class)
        fun provideTaskScheduler(
            scope: CoroutineScope,
            bridgeManager: ChatBridgeManager,
        ): TaskScheduler = TaskScheduler(scope, createTaskCallback(bridgeManager))

        @Provides
        @SingleIn(AppScope::class)
        fun provideToolArgTypes(): ToolArgTypes = DefaultTools.extractArgTypes()

        @Provides
        @SingleIn(AppScope::class)
        fun provideToolRegistry(
            toolExecutor: ToolExecutor,
            @DefaultHttpClient httpClient: HttpClient,
            taskScheduler: TaskScheduler,
            supervisor: ToolSupervisor,
            remoteClient: RemoteToolClient?,
            playwrightFetcher: PlaywrightFetcher?,
        ): ToolRegistry = DefaultTools.createToolRegistry(
            toolExecutor = toolExecutor,
            httpClient = httpClient,
            taskScheduler = taskScheduler,
            supervisor = supervisor,
            remoteClient = remoteClient,
            playwrightFetcher = playwrightFetcher,
        )
    }
}
