package fraggle.agent.skill

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerializationException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.readLines

/**
 * Scans a directory tree for `SKILL.md` files and parses them into [Skill] instances.
 *
 * Discovery rules (matching the agentskills.io spec):
 * - If a directory contains `SKILL.md`, it is treated as a skill root and **not**
 *   recursed into further. Nested SKILL.md files under a skill root are ignored.
 * - Otherwise, subdirectories are scanned recursively until a SKILL.md is found.
 * - Dot-entries (e.g. `.git`, `.cache`) and `node_modules` are always skipped.
 * - Any `.gitignore`, `.ignore`, or `.fdignore` found in the tree contributes
 *   rules to a shared [GitignoreMatcher]; ignored directories are not recursed
 *   into. This prevents pointing `skills_dir` at an existing repo from scanning
 *   test fixtures, build output, or vendored dependencies. See [IGNORE_FILE_NAMES].
 *
 * Validation follows a warn-and-continue policy: bad name or over-long description
 * produces a diagnostic but the skill still loads. Missing `description` is the only
 * hard failure — the skill is skipped.
 */
class SkillLoader {

    data class LoadResult(
        val skills: List<Skill>,
        val diagnostics: List<SkillDiagnostic>,
    )

    fun loadFromDirectory(dir: Path, source: SkillSource): LoadResult {
        val skills = mutableListOf<Skill>()
        val diagnostics = mutableListOf<SkillDiagnostic>()
        if (!dir.isDirectory()) {
            return LoadResult(emptyList(), emptyList())
        }
        scan(dir, dir, source, skills, diagnostics, GitignoreMatcher())
        return LoadResult(skills, diagnostics)
    }

    fun loadFromFile(skillFile: Path, source: SkillSource): LoadResult {
        if (!skillFile.isRegularFile() || skillFile.name != SKILL_FILE_NAME) {
            return LoadResult(emptyList(), emptyList())
        }
        val diagnostics = mutableListOf<SkillDiagnostic>()
        val skill = parseSkillFile(skillFile, source, diagnostics)
        return LoadResult(listOfNotNull(skill), diagnostics)
    }

    private fun scan(
        dir: Path,
        root: Path,
        source: SkillSource,
        skills: MutableList<Skill>,
        diagnostics: MutableList<SkillDiagnostic>,
        ignoreMatcher: GitignoreMatcher,
    ) {
        // Accumulate any .gitignore/.ignore/.fdignore rules from this directory onto
        // the shared matcher, scoped to this directory's prefix so sibling subtrees
        // don't match each other's rules.
        loadIgnoreRules(dir, root, ignoreMatcher)

        val skillFile = dir.resolve(SKILL_FILE_NAME)
        if (skillFile.isRegularFile()) {
            parseSkillFile(skillFile, source, diagnostics)?.let(skills::add)
            return
        }
        val children = try {
            Files.list(dir).use { it.toList() }
        } catch (e: Exception) {
            diagnostics += SkillDiagnostic.Warning(dir, "failed to list directory: ${e.message}")
            return
        }
        for (child in children) {
            if (!child.isDirectory()) continue
            val childName = child.name
            // Always skip dot-directories and node_modules, regardless of ignore files.
            if (childName.startsWith(".") || childName == NODE_MODULES) continue
            val relativePath = toRelativePath(root, child)
            if (ignoreMatcher.isIgnored(relativePath, isDirectory = true)) continue
            scan(child, root, source, skills, diagnostics, ignoreMatcher)
        }
    }

    private fun loadIgnoreRules(dir: Path, root: Path, matcher: GitignoreMatcher) {
        val prefix = if (dir == root) "" else toRelativePath(root, dir) + "/"
        for (name in IGNORE_FILE_NAMES) {
            val ignoreFile = dir.resolve(name)
            if (!ignoreFile.isRegularFile()) continue
            try {
                matcher.add(ignoreFile.readLines(), prefix)
            } catch (_: Exception) {
                // A broken ignore file should never make skill discovery fail loud.
            }
        }
    }

    private fun toRelativePath(root: Path, child: Path): String =
        root.relativize(child).toString().replace('\\', '/')

