package org.drewcarlson.fraggle.skills.web

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import org.drewcarlson.fraggle.sandbox.Sandbox
import org.drewcarlson.fraggle.sandbox.SandboxResult
import org.drewcarlson.fraggle.skill.*
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

/**
 * Web-related skills for fetching URLs and searching.
 */
object WebSkills {

    private val logger = LoggerFactory.getLogger(WebSkills::class.java)

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
        defaultRequest {
            header(HttpHeaders.UserAgent, "Fraggle/1.0")
        }
    }

    /**
     * Create all web skills with the given sandbox and optional Playwright fetcher.
     *
     * @param sandbox The sandbox for security checks
     * @param playwrightFetcher Optional Playwright fetcher for JavaScript-heavy pages
     */
    fun create(sandbox: Sandbox, playwrightFetcher: PlaywrightFetcher? = null): List<Skill> {
        val skills = mutableListOf(
            fetchWebpage(sandbox, playwrightFetcher),
            fetchApi(sandbox),
            sendImage(),
        )

        // Add screenshot skill if Playwright is configured
        if (playwrightFetcher != null) {
            skills.add(screenshotPage(playwrightFetcher))
        }

        return skills
    }

    /**
     * Primary skill to fetch a webpage. Uses Playwright when available for full JavaScript
     * rendering, falling back to simple HTTP for static content or when Playwright is unavailable.
     *
     * This is the preferred skill for accessing HTML/JavaScript websites.
     */
    fun fetchWebpage(sandbox: Sandbox, playwrightFetcher: PlaywrightFetcher?) = skill("fetch_webpage") {
        val hasPlaywright = playwrightFetcher != null
        description = buildString {
            append("Fetch content from a webpage URL. ")
            if (hasPlaywright) {
                append("Uses a real browser to render JavaScript, making it ideal for modern websites ")
                append("that use React, Vue, Angular, or other JavaScript frameworks. ")
            }
            append("Returns the page content as readable text.\n\n")
            append("USE THIS SKILL FOR:\n")
            append("- Reading articles, blog posts, documentation\n")
            append("- Accessing web applications and dynamic sites\n")
            append("- Any HTML webpage you need to read or extract information from\n\n")
            append("DO NOT USE FOR:\n")
            append("- JSON APIs (use fetch_api instead)\n")
            append("- Downloading images (use send_image instead)\n")
            append("- Raw data files or non-HTML content")
        }

        parameter<String>("url") {
            description = "The URL of the webpage to fetch"
            required = true
        }

        parameter<String>("wait_for_selector") {
            description = "Optional CSS selector to wait for before extracting content. Only use when you know the specific selector exists on the target page."
            required = false
        }

        execute { params ->
            val url = params.get<String>("url")
            val waitForSelector = params.getOrNull<String>("wait_for_selector")
                ?.takeIf { it.isNotBlank() && it.lowercase() != "null" }

            // Validate URL format
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return@execute SkillResult.Error("Invalid URL: must start with http:// or https://")
            }

            // Try Playwright first if available
            if (playwrightFetcher != null) {
                try {
                    // Ensure connected
                    if (!playwrightFetcher.isConnected()) {
                        playwrightFetcher.connect()
                    }

                    val result = playwrightFetcher.fetch(
                        url = url,
                        waitForSelector = waitForSelector,
                        extractText = true, // Always extract text for readability
                    )

                    val content = if (result.content.length > 50_000) {
                        result.content.take(50_000) + "\n... (truncated)"
                    } else {
                        result.content
                    }

                    val output = buildString {
                        appendLine("URL: ${result.finalUrl}")
                        result.title?.let { appendLine("Title: $it") }
                        result.statusCode?.let { appendLine("Status: $it") }
                        appendLine()
                        append(content)
                    }

                    return@execute SkillResult.Success(output)
                } catch (e: Exception) {
                    logger.warn("Playwright fetch failed, falling back to HTTP: ${e.message}")
                    // Fall through to HTTP fallback
                }
            }

            // HTTP fallback (or primary if no Playwright)
            when (val result = sandbox.fetch(url, timeout = 30.seconds)) {
                is SandboxResult.Success -> {
                    val fetch = result.value
                    if (fetch.statusCode in 200..299) {
                        val body = if (fetch.body.length > 50_000) {
                            fetch.body.take(50_000) + "\n... (truncated)"
                        } else {
                            fetch.body
                        }
                        SkillResult.Success(
                            "Status: ${fetch.statusCode}\nContent-Type: ${fetch.contentType ?: "unknown"}\n\n$body"
                        )
                    } else {
                        SkillResult.Error("HTTP ${fetch.statusCode}: ${fetch.body.take(500)}")
                    }
                }
                is SandboxResult.Denied -> SkillResult.Error("Access denied: ${result.reason}")
                is SandboxResult.Error -> SkillResult.Error(result.message)
            }
        }
    }

    /**
     * Skill to fetch data from APIs. Always uses simple HTTP (no browser rendering).
     * Ideal for JSON APIs, XML feeds, and other structured data endpoints.
     */
    fun fetchApi(sandbox: Sandbox) = skill("fetch_api") {
        description = """Fetch data from an API endpoint. Uses simple HTTP without browser rendering.

USE THIS SKILL FOR:
- JSON REST APIs
- XML/RSS feeds
- GraphQL endpoints
- Any structured data API
- Downloading raw text/data files

DO NOT USE FOR:
- HTML webpages (use fetch_webpage instead)
- Websites with JavaScript rendering
- Downloading images (use send_image instead)"""

        parameter<String>("url") {
            description = "The API endpoint URL to fetch"
            required = true
        }

        parameter<String>("method") {
            description = "HTTP method (GET, POST, PUT, DELETE, etc.). Defaults to GET."
            default = "GET"
        }

        execute { params ->
            val url = params.get<String>("url")
            val method = params.getOrDefault("method", "GET")

            // Validate URL format
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return@execute SkillResult.Error("Invalid URL: must start with http:// or https://")
            }

            try {
                val response = httpClient.request(url) {
                    this.method = HttpMethod.parse(method.uppercase())
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
                    SkillResult.Success(
                        "Status: $statusCode\nContent-Type: ${contentType ?: "unknown"}\n\n$truncatedBody"
                    )
                } else {
                    SkillResult.Error("HTTP $statusCode: ${body.take(500)}")
                }
            } catch (e: Exception) {
                SkillResult.Error("Failed to fetch API: ${e.message}")
            }
        }
    }

    /**
     * Skill to download an image from a URL and send it to the user.
     */
    fun sendImage() = skill("send_image") {
        description = """Download an image from a URL and send it as an attachment to the user.
            |Use this skill when the user asks you to show, send, or share an image from the web.
            |
            |IMPORTANT: The image is sent AUTOMATICALLY as a native attachment - do NOT include any image
            |URLs, markdown image syntax, or references in your response text. Simply confirm the image
            |was sent or describe what it shows.""".trimMargin()

        parameter<String>("url") {
            description = "The URL of the image to download and send"
            required = true
        }

        parameter<String>("caption") {
            description = "Optional caption to include with the image"
            required = false
        }

        execute { params ->
            val url = params.get<String>("url")
            // Filter out "null" strings that LLMs sometimes pass for optional params
            val caption = params.getOrNull<String>("caption")
                ?.takeIf { it.isNotBlank() && it.lowercase() != "null" }

            // Validate URL format
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return@execute SkillResult.Error("Invalid URL: must start with http:// or https://")
            }

            try {
                val response = httpClient.get(url)
                val statusCode = response.status.value
                val contentType = response.contentType()?.toString() ?: "application/octet-stream"

                if (statusCode !in 200..299) {
                    return@execute SkillResult.Error("Failed to download image: HTTP $statusCode")
                }

                // Verify it's an image
                val isImage = contentType.startsWith("image/") ||
                    url.lowercase().let { u ->
                        u.endsWith(".jpg") || u.endsWith(".jpeg") || u.endsWith(".png") ||
                            u.endsWith(".gif") || u.endsWith(".webp") || u.endsWith(".bmp")
                    }

                if (!isImage) {
                    return@execute SkillResult.Error(
                        "URL does not appear to be an image. Content-Type: $contentType"
                    )
                }

                // Download the image data
                val imageData = response.readRawBytes()

                // Check size (limit to 50MB)
                if (imageData.size > 50 * 1024 * 1024) {
                    return@execute SkillResult.Error("Image too large (max 50MB)")
                }

                // Determine MIME type
                val mimeType = when {
                    contentType.startsWith("image/") -> contentType
                    url.lowercase().endsWith(".png") -> "image/png"
                    url.lowercase().endsWith(".gif") -> "image/gif"
                    url.lowercase().endsWith(".webp") -> "image/webp"
                    else -> "image/jpeg"
                }

                SkillResult.ImageAttachment(
                    imageData = imageData,
                    mimeType = mimeType,
                    caption = caption,
                    output = "Image downloaded (${imageData.size / 1024}KB) and will be sent to the chat."
                )
            } catch (e: Exception) {
                SkillResult.Error("Failed to download image: ${e.message}")
            }
        }
    }

    /**
     * Skill to take a screenshot of a web page.
     */
    fun screenshotPage(playwrightFetcher: PlaywrightFetcher) = skill("screenshot_page") {
        description = """Take a screenshot of a web page and send it as an attachment to the user.
            |The page will be fully rendered with JavaScript before the screenshot is taken.
            |
            |IMPORTANT: The screenshot is sent AUTOMATICALLY as a native image attachment - do NOT include
            |any image URLs, markdown image syntax, or references in your response text. Simply confirm
            |the screenshot was taken or describe what it shows.""".trimMargin()

        parameter<String>("url") {
            description = "The URL to screenshot"
            required = true
        }

        parameter<Boolean>("full_page") {
            description = "If true, captures the entire scrollable page. Defaults to false (viewport only)."
            default = false
        }

        parameter<String>("caption") {
            description = "Optional caption to include with the screenshot"
            required = false
        }

        execute { params ->
            val url = params.get<String>("url")
            val fullPage = params.getOrDefault("full_page", false)
            // Filter out "null" strings that LLMs sometimes pass for optional params
            val caption = params.getOrNull<String>("caption")
                ?.takeIf { it.isNotBlank() && it.lowercase() != "null" }

            // Validate URL format
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return@execute SkillResult.Error("Invalid URL: must start with http:// or https://")
            }

            try {
                // Ensure connected
                if (!playwrightFetcher.isConnected()) {
                    playwrightFetcher.connect()
                }

                val imageData = playwrightFetcher.screenshot(url = url, fullPage = fullPage)

                SkillResult.ImageAttachment(
                    imageData = imageData,
                    mimeType = "image/png",
                    caption = caption ?: "Screenshot of $url",
                    output = "Screenshot captured (${imageData.size / 1024}KB) and will be sent to the chat."
                )
            } catch (e: Exception) {
                logger.error("Screenshot failed: ${e.message}", e)
                SkillResult.Error("Failed to capture screenshot: ${e.message}")
            }
        }
    }
}
