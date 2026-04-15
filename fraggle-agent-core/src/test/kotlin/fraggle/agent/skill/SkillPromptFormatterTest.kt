package fraggle.agent.skill

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillPromptFormatterTest {

    private fun skill(
        name: String,
        description: String = "Desc for $name",
        path: String = "/skills/$name/SKILL.md",
        hidden: Boolean = false,
    ) = Skill(
        name = name,
        description = description,
        filePath = Path.of(path),
        baseDir = Path.of(path).parent,
        source = SkillSource.PROJECT,
        disableModelInvocation = hidden,
        frontmatter = SkillFrontmatter(name = name, description = description),
    )

    @Test
    fun `empty list renders empty string`() {
        assertEquals("", SkillPromptFormatter.format(emptyList()))
    }

    @Test
    fun `renders skills as XML block`() {
        val output = SkillPromptFormatter.format(
            listOf(
                skill("code-review", "Review code changes for correctness.", "/p/code-review/SKILL.md"),
                skill("commit-message", "Write conventional commit messages.", "/p/commit-message/SKILL.md"),
            ),
        )

        assertTrue(output.startsWith("<available_skills>"))
        assertTrue("<name>code-review</name>" in output)
        assertTrue("<description>Review code changes for correctness.</description>" in output)
        assertTrue("<location>/p/code-review/SKILL.md</location>" in output)
        assertTrue("<name>commit-message</name>" in output)
        assertTrue("</available_skills>" in output)
        assertTrue("read_file tool" in output)
    }

    @Test
    fun `hidden skills are filtered out`() {
        val output = SkillPromptFormatter.format(
            listOf(
                skill("visible", "Public skill."),
                skill("hidden", "Hidden skill.", hidden = true),
            ),
        )

        assertTrue("<name>visible</name>" in output)
        assertFalse("<name>hidden</name>" in output)
    }

    @Test
    fun `only-hidden list renders empty string`() {
        val output = SkillPromptFormatter.format(
            listOf(skill("hidden", hidden = true)),
        )
        assertEquals("", output)
    }

    @Test
    fun `escapes XML special characters`() {
        val output = SkillPromptFormatter.format(
            listOf(skill("danger", description = "Uses <tag> & \"quotes\".")),
        )
        assertTrue("&lt;tag&gt;" in output)
        assertTrue("&amp;" in output)
        assertFalse("<tag>" in output)
    }
}