    private fun parseSkillFile(
        skillFile: Path,
        source: SkillSource,
        diagnostics: MutableList<SkillDiagnostic>,
    ): Skill? {
        val baseDir = skillFile.parent ?: return null
        val parentDirName = baseDir.name

        val content = try {
            skillFile.readText()
        } catch (e: Exception) {
            diagnostics += SkillDiagnostic.Error(skillFile, "failed to read: ${e.message}")
            return null
        }

        val (yamlBlock, _) = extractFrontmatter(content)
        if (yamlBlock == null) {
            diagnostics += SkillDiagnostic.Error(skillFile, "missing YAML frontmatter")
            return null
        }

        val frontmatter = try {
            YAML.decodeFromString(SkillFrontmatter.serializer(), yamlBlock)
        } catch (e: SerializationException) {
            diagnostics += SkillDiagnostic.Error(skillFile, "invalid YAML frontmatter: ${e.message}")
            return null
        } catch (e: Exception) {
            diagnostics += SkillDiagnostic.Error(skillFile, "failed to parse frontmatter: ${e.message}")
            return null
        }

        val description = frontmatter.description?.trim()?.takeIf { it.isNotEmpty() }
        if (description == null) {
            diagnostics += SkillDiagnostic.Error(skillFile, "description is required")
            return null
        }
        if (description.length > MAX_DESCRIPTION_LENGTH) {
            diagnostics += SkillDiagnostic.Warning(
                skillFile,
                "description exceeds $MAX_DESCRIPTION_LENGTH characters (${description.length})",
            )
        }

        val name = frontmatter.name?.trim().orEmpty().ifEmpty { parentDirName }
        for (error in validateName(name, parentDirName)) {
            diagnostics += SkillDiagnostic.Warning(skillFile, error)
        }

        return Skill(
            name = name,
            description = normalizeDescription(description),
            filePath = skillFile,
            baseDir = baseDir,
            source = source,
            disableModelInvocation = frontmatter.disableModelInvocation,
            frontmatter = frontmatter.copy(name = name, description = description),
        )
    }

    private fun extractFrontmatter(content: String): Pair<String?, String> {
        val normalized = content.replace("\r\n", "\n").replace("\r", "\n")
        if (!normalized.startsWith("---")) return null to normalized
        val endIndex = normalized.indexOf("\n---", startIndex = 3)
        if (endIndex == -1) return null to normalized
        val yaml = normalized.substring(4, endIndex)
        val body = normalized.substring(endIndex + 4).trim()
        return yaml to body
    }

    private fun validateName(name: String, parentDirName: String): List<String> {
        val errors = mutableListOf<String>()
        if (name != parentDirName) {
            errors += "name \"$name\" does not match parent directory \"$parentDirName\""
        }
        if (name.length > MAX_NAME_LENGTH) {
            errors += "name exceeds $MAX_NAME_LENGTH characters (${name.length})"
        }
        if (!NAME_PATTERN.matches(name)) {
            errors += "name contains invalid characters (must be lowercase a-z, 0-9, hyphens only)"
        }
        if (name.startsWith("-") || name.endsWith("-")) {
            errors += "name must not start or end with a hyphen"
        }
        if (name.contains("--")) {
            errors += "name must not contain consecutive hyphens"
        }
        return errors
    }

    private fun normalizeDescription(description: String): String =
        description.replace("\r\n", "\n").replace('\n', ' ').replace(Regex("\\s+"), " ").trim()

    companion object {
        const val SKILL_FILE_NAME = "SKILL.md"
        const val MAX_NAME_LENGTH = 64
        const val MAX_DESCRIPTION_LENGTH = 1024
        private const val NODE_MODULES = "node_modules"
        private val IGNORE_FILE_NAMES = listOf(".gitignore", ".ignore", ".fdignore")
        private val NAME_PATTERN = Regex("^[a-z0-9-]+$")

        private val YAML = Yaml(
            configuration = YamlConfiguration(strictMode = false),
        )
    }
}

/** Diagnostic produced while loading skills. Errors cause the skill to be skipped; warnings do not. */
sealed class SkillDiagnostic {
    abstract val path: Path
    abstract val message: String

    data class Warning(override val path: Path, override val message: String) : SkillDiagnostic()
    data class Error(override val path: Path, override val message: String) : SkillDiagnostic()
}
