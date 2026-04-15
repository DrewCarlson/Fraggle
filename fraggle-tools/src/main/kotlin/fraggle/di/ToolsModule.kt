package fraggle.di

import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.ktor.client.*
import fraggle.agent.tool.FraggleToolRegistry
import fraggle.executor.ToolExecutor
import fraggle.executor.supervision.ToolArgTypes
import fraggle.tools.DefaultTools
import fraggle.tools.web.PlaywrightFetcher
import org.slf4j.LoggerFactory
import fraggle.models.PlaywrightConfig as ModelsPlaywrightConfig
import fraggle.tools.web.PlaywrightConfig as RuntimePlaywrightConfig

private val logger = LoggerFactory.getLogger(ToolsModule::class.java)

/**
 * Provides generic tool bindings (filesystem, shell, web, time) that any agent
 * application can use.
 *
 * The [FraggleToolRegistry] provided here is qualified with [BaseFraggleToolRegistry]
 * so app-specific modules can decorate it with their own tools before exposing an
 * unqualified registry. For example, `fraggle-assistant` appends scheduling tools
 * (which need `ChatBridgeManager`); a future coding-agent app would skip the
 * assistant's decoration entirely and build its own registry.
 */
@ContributesTo(AppScope::class)
interface ToolsModule {

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

    @Provides
    @SingleIn(AppScope::class)
    fun provideToolArgTypes(): ToolArgTypes = DefaultTools.extractArgTypes()

    @Provides
    @SingleIn(AppScope::class)
    @BaseFraggleToolRegistry
    fun provideBaseFraggleToolRegistry(
        toolExecutor: ToolExecutor,
        @DefaultHttpClient httpClient: HttpClient,
        playwrightFetcher: PlaywrightFetcher?,
    ): FraggleToolRegistry = DefaultTools.createToolRegistry(
        toolExecutor = toolExecutor,
        httpClient = httpClient,
        playwrightFetcher = playwrightFetcher,
    )
}
