package fraggle.agent.skill

import java.nio.file.Path

/**
 * Provides skill-aware environment augmentation for tool execution.
 *
 * When a tool (e.g. `execute_command`) receives a `skill` parameter, it uses
 * this context to resolve the skill's Python venv and configured secrets
 * without the tool needing to know storage details.
 *
 * Secret values are injected at the process level and never pass through the
 * LLM — the model only sees the skill name.
 */
interface SkillExecutionContext {

    /**
     * Resolve the execution environment for [skillName].
     * Returns null if the skill is not found in the registry.
     */
    fun resolveEnvironment(skillName: String): SkillEnvironment?
}

/**
 * Resolved execution environment for a skill.
 *
 * @property envVars Extra environment variables to set (secrets + VIRTUAL_ENV).
 * @property venvBinDir The venv `bin` directory to prepend to PATH, or null if
 *   no venv is set up for this skill.
 * @property workDir The skill's base directory, used as the working directory
 *   for command execution.
 */
data class SkillEnvironment(
    val envVars: Map<String, String>,
    val venvBinDir: Path?,
    val workDir: Path,
)
