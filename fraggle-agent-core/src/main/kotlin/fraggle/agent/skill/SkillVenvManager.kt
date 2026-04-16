package fraggle.agent.skill

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Manages per-skill Python virtual environments under [venvsRoot].
 *
 * Each skill gets its own venv at `{venvsRoot}/{skillName}/`. If the skill's
 * [baseDir][Skill.baseDir] contains a `requirements.txt`, dependencies are
 * installed into the venv automatically during [setup].
 */
class SkillVenvManager(private val venvsRoot: Path) {

    /** Root path of the venv for [skillName]. */
    fun venvPath(skillName: String): Path = venvsRoot.resolve(skillName)

    /** Path to the venv's `python3` binary. */
    fun pythonPath(skillName: String): Path = venvPath(skillName).resolve("bin/python3")

    /** Path to the venv's `bin` directory (for PATH prepend). */
    fun binDir(skillName: String): Path = venvPath(skillName).resolve("bin")

    /** Whether the venv is set up and the python3 binary exists. */
    fun isSetUp(skillName: String): Boolean = pythonPath(skillName).exists()

    /**
     * Create a venv and install dependencies from `requirements.txt` if present.
     *
     * Steps:
     * 1. `python3 -m venv {venvsRoot}/{skillName}`
     * 2. If `{skill.baseDir}/requirements.txt` exists:
     *    `{venvPath}/bin/pip install -r {skill.baseDir}/requirements.txt`
     *
     * @return [SetupResult] with success flag and combined output.
     */
    suspend fun setup(skill: Skill): SetupResult = withContext(Dispatchers.IO) {
        val venv = venvPath(skill.name)
        venvsRoot.toFile().mkdirs()

        val venvResult = runProcess(
            listOf(findPython3(), "-m", "venv", venv.toString()),
            workDir = venvsRoot.toFile(),
        )
        if (!venvResult.success) {
            return@withContext SetupResult(
                success = false,
                output = "Failed to create venv:\n${venvResult.output}",
            )
        }

        val requirementsFile = skill.baseDir.resolve("requirements.txt")
        if (!requirementsFile.isRegularFile()) {
            return@withContext SetupResult(success = true, output = venvResult.output)
        }

        val pipResult = runProcess(
            listOf(
                binDir(skill.name).resolve("pip").toString(),
                "install", "-r", requirementsFile.toString(),
            ),
            workDir = skill.baseDir.toFile(),
        )
        SetupResult(
            success = pipResult.success,
            output = buildString {
                if (venvResult.output.isNotBlank()) appendLine(venvResult.output)
                append(pipResult.output)
            },
        )
    }

    /**
     * Delete and recreate the venv.
     */
    suspend fun rebuild(skill: Skill): SetupResult {
        remove(skill.name)
        return setup(skill)
    }

    /** Delete the venv directory entirely. */
    fun remove(skillName: String) {
        val venv = venvPath(skillName)
        if (!venv.exists()) return
        // Walk in reverse depth order so files are deleted before their parents.
        Files.walk(venv).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { path ->
                path.deleteIfExists()
            }
        }
    }

    data class SetupResult(val success: Boolean, val output: String)

    private suspend fun runProcess(
        command: List<String>,
        workDir: java.io.File,
    ): ProcessResult = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder(command)
                .directory(workDir)
                .redirectErrorStream(false)
                .start()

            val stdoutDeferred = async { process.inputStream.bufferedReader().readText() }
            val stderrDeferred = async { process.errorStream.bufferedReader().readText() }
            val exitCode = process.waitFor()
            val stdout = stdoutDeferred.await()
            val stderr = stderrDeferred.await()

            ProcessResult(
                success = exitCode == 0,
                output = buildString {
                    if (stdout.isNotBlank()) append(stdout.trim())
                    if (stderr.isNotBlank()) {
                        if (isNotEmpty()) appendLine()
                        append(stderr.trim())
                    }
                },
            )
        } catch (e: Exception) {
            ProcessResult(
                success = false,
                output = "Failed to run ${command.first()}: ${e.message ?: e::class.simpleName}",
            )
        }
    }

    private data class ProcessResult(val success: Boolean, val output: String)

    companion object {
        /**
         * Find the `python3` binary. Checks common names in order of preference.
         * Returns the first one found on PATH, falling back to `"python3"`.
         */
        internal fun findPython3(): String {
            val candidates = listOf("python3", "python")
            for (candidate in candidates) {
                try {
                    val process = ProcessBuilder("which", candidate)
                        .redirectErrorStream(true)
                        .start()
                    val output = process.inputStream.bufferedReader().readText().trim()
                    if (process.waitFor() == 0 && output.isNotBlank()) {
                        return candidate
                    }
                } catch (_: Exception) {
                    // Continue to next candidate.
                }
            }
            return "python3"
        }
    }
}
