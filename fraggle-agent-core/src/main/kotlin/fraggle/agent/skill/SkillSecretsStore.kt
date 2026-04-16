package fraggle.agent.skill

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * File-based secret storage for skills. Each secret is stored as a plain text
 * file at `{secretsRoot}/{skillName}/{VAR_NAME}`.
 *
 * Directory permissions are set to `rwx------` (700) and file permissions to
 * `rw-------` (600) on systems that support POSIX file permissions.
 */
class SkillSecretsStore(private val secretsRoot: Path) {

    /** Write or overwrite a secret value. Creates directories as needed. */
    fun set(skillName: String, varName: String, value: String) {
        val dir = skillDir(skillName)
        dir.createDirectories()
        setPosixPermissions(dir, DIR_PERMS)
        val file = dir.resolve(varName)
        file.writeText(value)
        setPosixPermissions(file, FILE_PERMS)
    }

    /** Read a secret value. Returns null if not configured. */
    fun get(skillName: String, varName: String): String? {
        val file = skillDir(skillName).resolve(varName)
        if (!file.isRegularFile()) return null
        return file.readText()
    }

    /** Check whether a specific secret is configured. */
    fun isConfigured(skillName: String, varName: String): Boolean =
        skillDir(skillName).resolve(varName).isRegularFile()

    /** Return the set of configured variable names for a skill. */
    fun listConfigured(skillName: String): Set<String> {
        val dir = skillDir(skillName)
        if (!dir.isDirectory()) return emptySet()
        return Files.list(dir).use { stream ->
            stream.filter { it.isRegularFile() }
                .map { it.fileName.toString() }
                .toList()
                .toSet()
        }
    }

    /**
     * Load all configured secrets for a skill as a name-to-value map.
     * Suitable for injecting into a process environment.
     */
    fun loadEnvVars(skillName: String): Map<String, String> {
        val dir = skillDir(skillName)
        if (!dir.isDirectory()) return emptyMap()
        return Files.list(dir).use { stream ->
            stream.filter { it.isRegularFile() }
                .toList()
                .associate { it.fileName.toString() to it.readText() }
        }
    }

    /** Remove a single secret. Returns true if the file existed and was deleted. */
    fun remove(skillName: String, varName: String): Boolean =
        skillDir(skillName).resolve(varName).deleteIfExists()

    /** Remove all secrets for a skill. */
    fun removeAll(skillName: String) {
        val dir = skillDir(skillName)
        if (!dir.isDirectory()) return
        Files.list(dir).use { stream ->
            stream.forEach { it.deleteIfExists() }
        }
        dir.deleteIfExists()
    }

    private fun skillDir(skillName: String): Path = secretsRoot.resolve(skillName)

    private fun setPosixPermissions(path: Path, perms: String) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(perms))
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystem (e.g. Windows) — permissions best-effort only.
        }
    }

    companion object {
        private const val DIR_PERMS = "rwx------"
        private const val FILE_PERMS = "rw-------"
    }
}
