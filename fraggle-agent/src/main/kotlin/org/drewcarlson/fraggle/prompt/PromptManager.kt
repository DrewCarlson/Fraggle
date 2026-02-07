package org.drewcarlson.fraggle.prompt

import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Configuration for prompt management.
 */
data class PromptConfig(
    /**
     * Directory where prompt files are stored.
     * If files don't exist, they are created from templates.
     */
    val promptsDir: Path,

    /**
     * Maximum characters to include from each prompt file.
     * Files exceeding this limit are truncated with a marker.
     */
    val maxFileChars: Int = 20_000,

    /**
     * Whether to auto-create missing prompt files from templates.
     */
    val autoCreateMissing: Boolean = true,
)

/**
 * Manages loading, creating, and injecting prompt files.
 *
 * Template files are stored as JAR resources and copied to the workspace
 * on first run, allowing users to customize them. Files are treated as
 * freeform markdown and injected directly into the system prompt.
 */
class PromptManager(
    private val config: PromptConfig,
) {
    private val logger = LoggerFactory.getLogger(PromptManager::class.java)

    companion object {
        private const val SYSTEM_FILE = "SYSTEM.md"
        private const val IDENTITY_FILE = "IDENTITY.md"
        private const val USER_FILE = "USER.md"
        internal const val AGENT_FILE = "AGENT.md"
        internal const val MEMORY_FILE = "MEMORY.md"

        private val MANAGED_FILES = listOf(SYSTEM_FILE, IDENTITY_FILE, USER_FILE, AGENT_FILE, MEMORY_FILE)

        private const val RESOURCE_PATH = "/prompts"
    }

    // Cached content
    private var systemContent: String? = null
    private var identityContent: String? = null
    private var userContent: String? = null
    private var cachedFullPrompt: String? = null
    private val templateCache = mutableMapOf<String, PromptTemplate>()

    /**
     * Initialize the prompt manager, creating missing files from templates.
     */
    fun initialize() {
        logger.info("Initializing prompt manager at ${config.promptsDir}")

        // Ensure prompts directory exists
        config.promptsDir.createDirectories()

        // Initialize each prompt file from templates if missing
        if (config.autoCreateMissing) {
            MANAGED_FILES.forEach { initializeFile(it) }
        }

        // Load and cache content
        reload()

        logger.info("Prompt manager initialized")
    }

    /**
     * Reload all prompt files from disk and rebuild the cached prompt.
     */
    fun reload() {
        systemContent = loadFile(SYSTEM_FILE)
        identityContent = loadFile(IDENTITY_FILE)
        userContent = loadFile(USER_FILE)
        cachedFullPrompt = buildFullPromptInternal()
        templateCache.clear()
        logger.debug("Full prompt cached (${cachedFullPrompt?.length ?: 0} chars)")
    }

    /**
     * Get the raw system prompt content.
     */
    fun getSystemPrompt(): String = systemContent ?: ""

    /**
     * Get the raw identity content.
     */
    fun getIdentityContent(): String = identityContent ?: ""

    /**
     * Get the raw user profile content.
     */
    fun getUserContent(): String = userContent ?: ""

    /**
     * Get the complete system prompt with all injected content.
     * Returns the cached prompt built during initialization/reload.
     */
    fun buildFullPrompt(): String = cachedFullPrompt ?: ""

    private fun buildFullPromptInternal(): String {
        return buildString {
            // System prompt (core instructions)
            val system = truncateContent(systemContent)
            if (system.isNotBlank()) {
                append(system)
                appendLine()
            }

            // Identity section
            val identity = truncateContent(identityContent)
            if (identity.isNotBlank()) {
                appendLine()
                appendLine("---")
                appendLine()
                append(identity)
                appendLine()
            }

            // User profile section
            val user = truncateContent(userContent)
            if (user.isNotBlank()) {
                appendLine()
                appendLine("---")
                appendLine()
                append(user)
                appendLine()
            }
        }.trim()
    }

    /**
     * Get the path to a prompt file.
     */
    fun getFilePath(filename: String): Path = config.promptsDir.resolve(filename)

    /**
     * Check if a prompt file exists.
     */
    fun fileExists(filename: String): Boolean = getFilePath(filename).exists()

    private fun initializeFile(filename: String) {
        val filePath = getFilePath(filename)

        if (filePath.exists()) {
            logger.debug("Prompt file exists: $filename")
            return
        }

        // Load template from resources
        val template = loadFromResources(filename)
        if (template == null) {
            logger.warn("Template resource not found: $filename")
            return
        }

        // Try to write template to file
        try {
            filePath.writeText(template)
            logger.info("Created prompt file from template: $filename")
        } catch (e: Exception) {
            logger.warn("Failed to write prompt file $filename to workspace: ${e.message}")
            logger.warn("Will use bundled resource for $filename")
        }
    }

    /**
     * Load a prompt file, falling back to JAR resources if workspace file is unavailable.
     */
    private fun loadFile(filename: String): String? {
        val filePath = getFilePath(filename)

        // Try to load from workspace first
        if (filePath.exists()) {
            try {
                val content = filePath.readText()
                logger.debug("Loaded prompt file from workspace: $filename (${content.length} chars)")
                return content
            } catch (e: Exception) {
                logger.warn("Failed to read prompt file $filename from workspace: ${e.message}")
                logger.warn("Falling back to bundled resource for $filename")
            }
        }

        // Fall back to JAR resources
        val resourceContent = loadFromResources(filename)
        if (resourceContent != null) {
            if (filePath.exists()) {
                // File exists but couldn't be read - already logged above
            } else {
                logger.info("Prompt file $filename not found in workspace, using bundled resource")
            }
            return resourceContent
        }

        logger.error("No prompt content available for $filename (not in workspace or resources)")
        return null
    }

    /**
     * Load content from JAR resources.
     */
    private fun loadFromResources(filename: String): String? {
        val resourcePath = "$RESOURCE_PATH/$filename"
        return try {
            javaClass.getResourceAsStream(resourcePath)
                ?.bufferedReader()
                ?.use { it.readText() }
        } catch (e: Exception) {
            logger.error("Failed to load resource $resourcePath: ${e.message}")
            null
        }
    }

    private fun truncateContent(content: String?): String {
        if (content.isNullOrBlank()) return ""

        val cleaned = stripHtmlComments(content)

        return if (cleaned.length > config.maxFileChars) {
            cleaned.take(config.maxFileChars) + "\n\n[... content truncated ...]"
        } else {
            cleaned
        }
    }

    /**
     * Load and parse a prompt file as a [PromptTemplate] with sections.
     * Returns null if the file cannot be loaded.
     * Results are cached until [reload] is called.
     */
    fun getTemplate(filename: String): PromptTemplate? {
        templateCache[filename]?.let { return it }

        val raw = loadFile(filename) ?: return null
        val cleaned = stripHtmlComments(raw)
        val template = PromptTemplate.parse(cleaned)
        templateCache[filename] = template
        return template
    }

    /**
     * Strip HTML comments from content.
     * Comments may span multiple lines.
     */
    internal fun stripHtmlComments(content: String): String {
        return content
            .replace(Regex("<!--[\\s\\S]*?-->"), "")
            .replace(Regex("\n{3,}"), "\n\n") // Collapse multiple blank lines
            .trim()
    }
}
