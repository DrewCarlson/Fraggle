package fraggle.agent.skill

/**
 * Default implementation of [SkillExecutionContext] that assembles a
 * [SkillEnvironment] from the skill registry, secrets store, and venv manager.
 *
 * For each resolved skill:
 * - All configured secrets are loaded as environment variables.
 * - If a venv is set up, `VIRTUAL_ENV` is set and the venv `bin` directory
 *   is returned for PATH prepend.
 * - The skill's [baseDir][Skill.baseDir] is used as the working directory.
 */
class DefaultSkillExecutionContext(
    private val registry: SkillRegistry,
    private val secretsStore: SkillSecretsStore,
    private val venvManager: SkillVenvManager,
) : SkillExecutionContext {

    override fun resolveEnvironment(skillName: String): SkillEnvironment? {
        val skill = registry.findByName(skillName) ?: return null
        val envVars = mutableMapOf<String, String>()

        // Inject configured secrets.
        envVars.putAll(secretsStore.loadEnvVars(skillName))

        // Venv activation.
        val venvBinDir = if (venvManager.isSetUp(skillName)) {
            val venvPath = venvManager.venvPath(skillName)
            envVars["VIRTUAL_ENV"] = venvPath.toString()
            venvManager.binDir(skillName)
        } else {
            null
        }

        return SkillEnvironment(
            envVars = envVars,
            venvBinDir = venvBinDir,
            workDir = skill.baseDir,
        )
    }
}
