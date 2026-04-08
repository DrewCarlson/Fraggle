package fraggle.tools.web

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import fraggle.agent.ResponseAttachment
import fraggle.agent.ToolExecutionContext
import org.slf4j.LoggerFactory

class FetchWebpageTool(
    private val httpClient: HttpClient,
    private val playwrightFetcher: PlaywrightFetcher?,
) : SimpleTool<FetchWebpageTool.Args>(
    argsType = typeToken<Args>(),
    name = "fetch_webpage",
    description = buildString {
        append("Fetch content from a webpage URL. ")
        if (playwrightFetcher != null) {
            append("Uses a real browser to render JavaScript, making it ideal for modern websites ")
            append("that use React, Vue, Angular, or other JavaScript frameworks. ")
        }
        append("Returns the page content as readable text.\n\n")
        append("USE THIS TOOL FOR:\n")
        append("- Reading articles, blog posts, documentation\n")
        append("- Accessing web applications and dynamic sites\n")
        append("- Any HTML webpage you need to read or extract information from\n\n")
        append("DO NOT USE FOR:\n")
        append("- JSON APIs (use fetch_api instead)\n")
        append("- Downloading images (use send_image instead)\n")
        append("- Raw data files or non-HTML content")
    },
) {
    private val logger = LoggerFactory.getLogger(FetchWebpageTool::class.java)

    @Serializable
    data class Args(
        @param:LLMDescription("The URL of the webpage to fetch")
        val url: String,
    )

    override suspend fun execute(args: Args): String {
        if (!args.url.startsWith("http://") && !args.url.startsWith("https://")) {
            return "Error: Invalid URL: must start with http:// or https://"
        }

        // Try Playwright first if available
        if (playwrightFetcher != null) {
            try {
                if (!playwrightFetcher.isConnected()) {
                    playwrightFetcher.connect()
                }

                val result = playwrightFetcher.fetch(
                    url = args.url,
                    extractText = true,
                )

                val content = if (result.content.length > 50_000) {
                    result.content.take(50_000) + "\n... (truncated)"
                } else {
                    result.content
                }

                return buildString {
                    appendLine("URL: ${result.finalUrl}")
                    result.title?.let { appendLine("Title: $it") }
                    result.statusCode?.let { appendLine("Status: $it") }
                    appendLine()
                    append(content)
                }
            } catch (e: Exception) {
                logger.warn("Playwright fetch failed, falling back to HTTP: ${e.message}")
            }
        }

        // HTTP fallback using Ktor httpClient
        return try {
            val response = httpClient.get(args.url)
            val body = response.bodyAsText()
            val statusCode = response.status.value
            val contentType = response.contentType()?.toString()

            if (statusCode in 200..299) {
                val truncated = if (body.length > 50_000) {
                    body.take(50_000) + "\n... (truncated)"
                } else {
                    body
                }
                "Status: $statusCode\nContent-Type: ${contentType ?: "unknown"}\n\n$truncated"
            } else {
                "Error: HTTP $statusCode: ${body.take(500)}"
            }
        } catch (e: Exception) {
            "Error: Failed to fetch webpage: ${e.message}"
        }
    }
}

class FetchApiTool(
    private val httpClient: HttpClient,
) : SimpleTool<FetchApiTool.Args>(
    argsType = typeToken<Args>(),
    name = "fetch_api",
    description = """Fetch data from an API endpoint. Uses simple HTTP without browser rendering.

USE THIS TOOL FOR:
- JSON REST APIs
- XML/RSS feeds
- GraphQL endpoints
- Any structured data API
- Downloading raw text/data files

DO NOT USE FOR:
- HTML webpages (use fetch_webpage instead)
- Websites with JavaScript rendering
- Downloading images (use send_image instead)""",
) {
    @Serializable
    data class Args(
        @param:LLMDescription("The API endpoint URL to fetch")
        val url: String,
        @param:LLMDescription("HTTP method (GET, POST, PUT, DELETE, etc.). Defaults to GET.")
        val method: String = "GET",
    )

    override suspend fun execute(args: Args): String {
        if (!args.url.startsWith("http://") && !args.url.startsWith("https://")) {
            return "Error: Invalid URL: must start with http:// or https://"
        }

        return try {
            val response = httpClient.request(args.url) {
                this.method = HttpMethod.parse(args.method.uppercase())
            }

            val body = response.bodyAsText()
            val statusCode = response.status.value
            val contentType = response.contentType()?.toString()

            if (statusCode in 200..299) {
                val truncatedBody = if (body.length > 50_000) {
                    body.take(50_000) + "\n... (truncated)"
                } else {
                    body
                }
                "Status: $statusCode\nContent-Type: ${contentType ?: "unknown"}\n\n$truncatedBody"
            } else {
                "Error: HTTP $statusCode: ${body.take(500)}"
            }
        } catch (e: Exception) {
            "Error: Failed to fetch API: ${e.message}"
        }
    }
}

class ScreenshotPageTool(
    private val playwrightFetcher: PlaywrightFetcher,
) : SimpleTool<ScreenshotPageTool.Args>(
    argsType = typeToken<Args>(),
    name = "screenshot_page",
    description = """Take a screenshot of a web page and send it WITH your response.
The page will be fully rendered with JavaScript before the screenshot is taken.

The screenshot will be automatically combined with your text response into one cohesive message.
Simply describe or explain the screenshot in your response text - the image will be attached automatically.""",
) {
    private val logger = LoggerFactory.getLogger(ScreenshotPageTool::class.java)

    @Serializable
    data class Args(
        @param:LLMDescription("The URL to screenshot")
        val url: String,
        @param:LLMDescription("If true, captures the entire scrollable page. Defaults to false (viewport only).")
        val full_page: Boolean = false,
        @param:LLMDescription("Optional caption to include with the screenshot")
        val caption: String? = null,
    )

    override suspend fun execute(args: Args): String {
        if (!args.url.startsWith("http://") && !args.url.startsWith("https://")) {
            return "Error: Invalid URL: must start with http:// or https://"
        }

        return try {
            if (!playwrightFetcher.isConnected()) {
                playwrightFetcher.connect()
            }

            val imageData = playwrightFetcher.screenshot(url = args.url, fullPage = args.full_page)
            val caption = args.caption?.takeIf { it.isNotBlank() && it.lowercase() != "null" }

            // Collect attachment via execution context
            ToolExecutionContext.current()?.attachments?.add(
                ResponseAttachment.Image(
                    data = imageData,
                    mimeType = "image/png",
                    caption = caption ?: "Screenshot of ${args.url}",
                )
            )

            "Screenshot captured (${imageData.size / 1024}KB) and will be sent to the chat."
        } catch (e: Exception) {
            logger.error("Screenshot failed: ${e.message}", e)
            "Error: Failed to capture screenshot: ${e.message}"
        }
    }
}
