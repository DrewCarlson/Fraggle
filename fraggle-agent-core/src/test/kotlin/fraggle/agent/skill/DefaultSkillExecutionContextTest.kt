package fraggle.agent.skill

import kotlinx.coroutines.test.runTest
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

class DefaultSkillExecutionContextTest {

    private fun makeSkill(baseDir: Path, name: String = "test-skill"): Skill {
        baseDir.createDirectories()
        baseDir.resolve("SKILL.md").writeText(
            """
            ---
            name: $name
            description: Test
            env: ['SECRET_A', 'SECRET_B']
            ---
            # Test
            """.trimIndent(),
        )
        return Skill(
            name = name,
            description = "Test",
            filePath = baseDir.resolve("SKILL.md"),
            baseDir = baseDir,
            source = SkillSource.GLOBAL,
            disableModelInvocation = false,
            frontmatter = SkillFrontmatter(
                name = name,
                description = "Test",
                env = listOf("SECRET_A", "SECRET_B"),
            ),
        )
    }

    @Nested
    inner class ResolveEnvironment {

        @Test
        fun `returns null for unknown skill`(@TempDir tmp: Path) = runTest {
            val ctx = DefaultSkillExecutionContext(
                registry = InMemorySkillRegistry(emptyList()),
                secretsStore = SkillSecretsStore(tmp.resolve("secrets")),
                venvManager = SkillVenvManager(tmp.resolve("venvs")),
            )
            assertNull(ctx.resolveEnvironment("nonexistent"))
        }

        @Test
        fun `injects secrets as env vars`(@TempDir tmp: Path) = runTest {
            val skillDir = tmp.resolve("skills/my-skill")
            val skill = makeSkill(skillDir, "my-skill")
            val secretsDir = tmp.resolve("secrets")
            val secretsStore = SkillSecretsStore(secretsDir)
            secretsStore.set("my-skill", "SECRET_A", "val-a")
            secretsStore.set("my-skill", "SECRET_B", "val-b")

            val ctx = DefaultSkillExecutionContext(
                registry = InMemorySkillRegistry(listOf(skill)),
                secretsStore = secretsStore,
                venvManager = SkillVenvManager(tmp.resolve("venvs")),
            )

            val env = ctx.resolveEnvironment("my-skill")
            assertNotNull(env)
            assertEquals("val-a", env.envVars["SECRET_A"])
            assertEquals("val-b", env.envVars["SECRET_B"])
            assertEquals(skillDir, env.workDir)
        }

        @Test
        fun `no venv when skill has no requirements`(@TempDir tmp: Path) = runTest {
            val skillDir = tmp.resolve("skills/my-skill")
            val skill = makeSkill(skillDir, "my-skill")

            val ctx = DefaultSkillExecutionContext(
                registry = InMemorySkillRegistry(listOf(skill)),
                secretsStore = SkillSecretsStore(tmp.resolve("secrets")),
                venvManager = SkillVenvManager(tmp.resolve("venvs")),
            )

            val env = ctx.resolveEnvironment("my-skill")
            assertNotNull(env)
            // No requirements.txt → no venv created
            assertNull(env.venvBinDir)
            assertTrue("VIRTUAL_ENV" !in env.envVars)
        }

        @Test
        fun `auto-creates venv when skill has requirements`(@TempDir tmp: Path) = runTest {
            val skillDir = tmp.resolve("skills/my-skill")
            val skill = makeSkill(skillDir, "my-skill")
            // Create a requirements.txt so hasPythonDeps is true
            skillDir.resolve("requirements.txt").writeText("requests>=2.28.0\n")

            val venvManager = SkillVenvManager(tmp.resolve("venvs"))

            val ctx = DefaultSkillExecutionContext(
                registry = InMemorySkillRegistry(listOf(skill)),
                secretsStore = SkillSecretsStore(tmp.resolve("secrets")),
                venvManager = venvManager,
            )

            // Venv doesn't exist yet
            assertTrue(!venvManager.isSetUp("my-skill"))

            val env = ctx.resolveEnvironment("my-skill")
            assertNotNull(env)
            // Venv should now be auto-created
            assertNotNull(env.venvBinDir)
            assertTrue("VIRTUAL_ENV" in env.envVars)
            assertTrue(venvManager.isSetUp("my-skill"))
        }

        @Test
        fun `empty env vars when no secrets configured`(@TempDir tmp: Path) = runTest {
            val skillDir = tmp.resolve("skills/my-skill")
            val skill = makeSkill(skillDir, "my-skill")

            val ctx = DefaultSkillExecutionContext(
                registry = InMemorySkillRegistry(listOf(skill)),
                secretsStore = SkillSecretsStore(tmp.resolve("secrets")),
                venvManager = SkillVenvManager(tmp.resolve("venvs")),
            )

            val env = ctx.resolveEnvironment("my-skill")
            assertNotNull(env)
            assertTrue(env.envVars.isEmpty())
        }

        @Test
        fun `workDir is skill baseDir`(@TempDir tmp: Path) = runTest {
            val skillDir = tmp.resolve("skills/my-skill")
            val skill = makeSkill(skillDir, "my-skill")

            val ctx = DefaultSkillExecutionContext(
                registry = InMemorySkillRegistry(listOf(skill)),
                secretsStore = SkillSecretsStore(tmp.resolve("secrets")),
                venvManager = SkillVenvManager(tmp.resolve("venvs")),
            )

            val env = ctx.resolveEnvironment("my-skill")
            assertNotNull(env)
            assertEquals(skillDir, env.workDir)
        }
    }
}
