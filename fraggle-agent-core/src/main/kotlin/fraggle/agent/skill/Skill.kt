package fraggle.agent.skill

import java.nio.file.Path

/**
 * A loaded agent skill — metadata + a pointer to the SKILL.md file on disk.
 *
 * Skills follow the [agentskills.io](https://agentskills.io) specification: a directory
 * containing a SKILL.md with YAML frontmatter, optionally accompanied by scripts, assets,
 * and reference material. Only metadata is injected into the system prompt; the model
 * reads the full body on demand.
 */
data class Skill(
    val name: String,
    val description: String,
    val filePath: Path,
    val baseDir: Path,
    val source: SkillSource,
    val disableModelInvocation: Boolean,
    val frontmatter: SkillFrontmatter,
)

/**
 * Origin of a skill. Used for collision precedence when the same name appears in
 * multiple locations — [PROJECT] wins over [GLOBAL] wins over [PACKAGE]. [EXPLICIT]
 * entries (CLI `--skill` flag or config `extra_paths`) override everything.
 */
enum class SkillSource {
    PACKAGE,
    GLOBAL,
    PROJECT,
    EXPLICIT,
}
