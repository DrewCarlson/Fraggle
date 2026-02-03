package org.drewcarlson.fraggle.prompt

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptManagerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var promptsDir: Path
    private lateinit var config: PromptConfig
    private lateinit var manager: PromptManager

    @BeforeEach
    fun setup() {
        promptsDir = tempDir.resolve("prompts")
        config = PromptConfig(
            promptsDir = promptsDir,
            maxFileChars = 20_000,
            autoCreateMissing = true,
        )
        manager = PromptManager(config)
    }

    @Nested
    inner class Initialization {

        @Test
        fun `initialize creates prompts directory`() {
            manager.initialize()
            assertTrue(promptsDir.exists())
        }

        @Test
        fun `initialize creates SYSTEM md from template when missing`() {
            manager.initialize()
            assertTrue(manager.fileExists("SYSTEM.md"))
        }

        @Test
        fun `initialize creates IDENTITY md from template when missing`() {
            manager.initialize()
            assertTrue(manager.fileExists("IDENTITY.md"))
        }

        @Test
        fun `initialize creates USER md from template when missing`() {
            manager.initialize()
            assertTrue(manager.fileExists("USER.md"))
        }

        @Test
        fun `initialize does not overwrite existing files`() {
            promptsDir.createDirectories()
            promptsDir.resolve("SYSTEM.md").writeText("Custom system prompt")

            manager.initialize()

            val content = manager.getSystemPrompt()
            assertEquals("Custom system prompt", content)
        }

        @Test
        fun `initialize with autoCreateMissing false does not create files`() {
            val noAutoConfig = PromptConfig(
                promptsDir = promptsDir,
                autoCreateMissing = false,
            )
            val noAutoManager = PromptManager(noAutoConfig)

            noAutoManager.initialize()

            assertFalse(promptsDir.resolve("SYSTEM.md").exists())
        }
    }

    @Nested
    inner class ContentLoading {

        @Test
        fun `getSystemPrompt returns file content`() {
            promptsDir.createDirectories()
            promptsDir.resolve("SYSTEM.md").writeText("# System\nBe helpful.")

            manager.initialize()

            val content = manager.getSystemPrompt()
            assertTrue(content.contains("Be helpful"))
        }

        @Test
        fun `getIdentityContent returns file content`() {
            promptsDir.createDirectories()
            promptsDir.resolve("IDENTITY.md").writeText("# Identity\nI am Fraggle.")

            manager.initialize()

            val content = manager.getIdentityContent()
            assertTrue(content.contains("I am Fraggle"))
        }

        @Test
        fun `getUserContent returns file content`() {
            promptsDir.createDirectories()
            promptsDir.resolve("USER.md").writeText("# User\nName: Alice")

            manager.initialize()

            val content = manager.getUserContent()
            assertTrue(content.contains("Name: Alice"))
        }

        @Test
        fun `missing file falls back to resource`() {
            val noAutoConfig = PromptConfig(
                promptsDir = promptsDir,
                autoCreateMissing = false,
            )
            val noAutoManager = PromptManager(noAutoConfig)
            noAutoManager.initialize()

            // Even without autoCreateMissing, the manager falls back to JAR resources
            val content = noAutoManager.getSystemPrompt()
            assertTrue(content.isNotBlank(), "Should fall back to JAR resource")
        }
    }

    @Nested
    inner class HtmlCommentStripping {

        @Test
        fun `strips single line HTML comments`() {
            promptsDir.createDirectories()
            promptsDir.resolve("SYSTEM.md").writeText(
                """
                # System
                <!-- This is a comment -->
                Be helpful.
                """.trimIndent()
            )

            manager.initialize()

            val prompt = manager.buildFullPrompt()
            assertFalse(prompt.contains("This is a comment"))
            assertTrue(prompt.contains("Be helpful"))
        }

        @Test
        fun `strips multi-line HTML comments`() {
            promptsDir.createDirectories()
            promptsDir.resolve("SYSTEM.md").writeText(
                """
                # System
                <!--
                This is a
                multi-line comment
                -->
                Be helpful.
                """.trimIndent()
            )

            manager.initialize()

            val prompt = manager.buildFullPrompt()
            assertFalse(prompt.contains("multi-line comment"))
            assertTrue(prompt.contains("Be helpful"))
        }

        @Test
        fun `collapses multiple blank lines after stripping`() {
            promptsDir.createDirectories()
            promptsDir.resolve("SYSTEM.md").writeText(
                """
                # System


                <!-- Comment -->


                Be helpful.
                """.trimIndent()
            )

            manager.initialize()

            val prompt = manager.buildFullPrompt()
            // Should not have 3+ consecutive newlines
            assertFalse(prompt.contains("\n\n\n"))
        }
    }

    @Nested
    inner class ContentTruncation {

        @Test
        fun `truncates content exceeding maxFileChars`() {
            val shortConfig = PromptConfig(
                promptsDir = promptsDir,
                maxFileChars = 50,
                autoCreateMissing = false, // Don't create other files from templates
            )
            val shortManager = PromptManager(shortConfig)

            promptsDir.createDirectories()
            promptsDir.resolve("SYSTEM.md").writeText("A".repeat(100))

            shortManager.initialize()

            val prompt = shortManager.buildFullPrompt()
            // The SYSTEM.md content should be truncated
            assertTrue(prompt.contains("[... content truncated ...]"))
        }

        @Test
        fun `does not truncate content within limit`() {
            promptsDir.createDirectories()
            promptsDir.resolve("SYSTEM.md").writeText("Short content")

            manager.initialize()

            val prompt = manager.buildFullPrompt()
            assertFalse(prompt.contains("truncated"))
        }
    }

    @Nested
    inner class FullPromptBuilding {

        @Test
        fun `buildFullPrompt combines all sections`() {
            promptsDir.createDirectories()
            promptsDir.resolve("SYSTEM.md").writeText("System content")
            promptsDir.resolve("IDENTITY.md").writeText("Identity content")
            promptsDir.resolve("USER.md").writeText("User content")

            manager.initialize()

            val prompt = manager.buildFullPrompt()
            assertTrue(prompt.contains("System content"))
            assertTrue(prompt.contains("Identity content"))
            assertTrue(prompt.contains("User content"))
        }

        @Test
        fun `buildFullPrompt adds dividers between sections`() {
            promptsDir.createDirectories()
            promptsDir.resolve("SYSTEM.md").writeText("System")
            promptsDir.resolve("IDENTITY.md").writeText("Identity")

            manager.initialize()

            val prompt = manager.buildFullPrompt()
            assertTrue(prompt.contains("---"))
        }

        @Test
        fun `buildFullPrompt uses only workspace files when they exist`() {
            promptsDir.createDirectories()
            promptsDir.resolve("SYSTEM.md").writeText("System only")
            promptsDir.resolve("IDENTITY.md").writeText("") // Empty file
            promptsDir.resolve("USER.md").writeText("") // Empty file

            val noAutoConfig = PromptConfig(
                promptsDir = promptsDir,
                autoCreateMissing = false,
            )
            val noAutoManager = PromptManager(noAutoConfig)
            noAutoManager.initialize()

            val prompt = noAutoManager.buildFullPrompt()
            // Only SYSTEM.md has content, so no dividers should appear
            assertEquals("System only", prompt)
        }

        @Test
        fun `buildFullPrompt falls back to resources when workspace files missing`() {
            val noAutoConfig = PromptConfig(
                promptsDir = promptsDir,
                autoCreateMissing = false,
            )
            val noAutoManager = PromptManager(noAutoConfig)
            noAutoManager.initialize()

            // Falls back to JAR resources, so not empty
            val prompt = noAutoManager.buildFullPrompt()
            assertTrue(prompt.isNotBlank(), "Should fall back to JAR resources")
        }
    }

    @Nested
    inner class Caching {

        @Test
        fun `buildFullPrompt returns cached value`() {
            promptsDir.createDirectories()
            promptsDir.resolve("SYSTEM.md").writeText("Initial content")

            manager.initialize()
            val first = manager.buildFullPrompt()

            // Modify file (but don't reload)
            promptsDir.resolve("SYSTEM.md").writeText("Modified content")

            val second = manager.buildFullPrompt()

            // Should still be cached value
            assertEquals(first, second)
            assertTrue(second.contains("Initial"))
        }

        @Test
        fun `reload updates cached content`() {
            promptsDir.createDirectories()
            promptsDir.resolve("SYSTEM.md").writeText("Initial content")

            manager.initialize()
            manager.buildFullPrompt()

            // Modify file and reload
            promptsDir.resolve("SYSTEM.md").writeText("Updated content")
            manager.reload()

            val prompt = manager.buildFullPrompt()
            assertTrue(prompt.contains("Updated"))
        }
    }

    @Nested
    inner class FilePathHelpers {

        @Test
        fun `getFilePath returns correct path`() {
            manager.initialize()

            val path = manager.getFilePath("SYSTEM.md")
            assertEquals(promptsDir.resolve("SYSTEM.md"), path)
        }

        @Test
        fun `fileExists returns true for existing file`() {
            promptsDir.createDirectories()
            promptsDir.resolve("test.md").writeText("test")

            manager.initialize()

            assertTrue(manager.fileExists("test.md"))
        }

        @Test
        fun `fileExists returns false for missing file`() {
            manager.initialize()
            assertFalse(manager.fileExists("nonexistent.md"))
        }
    }

    @Nested
    inner class ResourceFallback {

        @Test
        fun `falls back to resources when workspace file unreadable`() {
            // Create a directory with the same name as the file to make it unreadable
            promptsDir.createDirectories()
            promptsDir.resolve("SYSTEM.md").toFile().mkdirs()

            // This should fall back to resource and not crash
            manager.initialize()

            // Should have loaded something from resources
            val prompt = manager.buildFullPrompt()
            assertTrue(prompt.isNotBlank())
        }
    }
}
