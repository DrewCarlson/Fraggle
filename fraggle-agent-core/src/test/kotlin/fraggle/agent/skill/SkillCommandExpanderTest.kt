package fraggle.agent.skill

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SkillCommandExpanderTest {

    private fun registry(tmp: Path): Pair<SkillRegistry, Skill> {
        val dir = tmp.resolve("code-review")
        dir.createDirectories()
        val file = dir.resolve("SKILL.md")
        file.writeText(
            """
            ---
            name: code-review
            description: Review code changes for correctness and style.
            ---

            # Code Review

            Step 1. Read the diff.
            Step 2. Check for bugs.
            """.trimIndent(),
        )
        val loader = SkillLoader()
        val result = loader.loadFromDirectory(tmp, SkillSource.PROJECT)
        return InMemorySkillRegistry(result.skills) to result.skills.single()
    }

    @Test
    fun `returns NotASkillCommand for normal text`() {
        val (reg, _) = registry(java.nio.file.Files.createTempDirectory("sk"))
        val result = SkillCommandExpander { reg }.tryExpand("hello world")
        assertEquals(SkillCommandExpander.Result.NotASkillCommand, result)
    }

    @Test
    fun `returns NotASkillCommand for other slash commands`() {
        val (reg, _) = registry(java.nio.file.Files.createTempDirectory("sk"))
        val result = SkillCommandExpander { reg }.tryExpand("/approve")
        assertEquals(SkillCommandExpander.Result.NotASkillCommand, result)
    }

    @Test
    fun `returns UnknownSkill for missing skill`(@TempDir tmp: Path) {
        val (reg, _) = registry(tmp)
        val result = SkillCommandExpander { reg }.tryExpand("/skill:does-not-exist")
        assertIs<SkillCommandExpander.Result.UnknownSkill>(result)
        assertEquals("does-not-exist", result.name)
    }

    @Test
    fun `returns Malformed for bare prefix`(@TempDir tmp: Path) {
        val (reg, _) = registry(tmp)
        val result = SkillCommandExpander { reg }.tryExpand("/skill:")
        assertIs<SkillCommandExpander.Result.MalformedCommand>(result)
    }

    @Test
    fun `expands skill without args`(@TempDir tmp: Path) {
        val (reg, skill) = registry(tmp)

        val result = SkillCommandExpander { reg }.tryExpand("/skill:code-review")

        assertIs<SkillCommandExpander.Result.Expanded>(result)
        assertEquals(skill.name, result.skill.name)
        assertTrue(result.text.startsWith("<skill name=\"code-review\""))
        assertTrue("Step 1. Read the diff." in result.text)
        assertTrue(result.text.trimEnd().endsWith("</skill>"))
        // Frontmatter must be stripped.
        assertTrue("description:" !in result.text)
        assertTrue("---" !in result.text)
    }

    @Test
    fun `preserves trailing args after skill block`(@TempDir tmp: Path) {
        val (reg, _) = registry(tmp)

        val result = SkillCommandExpander { reg }.tryExpand("/skill:code-review please look at main.kt")

        assertIs<SkillCommandExpander.Result.Expanded>(result)
        assertTrue(result.text.contains("please look at main.kt"))
        // Args appear after the closing tag.
        assertTrue(result.text.indexOf("</skill>") < result.text.indexOf("please look at main.kt"))
    }

    @Test
    fun `includes base directory in preamble`(@TempDir tmp: Path) {
        val (reg, skill) = registry(tmp)
        val result = SkillCommandExpander { reg }.tryExpand("/skill:code-review")
        assertIs<SkillCommandExpander.Result.Expanded>(result)
        assertTrue(skill.baseDir.toString() in result.text)
    }

    @Test
    fun `hidden skills are still reachable by explicit command`(@TempDir tmp: Path) {
        val dir = tmp.resolve("secret")
        dir.createDirectories()
        dir.resolve("SKILL.md").writeText(
            """
            ---
            name: secret
            description: Hidden from the catalog.
            disable-model-invocation: true
            ---

            # Secret Skill
            """.trimIndent(),
        )
        val result = SkillLoader().loadFromDirectory(tmp, SkillSource.PROJECT)
        val registry = InMemorySkillRegistry(result.skills)
        assertTrue(registry.visibleToModel().isEmpty())

        val expanded = SkillCommandExpander { registry }.tryExpand("/skill:secret do the thing")

        assertIs<SkillCommandExpander.Result.Expanded>(expanded)
        assertEquals("secret", expanded.skill.name)
    }
}
