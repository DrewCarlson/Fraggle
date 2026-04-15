package fraggle.coding.context

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemPromptBuilderTest {

    @Nested
    inner class MinimalInputs {
        @Test
        fun `base prompt only — no sections`() {
            val result = SystemPromptBuilder.build(basePrompt = "You are a coder.")
            assertEquals("You are a coder.\n", result)
        }

        @Test
        fun `trailing whitespace in base prompt is normalized`() {
            val result = SystemPromptBuilder.build(basePrompt = "base\n\n\n")
            // One trailing newline only
            assertTrue(result.endsWith("base\n"))
            assertFalse(result.endsWith("base\n\n"))
        }
    }

    @Nested
    inner class Workspace {
        @Test
        fun `cwd appears in workspace section`() {
            val cwd = Paths.get("/tmp/proj")
            val result = SystemPromptBuilder.build(
                basePrompt = "base",
                workspace = WorkspaceSnapshot(cwd = cwd),
            )
            assertTrue(result.contains("## Workspace"), "has workspace heading")
            assertTrue(result.contains("/tmp/proj"), "has cwd path")
        }

        @Test
        fun `git fields render when present`() {
            val result = SystemPromptBuilder.build(
                basePrompt = "base",
                workspace = WorkspaceSnapshot(
                    cwd = Paths.get("/tmp/proj"),
                    gitBranch = "main",
                    gitHead = "abc1234",
                    gitStatusShort = " M src/Foo.kt\n?? new.txt",
                ),
            )
            assertTrue(result.contains("Git branch: `main`"))
            assertTrue(result.contains("Git HEAD: `abc1234`"))
            assertTrue(result.contains("Git status (short):"))
            assertTrue(result.contains(" M src/Foo.kt"))
            assertTrue(result.contains("?? new.txt"))
        }

        @Test
        fun `git fields are skipped individually when null`() {
            val result = SystemPromptBuilder.build(
                basePrompt = "base",
                workspace = WorkspaceSnapshot(cwd = Paths.get("/tmp/proj"), gitBranch = "main"),
            )
            assertTrue(result.contains("Git branch: `main`"))
            assertFalse(result.contains("Git HEAD"))
            assertFalse(result.contains("Git status"))
        }

        @Test
        fun `no workspace section at all when workspace is null`() {
            val result = SystemPromptBuilder.build(
                basePrompt = "base",
                workspace = null,
            )
            assertFalse(result.contains("## Workspace"))
        }
    }

    @Nested
    inner class ContextFiles {
        @Test
        fun `files appear in provided order and include the path`() {
            val files = listOf(
                ctx("/global/AGENTS.md", "GLOBAL RULES", LoadedContextFile.Source.GLOBAL),
                ctx("/proj/AGENTS.md", "PROJECT RULES", LoadedContextFile.Source.PROJECT),
                ctx("/proj/sub/AGENTS.md", "SUB RULES", LoadedContextFile.Source.PROJECT),
            )
            val result = SystemPromptBuilder.build(
                basePrompt = "base",
                contextFiles = files,
            )
            assertTrue(result.contains("## Context from AGENTS.md files"))

            val globalIdx = result.indexOf("GLOBAL RULES")
            val projectIdx = result.indexOf("PROJECT RULES")
            val subIdx = result.indexOf("SUB RULES")
            assertTrue(globalIdx >= 0 && projectIdx >= 0 && subIdx >= 0)
            assertTrue(globalIdx < projectIdx, "global should come before project")
            assertTrue(projectIdx < subIdx, "project should come before sub")

            assertTrue(result.contains("/global/AGENTS.md"))
            assertTrue(result.contains("/proj/AGENTS.md"))
            assertTrue(result.contains("/proj/sub/AGENTS.md"))
        }

        @Test
        fun `context section is omitted when list is empty`() {
            val result = SystemPromptBuilder.build(basePrompt = "base", contextFiles = emptyList())
            assertFalse(result.contains("## Context"))
        }
    }

    @Nested
    inner class Templates {
        @Test
        fun `available templates show as a bullet list`() {
            val result = SystemPromptBuilder.build(
                basePrompt = "base",
                availableTemplates = listOf(
                    TemplateDescriptor(name = "review", description = "Review a file for issues"),
                    TemplateDescriptor(name = "refactor"),
                ),
            )
            assertTrue(result.contains("## Available prompt templates"))
            assertTrue(result.contains("- `/review` — Review a file for issues"))
            assertTrue(result.contains("- `/refactor`"))
            // No stray em-dash when description is null
            assertFalse(result.contains("- `/refactor` —"))
        }

        @Test
        fun `template section is omitted when list is empty`() {
            val result = SystemPromptBuilder.build(basePrompt = "base")
            assertFalse(result.contains("## Available prompt templates"))
        }
    }

    @Nested
    inner class AppendText {
        @Test
        fun `appendText is added at the end`() {
            val result = SystemPromptBuilder.build(
                basePrompt = "base",
                appendText = "EXTRA RULES",
            )
            assertTrue(result.trim().endsWith("EXTRA RULES"))
        }

        @Test
        fun `blank appendText is ignored`() {
            val result = SystemPromptBuilder.build(basePrompt = "base", appendText = "   ")
            assertEquals("base\n", result)
        }
    }

    @Nested
    inner class Composition {
        @Test
        fun `full composition has sections in the documented order`() {
            val result = SystemPromptBuilder.build(
                basePrompt = "You are a coder.",
                workspace = WorkspaceSnapshot(cwd = Paths.get("/tmp/proj"), gitBranch = "main"),
                contextFiles = listOf(ctx("/proj/AGENTS.md", "rules", LoadedContextFile.Source.PROJECT)),
                availableTemplates = listOf(TemplateDescriptor("review")),
                appendText = "EXTRA",
            )

            val baseIdx = result.indexOf("You are a coder.")
            val workspaceIdx = result.indexOf("## Workspace")
            val contextIdx = result.indexOf("## Context")
            val templatesIdx = result.indexOf("## Available prompt templates")
            val extraIdx = result.indexOf("EXTRA")

            assertTrue(baseIdx < workspaceIdx)
            assertTrue(workspaceIdx < contextIdx)
            assertTrue(contextIdx < templatesIdx)
            assertTrue(templatesIdx < extraIdx)
        }
    }

    private fun ctx(path: String, content: String, source: LoadedContextFile.Source): LoadedContextFile =
        LoadedContextFile(path = Path.of(path), content = content, source = source)
}
