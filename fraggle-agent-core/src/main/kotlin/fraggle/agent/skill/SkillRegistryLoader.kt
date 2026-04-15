package fraggle.agent.skill

import fraggle.FraggleEnvironment
import fraggle.models.SkillsConfig
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/**
 * Builds a [SkillRegistry] by scanning disk. This is a stateless, reusable
 * operation so callers can rebuild the registry whenever they need a fresh
 * view — e.g. the REST dashboard on every `GET /skills` so newly installed
 * skills show up without a server restart.
 *
 * The agent runtime holds a cached snapshot (see [AgentCoreModule]) because
 * the system prompt should be stable within a turn; the dashboard uses this
 * loader directly so it reflects disk state immediately.
 */
class SkillRegistryLoader(
    private val skillLoader: SkillLoader = SkillLoader(),
) {
    /** Resolve the effective global skills directory from [config]. */
    fun resolveGlobalDir(config: SkillsConfig): Path =
        config.globalDir
            ?.takeIf { it.isNotBlank() }
            ?.let { FraggleEnvironment.resolvePath(it) }
            ?: FraggleEnvironment.skillsDir

    /** Resolve the effective project-scoped skills directory from [config]. */
    fun resolveProjectDir(config: SkillsConfig): Path =
        config.projectDir
            ?.takeIf { it.isNotBlank() }
            ?.let { FraggleEnvironment.resolveProjectPath(it) }
            ?: FraggleEnvironment.projectSkillsDir

    /**
     * Scan disk and produce a fresh [SkillRegistry] for [config]. Returns
     * [EmptySkillRegistry] when `config.enabled == false`. Diagnostics for
     * missing or malformed SKILL.md files are preserved on the registry so
     * the dashboard can render them.
     */
    fun load(config: SkillsConfig): SkillRegistry {
        if (!config.enabled) return EmptySkillRegistry

        val entries = mutableListOf<Skill>()
        val diagnostics = mutableListOf<SkillDiagnostic>()

        val globalResult = skillLoader.loadFromDirectory(resolveGlobalDir(config), SkillSource.GLOBAL)
        entries += globalResult.skills
        diagnostics += globalResult.diagnostics

        val projectResult = skillLoader.loadFromDirectory(resolveProjectDir(config), SkillSource.PROJECT)
        entries += projectResult.skills
        diagnostics += projectResult.diagnostics

        for (extra in config.extraPaths) {
            val path = FraggleEnvironment.resolvePath(extra)
            val result = if (path.isRegularFile()) {
                skillLoader.loadFromFile(path, SkillSource.EXPLICIT)
            } else {
                skillLoader.loadFromDirectory(path, SkillSource.EXPLICIT)
            }
            entries += result.skills
            diagnostics += result.diagnostics
        }

        return InMemorySkillRegistry(entries, diagnostics)
    }
}
