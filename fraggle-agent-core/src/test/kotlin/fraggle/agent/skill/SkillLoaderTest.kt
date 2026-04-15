package fraggle.agent.skill

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillLoaderTest {

    private fun writeSkill(root: Path, dirName: String, content: String): Path {
        val dir = root.resolve(dirName)
        dir.createDirectories()
        val file = dir.resolve("SKILL.md")
        file.writeText(content)
        return file
    }

    @Nested
    inner class Valid {

        @Test
        fun `loads valid skill`(@TempDir tmp: Path) {
            writeSkill(
                tmp,
                "code-review",
                """
                ---
                name: code-review
                description: Review code changes for correctness and style.
                ---

                # Code Review

                Body of the skill.
                """.trimIndent(),
            )

            val result = SkillLoader().loadFromDirectory(tmp, SkillSource.PROJECT)

            assertEquals(1, result.skills.size)
            val skill = result.skills.single()
            assertEquals("code-review", skill.name)
            assertEquals("Review code changes for correctness and style.", skill.description)
            assertEquals(SkillSource.PROJECT, skill.source)
            assertTrue(skill.filePath.endsWith("SKILL.md"))
            assertTrue(result.diagnostics.isEmpty())
        }

        @Test
        fun `normalizes multiline description to single line`(@TempDir tmp: Path) {
            writeSkill(
                tmp,
                "multi",
                """
                ---
                name: multi
                description: |
                  This is a multiline description.
                  It spans multiple lines.
                ---
                """.trimIndent(),
            )

            val result = SkillLoader().loadFromDirectory(tmp, SkillSource.PROJECT)

            assertEquals(
                "This is a multiline description. It spans multiple lines.",
                result.skills.single().description,
            )
        }

        @Test
        fun `parses disable-model-invocation flag`(@TempDir tmp: Path) {
            writeSkill(
                tmp,
                "hidden",
                """
                ---
                name: hidden
                description: Hidden skill only invoked explicitly.
                disable-model-invocation: true
                ---
                """.trimIndent(),
            )

            val result = SkillLoader().loadFromDirectory(tmp, SkillSource.PROJECT)

            assertTrue(result.skills.single().disableModelInvocation)
        }

        @Test
        fun `accepts unknown frontmatter fields`(@TempDir tmp: Path) {
            writeSkill(
                tmp,
                "extra",
                """
                ---
                name: extra
                description: Skill with unknown fields.
                author: someone
                version: 1.0
                ---
                """.trimIndent(),
            )

            val result = SkillLoader().loadFromDirectory(tmp, SkillSource.PROJECT)

            assertEquals(1, result.skills.size)
        }
    }

    @Nested
    inner class Invalid {

        @Test
        fun `skips skill with missing description`(@TempDir tmp: Path) {
            writeSkill(
                tmp,
                "no-desc",
                """
                ---
                name: no-desc
                ---

                # Body only
                """.trimIndent(),
            )

            val result = SkillLoader().loadFromDirectory(tmp, SkillSource.PROJECT)

            assertTrue(result.skills.isEmpty())
            assertTrue(result.diagnostics.any { it is SkillDiagnostic.Error })
        }

        @Test
        fun `skips skill with no frontmatter`(@TempDir tmp: Path) {
            writeSkill(tmp, "plain", "# No Frontmatter\n\nJust a body.\n")

            val result = SkillLoader().loadFromDirectory(tmp, SkillSource.PROJECT)

            assertTrue(result.skills.isEmpty())
            assertTrue(result.diagnostics.any { it is SkillDiagnostic.Error })
        }

        @Test
        fun `warns on invalid name but still loads`(@TempDir tmp: Path) {
            writeSkill(
                tmp,
                "Invalid_Name",
                """
                ---
                name: Invalid_Name
                description: Bad name characters.
                ---
                """.trimIndent(),
            )

            val result = SkillLoader().loadFromDirectory(tmp, SkillSource.PROJECT)

            assertEquals(1, result.skills.size)
            assertTrue(result.diagnostics.any { it is SkillDiagnostic.Warning })
        }

        @Test
        fun `warns on consecutive hyphens`(@TempDir tmp: Path) {
            writeSkill(
                tmp,
                "bad--name",
                """
                ---
                name: bad--name
                description: Consecutive hyphens.
                ---
                """.trimIndent(),
            )

            val result = SkillLoader().loadFromDirectory(tmp, SkillSource.PROJECT)

            assertEquals(1, result.skills.size)
            assertTrue(
                result.diagnostics
                    .filterIsInstance<SkillDiagnostic.Warning>()
                    .any { "consecutive hyphens" in it.message },
            )
        }
    }

    @Nested
    inner class Discovery {

        @Test
        fun `finds nested skill when parent has no SKILL_md`(@TempDir tmp: Path) {
            writeSkill(
                tmp,
                "category/child-skill",
                """
                ---
                name: child-skill
                description: Nested under a category directory.
                ---
                """.trimIndent(),
            )

            val result = SkillLoader().loadFromDirectory(tmp, SkillSource.GLOBAL)

            assertEquals(1, result.skills.size)
            assertEquals("child-skill", result.skills.single().name)
        }

        @Test
        fun `does not recurse past a SKILL_md root`(@TempDir tmp: Path) {
            writeSkill(
                tmp,
                "root",
                """
                ---
                name: root
                description: Root skill.
                ---
                """.trimIndent(),
            )
            // Nested SKILL.md under the root must be ignored.
            writeSkill(
                tmp.resolve("root"),
                "nested",
                """
                ---
                name: nested
                description: Should be ignored.
                ---
                """.trimIndent(),
            )

            val result = SkillLoader().loadFromDirectory(tmp, SkillSource.PROJECT)

            assertEquals(1, result.skills.size)
            assertEquals("root", result.skills.single().name)
        }

        @Test
        fun `returns empty for non-existent directory`(@TempDir tmp: Path) {
            val result = SkillLoader().loadFromDirectory(tmp.resolve("missing"), SkillSource.PROJECT)
            assertTrue(result.skills.isEmpty())
            assertTrue(result.diagnostics.isEmpty())
        }

        @Test
        fun `loadFromFile parses a single SKILL_md`(@TempDir tmp: Path) {
            val file = writeSkill(
                tmp,
                "single",
                """
                ---
                name: single
                description: A single skill loaded directly.
                ---
                """.trimIndent(),
            )

            val result = SkillLoader().loadFromFile(file, SkillSource.EXPLICIT)

            assertEquals(1, result.skills.size)
            assertEquals(SkillSource.EXPLICIT, result.skills.single().source)
        }
    }

    @Nested
    inner class RegistryPrecedence {

        @Test
        fun `project source overrides global on name collision`() {
            val global = Skill(
                name = "shared",
                description = "Global version",
                filePath = Path.of("/g/shared/SKILL.md"),
                baseDir = Path.of("/g/shared"),
                source = SkillSource.GLOBAL,
                disableModelInvocation = false,
                frontmatter = SkillFrontmatter(name = "shared", description = "Global version"),
            )
            val project = global.copy(
                description = "Project version",
                filePath = Path.of("/p/shared/SKILL.md"),
                baseDir = Path.of("/p/shared"),
                source = SkillSource.PROJECT,
                frontmatter = SkillFrontmatter(name = "shared", description = "Project version"),
            )

            val registry = InMemorySkillRegistry(listOf(global, project))

            assertEquals(1, registry.skills.size)
            assertEquals("Project version", registry.findByName("shared")?.description)
        }

        @Test
        fun `explicit source beats project`() {
            val project = Skill(
                name = "shared",
                description = "Project",
                filePath = Path.of("/p/shared/SKILL.md"),
                baseDir = Path.of("/p/shared"),
                source = SkillSource.PROJECT,
                disableModelInvocation = false,
                frontmatter = SkillFrontmatter(name = "shared", description = "Project"),
            )
            val explicit = project.copy(
                description = "Explicit",
                source = SkillSource.EXPLICIT,
                frontmatter = SkillFrontmatter(name = "shared", description = "Explicit"),
            )

            val registry = InMemorySkillRegistry(listOf(project, explicit))

            assertEquals("Explicit", registry.findByName("shared")?.description)
        }

        @Test
        fun `visibleToModel filters disabled skills`() {
            val visible = Skill(
                name = "visible",
                description = "Visible",
                filePath = Path.of("/a/visible/SKILL.md"),
                baseDir = Path.of("/a/visible"),
                source = SkillSource.PROJECT,
                disableModelInvocation = false,
                frontmatter = SkillFrontmatter(name = "visible", description = "Visible"),
            )
            val hidden = visible.copy(
                name = "hidden",
                filePath = Path.of("/a/hidden/SKILL.md"),
                baseDir = Path.of("/a/hidden"),
                disableModelInvocation = true,
                frontmatter = SkillFrontmatter(name = "hidden", description = "Visible"),
            )

            val registry = InMemorySkillRegistry(listOf(visible, hidden))

            assertEquals(listOf("visible"), registry.visibleToModel().map { it.name })
            assertNotNull(registry.findByName("hidden"))
        }

        @Test
        fun `empty registry returns null and empty lists`() {
            assertNull(EmptySkillRegistry.findByName("anything"))
            assertTrue(EmptySkillRegistry.skills.isEmpty())
            assertTrue(EmptySkillRegistry.visibleToModel().isEmpty())
        }
    }
}
