package fraggle

import fraggle.agent.skill.Skill
import fraggle.agent.skill.SkillDiagnostic
import fraggle.agent.skill.SkillLoader
import fraggle.agent.skill.SkillSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Handles the actual filesystem work behind `fraggle skills add`: runs
 * [SkillLoader] over a source path, validates everything that loaded, and then
 * copies or symlinks each discovered skill's directory into a target skills dir.
 *
 * Extracted from the Clikt subcommand so it can be unit-tested without standing
 * up the CLI, and so the logic is shared with `fraggle skills add` from any
 * future source (GitHub tarball, git clone) — those will stage to a temp dir
 * and then hand off to this installer.
 *
 * Fraggle's installer is deliberately narrower than `npx skills`: it only writes
 * to Fraggle's own skill directories. Cross-agent installs (`.claude/skills`,
 * `.cursor/skills`, etc.) are not in scope — use the JS tool for that.
 */
class SkillInstaller(
    private val targetDir: Path,
    private val mode: InstallMode = InstallMode.COPY,
    private val force: Boolean = false,
) {

    enum class InstallMode { COPY, SYMLINK }

    /**
     * Outcome of an [install] call. Never throws for user-recoverable problems —
     * validation failures, name collisions, and filesystem errors all land in
     * [skipped] so the caller can print a useful summary and decide how to exit.
     */
    data class Result(
        val installed: List<Installed>,
        val skipped: List<Skipped>,
        val diagnostics: List<SkillDiagnostic>,
    ) {
        val hadAnySuccess: Boolean get() = installed.isNotEmpty()
    }

    data class Installed(
        val name: String,
        val destination: Path,
        val sourceLabel: String,
        val mode: InstallMode,
    )

    data class Skipped(val name: String, val reason: String)

    /** Outcome of a single [uninstall] call. */
    sealed class UninstallResult {
        /** Skill was removed from disk and the manifest. */
        data class Removed(val name: String, val path: Path, val mode: String) : UninstallResult()
        /** Skill isn't tracked in the manifest — refuse to touch it. */
        data class NotInManifest(val name: String) : UninstallResult()
        /** Filesystem error during deletion; manifest is left untouched. */
        data class Failed(val name: String, val reason: String) : UninstallResult()
    }

    /**
     * Install all skills discovered under [source] into [targetDir].
     *
     * [source] may be:
     *  - a directory containing a `SKILL.md` (single skill), or
     *  - a directory containing multiple skill subdirectories (each with its
     *    own `SKILL.md`), or
     *  - a single `SKILL.md` file (its parent directory is treated as the
     *    skill root).
     *
     * [sourceLabel] is what gets written into the manifest as the origin
     * identifier — e.g. `local:/abs/path/to/src` or later `github:owner/repo`.
     * Keeping it caller-supplied means this class doesn't need to know anything
     * about URL schemes.
     */
    @OptIn(ExperimentalTime::class)
    fun install(source: Path, sourceLabel: String): Result {
        val loader = SkillLoader()
        val loadResult = when {
            source.isRegularFile() && source.fileName.toString() == SkillLoader.SKILL_FILE_NAME ->
                loader.loadFromFile(source, SkillSource.EXPLICIT)
            source.isDirectory() -> loader.loadFromDirectory(source, SkillSource.EXPLICIT)
            !source.exists() -> return Result(
                installed = emptyList(),
                skipped = listOf(Skipped(source.toString(), "source does not exist")),
                diagnostics = emptyList(),
            )
            else -> return Result(
                installed = emptyList(),
                skipped = listOf(Skipped(source.toString(), "source is neither a SKILL.md nor a directory")),
                diagnostics = emptyList(),
            )
        }

        val errors = loadResult.diagnostics.filterIsInstance<SkillDiagnostic.Error>()
        if (errors.isNotEmpty() && loadResult.skills.isEmpty()) {
            return Result(
                installed = emptyList(),
                skipped = listOf(Skipped(source.toString(), "no valid skills found at source")),
                diagnostics = loadResult.diagnostics,
            )
        }

        targetDir.createDirectories()
        val manifest = SkillsManifest.read(manifestPath(targetDir))
        val entries = manifest.skills.associateBy { it.name }.toMutableMap()

        val installed = mutableListOf<Installed>()
        val skipped = mutableListOf<Skipped>()

        for (skill in loadResult.skills) {
            val destination = targetDir.resolve(skill.name)
            if (destination.exists()) {
                if (!force) {
                    skipped += Skipped(
                        skill.name,
                        "destination already exists at $destination (pass --force to overwrite)",
                    )
                    continue
                }
                deleteRecursively(destination)
            }

            try {
                when (mode) {
                    InstallMode.COPY -> copyDirectory(skill.baseDir, destination)
                    InstallMode.SYMLINK -> {
                        destination.parent?.createDirectories()
                        destination.createSymbolicLinkPointingTo(skill.baseDir)
                    }
                }
            } catch (e: Exception) {
                skipped += Skipped(skill.name, "failed to install: ${e.message ?: e::class.simpleName}")
                // Clean up a half-written destination so a rerun can succeed.
                runCatching { deleteRecursively(destination) }
                continue
            }

            entries[skill.name] = SkillsManifest.Entry(
                name = skill.name,
                source = sourceLabel,
                installedAt = Clock.System.now().toString(),
                mode = mode.name.lowercase(),
            )
            installed += Installed(skill.name, destination, sourceLabel, mode)
        }

        SkillsManifest(skills = entries.values.sortedBy { it.name })
            .write(manifestPath(targetDir))

        return Result(
            installed = installed,
            skipped = skipped,
            diagnostics = loadResult.diagnostics,
        )
    }

    /**
     * Remove a skill that was previously installed by [install]. Only skills
     * recorded in the manifest are eligible — content a user dropped into the
     * target manually is left alone. Returns [UninstallResult] so the CLI can
     * print a per-name summary without throwing.
     *
     * Symlinks are unlinked without following; directories are deleted
     * recursively. In either case the manifest entry is dropped.
     */
    fun uninstall(name: String): UninstallResult {
        val manifestFile = manifestPath(targetDir)
        val manifest = SkillsManifest.read(manifestFile)
        val entry = manifest.skills.firstOrNull { it.name == name }
            ?: return UninstallResult.NotInManifest(name)

        val destination = targetDir.resolve(name)
        return try {
            deleteRecursively(destination)
            val remaining = manifest.skills.filter { it.name != name }
            SkillsManifest(skills = remaining).write(manifestFile)
            UninstallResult.Removed(name, destination, entry.mode)
        } catch (e: Exception) {
            UninstallResult.Failed(name, e.message ?: e::class.simpleName.orEmpty())
        }
    }

    private fun copyDirectory(src: Path, dst: Path) {
        dst.createDirectories()
        Files.walk(src).use { stream ->
            for (path in stream) {
                val rel = path.relativeTo(src).toString()
                if (rel.isEmpty()) continue
                val target = dst.resolve(rel)
                val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
                when {
                    attrs.isDirectory -> target.createDirectories()
                    attrs.isRegularFile -> {
                        target.parent?.createDirectories()
                        Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                    // symlinks and other entries are skipped silently — skills
                    // should be plain content, and following symlinks during
                    // copy is a footgun.
                }
            }
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!path.exists()) return
        // Handle the symlink case first — we don't want to walk into the target.
        val attrs = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        } catch (_: Exception) {
            null
        }
        if (attrs != null && !attrs.isDirectory) {
            try { Files.delete(path) } catch (_: Exception) {}
            return
        }
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach {
                runCatching { Files.delete(it) }
            }
        }
    }

    companion object {
        const val MANIFEST_FILENAME = ".fraggle-skills.json"
        fun manifestPath(targetDir: Path): Path = targetDir.resolve(MANIFEST_FILENAME)
    }
}

