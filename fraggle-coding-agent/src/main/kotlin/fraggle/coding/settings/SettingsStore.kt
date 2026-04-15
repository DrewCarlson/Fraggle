package fraggle.coding.settings

import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Loads and merges coding-agent settings from the global and project files.
 *
 * Resolution order (last wins):
 *  1. Defaults from [CodingSettingsDefaults]
 *  2. Global file `$FRAGGLE_ROOT/coding/settings.json`
 *  3. Project file `<project>/.fraggle/coding/settings.json`
 *
 * Missing files are treated as empty settings (no overrides). Malformed
 * JSON is logged to stderr and treated as empty — we don't want a typo in
 * settings.json to prevent the user from running `fraggle code`.
 *
 * The store is stateless: every call to [load] re-reads the files so live
 * edits to settings.json take effect on the next run.
 */
object SettingsStore {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        prettyPrint = true
    }

    /**
     * Read [globalFile] and [projectFile] (either may be null to skip) and
     * return a merged [CodingSettings] where project values override global
     * ones and both override the defaults from [CodingSettingsDefaults].
     *
     * All returned fields are non-null — the [Merged] result wraps the
     * computed effective values plus a [sources] bundle for diagnostics.
     */
    fun load(globalFile: Path?, projectFile: Path?): Merged {
        val global = globalFile?.let(::readFile) ?: CodingSettings()
        val project = projectFile?.let(::readFile) ?: CodingSettings()

        val effective = CodingSettings(
            supervision = project.supervision ?: global.supervision ?: CodingSettingsDefaults.supervision,
            contextWindowTokens = project.contextWindowTokens ?: global.contextWindowTokens ?: CodingSettingsDefaults.contextWindowTokens,
            compactionTriggerRatio = project.compactionTriggerRatio ?: global.compactionTriggerRatio ?: CodingSettingsDefaults.compactionTriggerRatio,
            compactionKeepRecentMessages = project.compactionKeepRecentMessages ?: global.compactionKeepRecentMessages ?: CodingSettingsDefaults.compactionKeepRecentMessages,
            maxIterations = project.maxIterations ?: global.maxIterations ?: CodingSettingsDefaults.maxIterations,
            model = project.model ?: global.model,
        )

        return Merged(
            effective = effective,
            sources = Sources(
                globalFileExists = globalFile?.let { it.exists() && it.isRegularFile() } ?: false,
                projectFileExists = projectFile?.let { it.exists() && it.isRegularFile() } ?: false,
            ),
        )
    }

    /**
     * Read a single settings file, tolerating missing files and malformed
     * JSON. Returns an empty [CodingSettings] on any failure. Errors go to
     * stderr so the user sees them but the command still runs.
     */
    private fun readFile(path: Path): CodingSettings {
        if (!path.exists() || !path.isRegularFile()) return CodingSettings()
        return try {
            json.decodeFromString(CodingSettings.serializer(), path.readText())
        } catch (e: Exception) {
            System.err.println("warning: failed to parse $path: ${e.message}")
            CodingSettings()
        }
    }

    /**
     * Result of a [load] call. [effective] holds the merged, all-non-null
     * settings the CLI will actually use. [sources] reports which of the
     * two files were present — useful for the header's "loaded N context
     * files" display and for diagnostics.
     */
    data class Merged(
        val effective: CodingSettings,
        val sources: Sources,
    )

    data class Sources(
        val globalFileExists: Boolean,
        val projectFileExists: Boolean,
    )
}
