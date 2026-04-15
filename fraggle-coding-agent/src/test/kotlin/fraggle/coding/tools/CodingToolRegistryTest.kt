package fraggle.coding.tools

import fraggle.executor.LocalToolExecutor
import fraggle.executor.supervision.ToolArgKind
import io.ktor.client.HttpClient
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodingToolRegistryTest {

    private fun build(
        dir: Path,
        enabledTools: Set<String>? = null,
    ): CodingToolRegistry.Built = CodingToolRegistry.build(
        toolExecutor = LocalToolExecutor(dir),
        httpClient = HttpClient(),
        playwrightFetcher = null,
        enabledTools = enabledTools,
    )

    @Nested
    inner class Composition {
        @Test
        fun `default build includes the generic base tools plus edit_file`(@TempDir dir: Path) {
            val built = build(dir)
            val names = built.registry.tools.map { it.name }.toSet()

            // Base filesystem/shell/time tools from DefaultTools
            assertTrue("read_file" in names, "missing read_file: $names")
            assertTrue("write_file" in names, "missing write_file: $names")
            assertTrue("list_files" in names, "missing list_files: $names")
            assertTrue("search_files" in names, "missing search_files: $names")
            assertTrue("execute_command" in names, "missing execute_command: $names")
            assertTrue("get_current_time" in names, "missing get_current_time: $names")

            // Coding-agent's own edit_file
            assertTrue("edit_file" in names, "missing edit_file: $names")
        }

        @Test
        fun `default build does NOT include scheduling tools`(@TempDir dir: Path) {
            val built = build(dir)
            val names = built.registry.tools.map { it.name }.toSet()
            // Scheduling lives in fraggle-assistant now; coding agent must
            // not see them even transitively. This is the load-bearing check
            // for the pre-work decoupling.
            assertFalse("schedule_task" in names, "scheduling tool leaked into coding registry")
            assertFalse("list_tasks" in names)
            assertFalse("cancel_task" in names)
            assertFalse("get_task" in names)
        }

        @Test
        fun `edit_file is the instance exposed by this module`(@TempDir dir: Path) {
            val built = build(dir)
            val editFile = built.registry.tools.firstOrNull { it.name == "edit_file" }
            assertNotNull(editFile, "edit_file should be in the registry")
            assertTrue(editFile is EditFileTool, "edit_file should be our EditFileTool, got ${editFile.javaClass}")
        }
    }

    @Nested
    inner class Filtering {
        @Test
        fun `null enabledTools means all tools pass through`(@TempDir dir: Path) {
            val unfiltered = build(dir, enabledTools = null)
            val allNames = unfiltered.registry.tools.map { it.name }

            // Simple sanity: we get more than just the coding-agent's own tool
            assertTrue(allNames.size > 1)
            assertTrue("edit_file" in allNames)
        }

        @Test
        fun `explicit subset filters the registry to just those tools`(@TempDir dir: Path) {
            val built = build(dir, enabledTools = setOf("read_file", "edit_file"))
            val names = built.registry.tools.map { it.name }.toSet()
            assertEquals(setOf("read_file", "edit_file"), names)
        }

        @Test
        fun `empty set filters out everything`(@TempDir dir: Path) {
            val built = build(dir, enabledTools = emptySet())
            assertEquals(emptyList(), built.registry.tools)
        }

        @Test
        fun `unknown tool names in the set are silently ignored`(@TempDir dir: Path) {
            val built = build(dir, enabledTools = setOf("read_file", "not_a_real_tool"))
            val names = built.registry.tools.map { it.name }
            assertEquals(listOf("read_file"), names)
        }
    }

    @Nested
    inner class ArgTypes {
        @Test
        fun `edit_file path argument is registered as PATH`(@TempDir dir: Path) {
            val built = build(dir)
            val editArgs = built.argTypes.types["edit_file"]
            assertNotNull(editArgs, "edit_file should have arg type entries")
            assertEquals(ToolArgKind.PATH, editArgs["path"], "edit_file.path should be a PATH arg")
        }

        @Test
        fun `base tool arg types are preserved`(@TempDir dir: Path) {
            val built = build(dir)
            // read_file already has a PATH arg registered via DefaultTools; confirm it survived
            // being merged with the coding-agent additions.
            val readArgs = built.argTypes.types["read_file"]
            assertNotNull(readArgs, "read_file arg types should still be present after merging")
            assertEquals(ToolArgKind.PATH, readArgs["path"])
        }
    }
}
