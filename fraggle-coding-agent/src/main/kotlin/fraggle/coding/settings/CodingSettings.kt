package fraggle.coding.settings

import kotlinx.serialization.Serializable

/**
 * Persisted coding-agent settings, merged from two files:
 *  - Global: `$FRAGGLE_ROOT/coding/settings.json`
 *  - Project: `<project>/.fraggle/coding/settings.json`
 *
 * Project values override global when both files are present. The CLI
 * command applies a final layer of overrides from command-line flags so
 * one-off runs can change behaviour without mutating the files.
 *
 * Every field is optional at the file level — fields the user hasn't
 * explicitly set get the defaults in [CodingSettingsDefaults]. This lets
 * the user ship a two-line `settings.json` that only overrides the fields
 * they care about, and add new fields later without breaking older files.
 */
@Serializable
data class CodingSettings(
    /** Supervision mode: "ask" or "none". */
    val supervision: String? = null,

    /** Context-window size (tokens) for the current model; 0 = unknown. */
    val contextWindowTokens: Int? = null,

    /** Compaction: trigger the ratio at this ratio. */
    val compactionTriggerRatio: Double? = null,

    /** Compaction: keep this many messages verbatim. */
    val compactionKeepRecentMessages: Int? = null,

    /** Maximum tool-call iterations per turn. */
    val maxIterations: Int? = null,

    /** Override the default model id. */
    val model: String? = null,
)

/**
 * Defaults applied when a settings field is missing from both files AND
 * isn't overridden by a CLI flag. These mirror what the coding-agent
 * classes themselves default to — centralized here so there's one place
 * to read "what does fraggle code do out of the box."
 */
object CodingSettingsDefaults {
    const val supervision: String = "ask"
    const val contextWindowTokens: Int = 0
    const val compactionTriggerRatio: Double = 0.70
    const val compactionKeepRecentMessages: Int = 12
    const val maxIterations: Int = 20
}
