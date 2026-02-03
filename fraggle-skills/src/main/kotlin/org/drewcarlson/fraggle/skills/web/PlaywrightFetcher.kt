package org.drewcarlson.fraggle.skills.web

import com.microsoft.playwright.*
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.WaitUntilState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Configuration for Playwright browser connection.
 */
data class PlaywrightConfig(
    /**
     * WebSocket URL for connecting to a Playwright browser server.
     * Example: ws://localhost:3000
     */
    val wsEndpoint: String,

    /**
     * Timeout in milliseconds for page navigation. Defaults to 30000 (30 seconds).
     */
    val navigationTimeout: Long = 30_000,

    /**
     * Additional time to wait after page load for JavaScript to execute.
     * Defaults to 2000 (2 seconds).
     */
    val waitAfterLoad: Long = 2_000,

    /**
     * Default viewport width. Defaults to 1280.
     */
    val viewportWidth: Int = 1280,

    /**
     * Default viewport height. Defaults to 720.
     */
    val viewportHeight: Int = 720,

    /**
     * User agent string to use. If not specified, uses browser default.
     */
    val userAgent: String? = null,
)

/**
 * Result of a Playwright page fetch.
 */
data class PlaywrightFetchResult(
    val url: String,
    val finalUrl: String,
    val statusCode: Int?,
    val content: String,
    val title: String?,
    val contentType: String?,
)

/**
 * Fetcher that uses Playwright to render JavaScript-heavy pages.
 *
 * Connects to a remote Playwright browser server via WebSocket.
 * The server can be:
 * - A standalone Playwright server
 * - Browserless.io or similar services
 * - A Docker container running playwright
 *
 * Example Docker command to run a Playwright server:
 * ```
 * docker run -p 3000:3000 browserless/chrome
 * ```
 */
class PlaywrightFetcher(
    private val config: PlaywrightConfig,
) {
    private val logger = LoggerFactory.getLogger(PlaywrightFetcher::class.java)

    private var playwright: Playwright? = null
    private var browser: Browser? = null

    /**
     * Connect to the Playwright browser server.
     */
    suspend fun connect() {
        withContext(Dispatchers.IO) {
            if (browser != null) {
                logger.debug("Already connected to Playwright")
                return@withContext
            }

            logger.info("Connecting to Playwright at ${config.wsEndpoint}")

            playwright = Playwright.create()

            // Connect to the remote browser using the WebSocket endpoint
            browser = playwright!!.chromium().connect(config.wsEndpoint)

            logger.info("Connected to Playwright browser")
        }
    }

    /**
     * Disconnect from the Playwright browser server.
     */
    suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            browser?.close()
            playwright?.close()
            browser = null
            playwright = null
            logger.info("Disconnected from Playwright")
        }
    }

    /**
     * Check if connected to the browser.
     */
    fun isConnected(): Boolean = browser?.isConnected == true

    /**
     * Fetch a URL using Playwright, waiting for JavaScript to render.
     *
     * @param url The URL to fetch
     * @param waitForSelector Optional CSS selector to wait for before returning content
     * @param waitForLoadState The load state to wait for (defaults to "networkidle")
     * @param extractText If true, extracts text content instead of HTML
     */
    suspend fun fetch(
        url: String,
        waitForSelector: String? = null,
        waitForLoadState: String = "networkidle",
        extractText: Boolean = false,
    ): PlaywrightFetchResult = withContext(Dispatchers.IO) {
        val currentBrowser = checkNotNull(browser) {
            "Not connected to Playwright. Call connect() first."
        }

        val contextOptions = Browser.NewContextOptions()
            .setViewportSize(config.viewportWidth, config.viewportHeight)

        config.userAgent?.let { contextOptions.setUserAgent(it) }

        val context = currentBrowser.newContext(contextOptions)
        val page = context.newPage()

        try {
            page.setDefaultTimeout(config.navigationTimeout.toDouble())
            page.setDefaultNavigationTimeout(config.navigationTimeout.toDouble())

            logger.debug("Navigating to $url")

            // Navigate and capture response
            val response = page.navigate(
                url,
                Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.valueOf(waitForLoadState))
            )

            val statusCode = response?.status()
            val contentType = response?.headers()?.get("content-type")

            // Wait for additional load state if needed
            if (waitForLoadState == "networkidle") {
                try {
                    page.waitForLoadState(
                        LoadState.NETWORKIDLE,
                        Page.WaitForLoadStateOptions()
                            .setTimeout(config.navigationTimeout.toDouble())
                    )
                } catch (e: Exception) {
                    logger.debug("Network idle timeout, continuing anyway: ${e.message}")
                }
            }

            // Additional wait for JS to settle
            if (config.waitAfterLoad > 0) {
                Thread.sleep(config.waitAfterLoad)
            }

            // Wait for specific selector if requested (ignore null, empty, or literal "null" strings)
            if (!waitForSelector.isNullOrBlank() && waitForSelector.lowercase() != "null") {
                try {
                    page.waitForSelector(
                        waitForSelector,
                        Page.WaitForSelectorOptions()
                            .setTimeout(config.navigationTimeout.toDouble())
                    )
                } catch (e: Exception) {
                    logger.warn("Selector '$waitForSelector' not found: ${e.message}")
                }
            }

            val finalUrl = page.url()
            val title = page.title()
            val content = if (extractText) {
                page.innerText("body")
            } else {
                page.content()
            }

            logger.debug("Fetched $url -> $finalUrl (${content.length} chars)")

            PlaywrightFetchResult(
                url = url,
                finalUrl = finalUrl,
                statusCode = statusCode,
                content = content,
                title = title,
                contentType = contentType,
            )
        } finally {
            page.close()
            context.close()
        }
    }

    /**
     * Take a screenshot of a URL.
     *
     * @param url The URL to screenshot
     * @param fullPage If true, captures the entire scrollable page
     * @return PNG image data
     */
    suspend fun screenshot(
        url: String,
        fullPage: Boolean = false,
    ): ByteArray = withContext(Dispatchers.IO) {
        val currentBrowser = checkNotNull(browser) {
            "Not connected to Playwright. Call connect() first."
        }

        val contextOptions = Browser.NewContextOptions()
            .setViewportSize(config.viewportWidth, config.viewportHeight)

        config.userAgent?.let { contextOptions.setUserAgent(it) }

        val context = currentBrowser.newContext(contextOptions)
        val page = context.newPage()

        try {
            page.setDefaultTimeout(config.navigationTimeout.toDouble())
            page.setDefaultNavigationTimeout(config.navigationTimeout.toDouble())

            page.navigate(
                url, Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.NETWORKIDLE)
            )

            // Wait for JS to settle
            if (config.waitAfterLoad > 0) {
                Thread.sleep(config.waitAfterLoad)
            }

            page.screenshot(Page.ScreenshotOptions().setFullPage(fullPage))
        } finally {
            page.close()
            context.close()
        }
    }
}
