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

// ---- Skill installation models ----

/**
 * Request body for POST /skills/preview — resolves a source and reports
 * what skills would be installed without touching disk.
 */
@Serializable
data class SkillPreviewRequest(
    val source: String,
)

/**
 * A single skill discovered during preview.
 */
@Serializable
data class SkillPreviewEntry(
    val name: String,
    val description: String,
    val license: String? = null,
    val compatibility: String? = null,
    val allowedTools: List<String> = emptyList(),
    val hasPythonDeps: Boolean = false,
    val requiredEnv: List<String> = emptyList(),
)

/**
 * Response for POST /skills/preview.
 */
@Serializable
data class SkillPreviewResponse(
    val sourceLabel: String,
    val skills: List<SkillPreviewEntry>,
    val diagnostics: List<String> = emptyList(),
)

/**
 * Request body for POST /skills/install.
 */
@Serializable
data class SkillInstallRequest(
    val source: String,
    val scope: String = "global",
)

/**
 * A single skill that was installed.
 */
@Serializable
data class SkillInstalledEntry(
    val name: String,
    val destination: String,
)

/**
 * Response for POST /skills/install.
 */
@Serializable
data class SkillInstallResponse(
    val installed: List<SkillInstalledEntry>,
    val skipped: List<String> = emptyList(),
    val diagnostics: List<String> = emptyList(),
)
