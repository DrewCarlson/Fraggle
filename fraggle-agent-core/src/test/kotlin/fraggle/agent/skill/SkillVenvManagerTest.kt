package fraggle.agent.skill

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillVenvManagerTest {

    private fun makeSkill(baseDir: Path, name: String = "test-skill"): Skill {
        baseDir.createDirectories()
        val skillFile = baseDir.resolve("SKILL.md")
        skillFile.writeText(
            """
            ---
            name: $name
            description: Test skill
            ---
            # Test
            """.trimIndent(),
        )
        return Skill(
            name = name,
            description = "Test skill",
            filePath = skillFile,
            baseDir = baseDir,
            source = SkillSource.GLOBAL,
            disableModelInvocation = false,
            frontmatter = SkillFrontmatter(name = name, description = "Test skill"),
        )
    }

    @Nested
    inner class Paths {

        @Test
        fun `venvPath resolves under venvsRoot`(@TempDir tmp: Path) {
            val manager = SkillVenvManager(tmp)
            assertEquals(tmp.resolve("my-skill"), manager.venvPath("my-skill"))
        }

        @Test
        fun `pythonPath points to bin python3`(@TempDir tmp: Path) {
            val manager = SkillVenvManager(tmp)
            assertEquals(tmp.resolve("my-skill/bin/python3"), manager.pythonPath("my-skill"))
        }

        @Test
        fun `binDir points to bin directory`(@TempDir tmp: Path) {
            val manager = SkillVenvManager(tmp)
            assertEquals(tmp.resolve("my-skill/bin"), manager.binDir("my-skill"))
        }
    }

    @Nested
    inner class IsSetUp {

        @Test
        fun `returns false when venv does not exist`(@TempDir tmp: Path) {
            val manager = SkillVenvManager(tmp)
            assertFalse(manager.isSetUp("my-skill"))
        }
    }

    @Nested
    inner class Setup {

        @Test
        fun `creates venv with python3`(@TempDir tmp: Path) = runTest {
            val venvsDir = tmp.resolve("venvs")
            val skillDir = tmp.resolve("skill")
            val manager = SkillVenvManager(venvsDir)
            val skill = makeSkill(skillDir)

            val result = manager.setup(skill)

            assertTrue(result.success, "Setup failed: ${result.output}")
            assertTrue(manager.isSetUp(skill.name), "python3 binary not found after setup")
        }

        @Test
        fun `installs requirements when present`(@TempDir tmp: Path) = runTest {
            val venvsDir = tmp.resolve("venvs")
            val skillDir = tmp.resolve("skill")
            val manager = SkillVenvManager(venvsDir)

            // Use a trivially available package
            skillDir.createDirectories()
            skillDir.resolve("requirements.txt").writeText("pip\n")
            val skill = makeSkill(skillDir)

            val result = manager.setup(skill)

            assertTrue(result.success, "Setup with requirements failed: ${result.output}")
            assertTrue(manager.isSetUp(skill.name))
        }

        @Test
        fun `skips pip install when no requirements txt`(@TempDir tmp: Path) = runTest {
            val venvsDir = tmp.resolve("venvs")
            val skillDir = tmp.resolve("skill")
            val manager = SkillVenvManager(venvsDir)
            val skill = makeSkill(skillDir)

            val result = manager.setup(skill)

            assertTrue(result.success, "Setup without requirements failed: ${result.output}")
        }
    }

    @Nested
    inner class Remove {

        @Test
        fun `removes existing venv`(@TempDir tmp: Path) = runTest {
            val venvsDir = tmp.resolve("venvs")
            val skillDir = tmp.resolve("skill")
            val manager = SkillVenvManager(venvsDir)
            val skill = makeSkill(skillDir)

            manager.setup(skill)
            assertTrue(manager.isSetUp(skill.name))

            manager.remove(skill.name)
            assertFalse(manager.isSetUp(skill.name))
            assertFalse(manager.venvPath(skill.name).exists())
        }

        @Test
        fun `remove is no-op for nonexistent venv`(@TempDir tmp: Path) {
            val manager = SkillVenvManager(tmp)
            manager.remove("nonexistent") // should not throw
        }
    }

    @Nested
    inner class Rebuild {

        @Test
        fun `rebuild recreates venv`(@TempDir tmp: Path) = runTest {
            val venvsDir = tmp.resolve("venvs")
            val skillDir = tmp.resolve("skill")
            val manager = SkillVenvManager(venvsDir)
            val skill = makeSkill(skillDir)

            manager.setup(skill)
            assertTrue(manager.isSetUp(skill.name))

            val result = manager.rebuild(skill)
            assertTrue(result.success, "Rebuild failed: ${result.output}")
            assertTrue(manager.isSetUp(skill.name))
        }
    }
}
