package fraggle.agent.skill

import java.nio.file.Path
import kotlin.io.path.isRegularFile

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
) {
    /** Whether this skill has a `requirements.txt` declaring Python dependencies. */
    val hasPythonDeps: Boolean get() = baseDir.resolve("requirements.txt").isRegularFile()

    /** Environment variable names this skill declares as required. */
    val requiredEnv: List<String> get() = frontmatter.env
}

/**
 * Origin of a skill. Used for collision precedence when the same name appears in
 * multiple locations — [PROJECT] wins over [GLOBAL]. [EXPLICIT]
 * entries (CLI `--skill` flag or config `extra_paths`) override everything.
 */
enum class SkillSource {
    GLOBAL,
    PROJECT,
    EXPLICIT,
}
