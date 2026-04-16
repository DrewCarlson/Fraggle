package fraggle.agent.skill

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * YAML frontmatter block at the top of a SKILL.md file.
 *
 * Fields beyond [name] and [description] are parsed best-effort per the
 * [agentskills.io](https://agentskills.io/specification) specification. Unknown fields
 * are ignored. Soft validation failures (name too long, bad characters, etc.) produce
 * warnings rather than load failures — only missing [description] causes a skill to be
 * skipped entirely.
 */
@Serializable
data class SkillFrontmatter(
    val name: String? = null,
    val description: String? = null,
    val license: String? = null,
    val compatibility: String? = null,
    @SerialName("allowed-tools")
    val allowedTools: List<String> = emptyList(),
    val env: List<String> = emptyList(),
    @SerialName("disable-model-invocation")
    val disableModelInvocation: Boolean = false,
)
