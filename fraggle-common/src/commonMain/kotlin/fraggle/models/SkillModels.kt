package fraggle.models

import kotlinx.serialization.Serializable

/**
 * Summary of a skill for list views.
 */
@Serializable
data class SkillInfo(
    val name: String,
    val description: String,
    val source: String,
    val disableModelInvocation: Boolean,
)

/**
 * Detailed information about a skill, including the full SKILL.md body.
 */
@Serializable
data class SkillDetail(
    val name: String,
    val description: String,
    val source: String,
    val filePath: String,
    val baseDir: String,
    val disableModelInvocation: Boolean,
    val license: String? = null,
    val compatibility: String? = null,
    val allowedTools: List<String> = emptyList(),
    val body: String,
)
