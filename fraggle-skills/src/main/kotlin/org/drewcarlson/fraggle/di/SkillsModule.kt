package org.drewcarlson.fraggle.di

import ai.koog.agents.core.tools.ToolRegistry
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.ktor.client.*
import kotlinx.coroutines.CoroutineScope
import org.drewcarlson.fraggle.chat.ChatBridgeManager
import org.drewcarlson.fraggle.chat.OutgoingMessage
import org.drewcarlson.fraggle.sandbox.Sandbox
import org.drewcarlson.fraggle.skills.DefaultTools
import org.drewcarlson.fraggle.skills.scheduling.TaskScheduler
import org.drewcarlson.fraggle.skills.web.PlaywrightFetcher
import org.slf4j.LoggerFactory
import org.drewcarlson.fraggle.models.PlaywrightConfig as ModelsPlaywrightConfig
import org.drewcarlson.fraggle.skills.web.PlaywrightConfig as RuntimePlaywrightConfig

/**
 * Provides skill-related services.
 */
@ContributesTo(AppScope::class)
interface SkillsModule {
    companion object {
        private val logger = LoggerFactory.getLogger(SkillsModule::class.java)

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

        private fun createTaskCallback(bridgeManager: ChatBridgeManager): suspend (org.drewcarlson.fraggle.skills.scheduling.ScheduledTask) -> Unit = { task ->
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
        fun provideToolRegistry(
            sandbox: Sandbox,
            @DefaultHttpClient httpClient: HttpClient,
            taskScheduler: TaskScheduler,
            playwrightFetcher: PlaywrightFetcher?,
        ): ToolRegistry = DefaultTools.createToolRegistry(
            sandbox = sandbox,
            httpClient = httpClient,
            taskScheduler = taskScheduler,
            playwrightFetcher = playwrightFetcher,
        )
    }
}
