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
 * what skills would be installed without touching disk. `scope` controls
 * which target dir's manifest is consulted for previously-ignored names.
 */
@Serializable
data class SkillPreviewRequest(
    val source: String,
    val scope: String = "global",
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
 *
 * `previouslyIgnored` reports skill names that were ignored in a prior
 * install from this source (reading the target manifest). The dashboard
 * uses this to pre-check the "ignore" boxes when updating a known source.
 */
@Serializable
data class SkillPreviewResponse(
    val sourceLabel: String,
    val skills: List<SkillPreviewEntry>,
    val diagnostics: List<String> = emptyList(),
    val previouslyIgnored: List<String> = emptyList(),
)

/**
 * Request body for POST /skills/install.
 *
 * `ignored` lists skill names discovered at the source that should NOT be
 * installed. The choice is persisted in the manifest keyed by source label,
 * so future updates from the same source will keep them out.
 */
@Serializable
data class SkillInstallRequest(
    val source: String,
    val scope: String = "global",
    val ignored: List<String> = emptyList(),
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
