package fraggle.coding.context

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentsFileLoaderTest {

    @Nested
    inner class SingleDirectory {
        @Test
        fun `empty project returns nothing`(@TempDir dir: Path) {
            val project = dir.resolve("proj").also { it.createDirectories() }
            // Pretend it's a git root so the walk stops cleanly
            project.resolve(".git").createDirectories()

            val loader = AgentsFileLoader(cwd = project)
            assertEquals(emptyList(), loader.load())
        }

        @Test
        fun `cwd AGENTS-md is picked up`(@TempDir dir: Path) {
            val project = dir.resolve("proj").also { it.createDirectories() }
            project.resolve(".git").createDirectories()
            project.resolve("AGENTS.md").writeText("project rules")

            val loaded = AgentsFileLoader(cwd = project).load()
            assertEquals(1, loaded.size)
            assertEquals("project rules", loaded.single().content)
            assertEquals(LoadedContextFile.Source.PROJECT, loaded.single().source)
        }

        @Test
        fun `CLAUDE-md is used as fallback when AGENTS-md is missing`(@TempDir dir: Path) {
            val project = dir.resolve("proj").also { it.createDirectories() }
            project.resolve(".git").createDirectories()
            project.resolve("CLAUDE.md").writeText("claude-code conventions")

            val loaded = AgentsFileLoader(cwd = project).load()
            assertEquals(1, loaded.size)
            assertEquals("claude-code conventions", loaded.single().content)
            assertTrue(loaded.single().path.toString().endsWith("CLAUDE.md"))
        }

        @Test
        fun `AGENTS-md wins over CLAUDE-md when both exist`(@TempDir dir: Path) {
            val project = dir.resolve("proj").also { it.createDirectories() }
            project.resolve(".git").createDirectories()
            project.resolve("AGENTS.md").writeText("agents wins")
            project.resolve("CLAUDE.md").writeText("claude loses")

            val loaded = AgentsFileLoader(cwd = project).load()
            assertEquals(1, loaded.size)
            assertEquals("agents wins", loaded.single().content)
        }
    }

    @Nested
    inner class WalkingUp {
        @Test
        fun `walk collects every AGENTS-md from cwd up to git root in outer-to-inner order`(@TempDir dir: Path) {
            // Layout:
            //   proj/              <- .git lives here (project root)
            //     AGENTS.md ("root")
            //     pkg/
            //       AGENTS.md ("pkg")
            //       sub/
            //         AGENTS.md ("sub")   <- cwd
            val project = dir.resolve("proj").also { it.createDirectories() }
            project.resolve(".git").createDirectories()
            project.resolve("AGENTS.md").writeText("root")

            val pkg = project.resolve("pkg").also { it.createDirectories() }
            pkg.resolve("AGENTS.md").writeText("pkg")

            val sub = pkg.resolve("sub").also { it.createDirectories() }
            sub.resolve("AGENTS.md").writeText("sub")

            val loaded = AgentsFileLoader(cwd = sub).load()
            assertEquals(listOf("root", "pkg", "sub"), loaded.map { it.content })
        }

        @Test
        fun `walk stops at git root, does not escape above it`(@TempDir dir: Path) {
            // Layout:
            //   outer/
            //     AGENTS.md ("outer — should NOT appear")
            //     proj/
            //       .git/
            //       AGENTS.md ("root")
            //       sub/         <- cwd (no AGENTS.md)
            val outer = dir.resolve("outer").also { it.createDirectories() }
            outer.resolve("AGENTS.md").writeText("outer — should NOT appear")

            val project = outer.resolve("proj").also { it.createDirectories() }
            project.resolve(".git").createDirectories()
            project.resolve("AGENTS.md").writeText("root")

            val sub = project.resolve("sub").also { it.createDirectories() }

            val loaded = AgentsFileLoader(cwd = sub).load()
            assertEquals(listOf("root"), loaded.map { it.content })
        }

        @Test
        fun `walk stops at filesystem root if no git directory is ever found`(@TempDir dir: Path) {
            // No .git anywhere. Walker should still terminate at filesystem root.
            val project = dir.resolve("proj").also { it.createDirectories() }
            val sub = project.resolve("sub").also { it.createDirectories() }
            sub.resolve("AGENTS.md").writeText("sub only")

            val loaded = AgentsFileLoader(cwd = sub).load()
            // At least sub/AGENTS.md was found. There may or may not be ancestor files
            // depending on the test environment; we only assert the sub entry is present.
            assertTrue(loaded.any { it.content == "sub only" })
        }
    }

    @Nested
    inner class GlobalFile {
        @Test
        fun `global AGENTS-md is prepended before project files`(@TempDir dir: Path) {
            val project = dir.resolve("proj").also { it.createDirectories() }
            project.resolve(".git").createDirectories()
            project.resolve("AGENTS.md").writeText("project")

            val global = dir.resolve("global-AGENTS.md")
            global.writeText("global")

            val loaded = AgentsFileLoader(cwd = project, globalAgentsFile = global).load()
            assertEquals(listOf("global", "project"), loaded.map { it.content })
            assertEquals(LoadedContextFile.Source.GLOBAL, loaded[0].source)
            assertEquals(LoadedContextFile.Source.PROJECT, loaded[1].source)
        }

        @Test
        fun `missing global file is silently ignored`(@TempDir dir: Path) {
            val project = dir.resolve("proj").also { it.createDirectories() }
            project.resolve(".git").createDirectories()
            project.resolve("AGENTS.md").writeText("project only")

            val loaded = AgentsFileLoader(
                cwd = project,
                globalAgentsFile = dir.resolve("does-not-exist.md"),
            ).load()
            assertEquals(listOf("project only"), loaded.map { it.content })
        }

        @Test
        fun `global file works even if project has no AGENTS-md`(@TempDir dir: Path) {
            val project = dir.resolve("proj").also { it.createDirectories() }
            project.resolve(".git").createDirectories()

            val global = dir.resolve("global.md").also { it.writeText("global only") }

            val loaded = AgentsFileLoader(cwd = project, globalAgentsFile = global).load()
            assertEquals(listOf("global only"), loaded.map { it.content })
        }
    }
}
