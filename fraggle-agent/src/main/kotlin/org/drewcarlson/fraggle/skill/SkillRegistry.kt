package org.drewcarlson.fraggle.skill

/**
 * Registry for managing skills and skill groups.
 *
 * Example usage:
 * ```kotlin
 * val registry = SkillRegistry {
 *     install(fileReadSkill)
 *     install(fileWriteSkill)
 *
 *     group("filesystem") {
 *         install(fileReadSkill)
 *         install(fileWriteSkill)
 *         install(fileSearchSkill)
 *     }
 * }
 *
 * // Look up skills
 * val skill = registry.get("file_read")
 * val tools = registry.toOpenAITools()
 * ```
 */
class SkillRegistry internal constructor(
    private val skills: Map<String, Skill>,
    private val groups: Map<String, SkillGroup>,
) {
    companion object {
        operator fun invoke(block: SkillRegistryBuilder.() -> Unit): SkillRegistry {
            val builder = SkillRegistryBuilder()
            builder.block()
            return builder.build()
        }
    }

    /**
     * Get a skill by name.
     */
    fun get(name: String): Skill? = skills[name]

    /**
     * Get a skill by name, throwing if not found.
     */
    fun require(name: String): Skill =
        skills[name] ?: throw IllegalArgumentException("Skill not found: $name")

    /**
     * Get all registered skills.
     */
    fun all(): List<Skill> = skills.values.toList()

    /**
     * Get skills in a specific group.
     */
    fun group(name: String): SkillGroup? = groups[name]

    /**
     * Get all skill groups.
     */
    fun allGroups(): List<SkillGroup> = groups.values.toList()

    /**
     * Check if a skill is registered.
     */
    fun contains(name: String): Boolean = skills.containsKey(name)

    /**
     * Convert all skills to OpenAI tool format.
     */
    fun toOpenAITools(): List<OpenAITool> {
        return skills.values.map { skill ->
            OpenAITool(function = skill.toOpenAIFunction())
        }
    }

    /**
     * Convert skills from a specific group to OpenAI tool format.
     */
    fun toOpenAITools(groupName: String): List<OpenAITool> {
        val group = groups[groupName] ?: return emptyList()
        return group.skills.map { skill ->
            OpenAITool(function = skill.toOpenAIFunction())
        }
    }

    /**
     * Create a new registry with additional skills.
     */
    fun plus(other: SkillRegistry): SkillRegistry {
        return SkillRegistry(
            skills = this.skills + other.skills,
            groups = this.groups + other.groups,
        )
    }

    /**
     * Create a new registry with an additional skill.
     */
    fun plus(skill: Skill): SkillRegistry {
        return SkillRegistry(
            skills = this.skills + (skill.name to skill),
            groups = this.groups,
        )
    }
}

/**
 * A group of related skills with shared configuration.
 */
data class SkillGroup(
    val name: String,
    val skills: List<Skill>,
    val description: String = "",
)

@SkillDsl
class SkillRegistryBuilder {
    private val skills = mutableMapOf<String, Skill>()
    private val groups = mutableMapOf<String, SkillGroup>()

    /**
     * Install a skill into the registry.
     */
    fun install(skill: Skill) {
        skills[skill.name] = skill
    }

    /**
     * Install multiple skills into the registry.
     */
    fun install(vararg skillList: Skill) {
        skillList.forEach { install(it) }
    }

    /**
     * Create a group of skills.
     */
    fun group(name: String, description: String = "", block: SkillGroupBuilder.() -> Unit) {
        val builder = SkillGroupBuilder(name, description)
        builder.block()
        val group = builder.build()
        groups[name] = group
        // Also add group skills to the main registry
        group.skills.forEach { skills[it.name] = it }
    }

    fun build(): SkillRegistry {
        return SkillRegistry(
            skills = skills.toMap(),
            groups = groups.toMap(),
        )
    }
}

@SkillDsl
class SkillGroupBuilder(
    private val name: String,
    private val description: String,
) {
    private val skills = mutableListOf<Skill>()

    fun install(skill: Skill) {
        skills.add(skill)
    }

    fun install(vararg skillList: Skill) {
        skills.addAll(skillList)
    }

    fun build(): SkillGroup {
        return SkillGroup(
            name = name,
            skills = skills.toList(),
            description = description,
        )
    }
}

/**
 * Empty registry for initialization.
 */
val EmptySkillRegistry = SkillRegistry {}
