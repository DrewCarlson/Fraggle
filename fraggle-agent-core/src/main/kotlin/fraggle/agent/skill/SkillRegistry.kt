package fraggle.agent.skill

/**
 * Registry of loaded skills. Both the messenger assistant and the future coding agent
 * consume the same registry; it lives in `fraggle-agent-core` so nothing agent-specific
 * needs to know how skills are discovered or parsed.
 */
interface SkillRegistry {
    /** All loaded skills, after collision resolution. */
    val skills: List<Skill>

    /** Diagnostics accumulated during loading. */
    val diagnostics: List<SkillDiagnostic>

    fun findByName(name: String): Skill?

    /** Skills the model may autonomously invoke (excludes `disable-model-invocation: true`). */
    fun visibleToModel(): List<Skill>
}

/**
 * Default [SkillRegistry] backed by an immutable map. Collisions on `name` are resolved
 * by [SkillSource] precedence — later sources win: `PACKAGE < GLOBAL < PROJECT < EXPLICIT`.
 * Within the same source, the last entry wins.
 */
class InMemorySkillRegistry(
    entries: List<Skill>,
    override val diagnostics: List<SkillDiagnostic> = emptyList(),
) : SkillRegistry {

    private val byName: Map<String, Skill> = buildMap {
        for (skill in entries) {
            val existing = get(skill.name)
            if (existing == null || skill.source.ordinal >= existing.source.ordinal) {
                put(skill.name, skill)
            }
        }
    }

    override val skills: List<Skill> = byName.values.sortedBy { it.name }

    override fun findByName(name: String): Skill? = byName[name]

    override fun visibleToModel(): List<Skill> = skills.filterNot { it.disableModelInvocation }
}

/** Empty registry for when skills are disabled in config. */
object EmptySkillRegistry : SkillRegistry {
    override val skills: List<Skill> = emptyList()
    override val diagnostics: List<SkillDiagnostic> = emptyList()
    override fun findByName(name: String): Skill? = null
    override fun visibleToModel(): List<Skill> = emptyList()
}