/**
 * On-disk manifest of installed skills. Lives at `<target>/.fraggle-skills.json`
 * next to the skills themselves and is rewritten atomically-ish on each
 * `add`/`remove`. Intentionally kept as a flat JSON list so it's greppable and
 * cheap to hand-edit.
 *
 * Only files this project wrote should be in here — skills a user drops into
 * the directory manually won't appear in the manifest, and `list` will still
 * show them (it reads from `SkillLoader`, not the manifest).
 */
@Serializable
data class SkillsManifest(
    val version: Int = VERSION,
    val skills: List<Entry> = emptyList(),
) {
    @Serializable
    data class Entry(
        val name: String,
        val source: String,
        @SerialName("installed_at") val installedAt: String,
        val mode: String,
    )

    fun write(path: Path) {
        path.parent?.createDirectories()
        path.writeText(JSON.encodeToString(this))
    }

    companion object {
        const val VERSION = 1
        private val JSON = Json { prettyPrint = true; encodeDefaults = true }

        fun read(path: Path): SkillsManifest {
            if (!path.exists()) return SkillsManifest()
            return try {
                JSON.decodeFromString<SkillsManifest>(path.readText())
            } catch (e: Exception) {
                // Never fail an install because the manifest is corrupt —
                // start fresh and log via the caller if needed.
                SkillsManifest()
            }
        }
    }
}

/**
 * Template used by `fraggle skills init` to scaffold a new `SKILL.md`. Kept out
 * of the Clikt command so tests can pin the exact content.
 */
internal object SkillTemplate {
    fun render(name: String, description: String): String = buildString {
        appendLine("---")
        appendLine("name: $name")
        appendLine("description: $description")
        appendLine("---")
        appendLine()
        appendLine("# ${titleCase(name)}")
        appendLine()
        appendLine("Describe the workflow this skill implements. Start with *when* the model should")
        appendLine("reach for it — the description above is what triggers automatic invocation, so")
        appendLine("anything ambiguous there should be clarified here.")
        appendLine()
        appendLine("## Steps")
        appendLine()
        appendLine("1. First thing to do.")
        appendLine("2. Second thing to do.")
        appendLine("3. Report results.")
    }

    private fun titleCase(name: String): String =
        name.split('-').joinToString(" ") { part ->
            part.replaceFirstChar { if (it.isLowerCase()) it.titlecaseChar() else it }
        }
}
