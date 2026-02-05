package org.drewcarlson.fraggle.sandbox

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Security sandbox interface for controlled execution of operations.
 * Implementations can range from permissive (logging only) to strict (Docker/gVisor isolation).
 */
interface Sandbox {
    /**
     * Read a file with optional line limit.
     */
    suspend fun readFile(path: String, maxLines: Int = Int.MAX_VALUE): SandboxResult<String>

    /**
     * Write content to a file.
     */
    suspend fun writeFile(path: String, content: String): SandboxResult<Unit>

    /**
     * Append content to a file.
     */
    suspend fun appendFile(path: String, content: String): SandboxResult<Unit>

    /**
     * Delete a file.
     */
    suspend fun deleteFile(path: String): SandboxResult<Unit>

    /**
     * List files in a directory.
     */
    suspend fun listFiles(path: String, recursive: Boolean = false): SandboxResult<List<FileInfo>>

    /**
     * Check if a path exists.
     */
    suspend fun exists(path: String): SandboxResult<Boolean>

    /**
     * Execute a shell command.
     */
    suspend fun execute(command: String, timeout: Duration = 30.seconds): SandboxResult<ExecutionResult>

    /**
     * Fetch content from a URL.
     */
    suspend fun fetch(url: String, timeout: Duration = 30.seconds): SandboxResult<FetchResult>

    /**
     * Get the working directory.
     */
    fun workDir(): Path

    /**
     * Get the security configuration.
     */
    fun config(): SecurityConfig
}

/**
 * Result wrapper for sandbox operations.
 */
sealed class SandboxResult<out T> {
    data class Success<T>(val value: T) : SandboxResult<T>()
    data class Denied(val reason: String) : SandboxResult<Nothing>()
    data class Error(val message: String, val cause: Throwable? = null) : SandboxResult<Nothing>()

    fun getOrNull(): T? = when (this) {
        is Success -> value
        else -> null
    }

    fun getOrThrow(): T = when (this) {
        is Success -> value
        is Denied -> throw SecurityException("Operation denied: $reason")
        is Error -> throw SandboxException(message, cause)
    }

    inline fun <R> map(transform: (T) -> R): SandboxResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Denied -> this
        is Error -> this
    }

    inline fun <R> flatMap(transform: (T) -> SandboxResult<R>): SandboxResult<R> = when (this) {
        is Success -> transform(value)
        is Denied -> this
        is Error -> this
    }
}

class SandboxException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Result of command execution.
 */
data class ExecutionResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
)

/**
 * Result of URL fetch.
 */
data class FetchResult(
    val statusCode: Int,
    val body: String,
    val contentType: String?,
    val headers: Map<String, String>,
)

/**
 * File information.
 */
data class FileInfo(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
)

/**
 * Security configuration for the sandbox.
 */
data class SecurityConfig(
    val allowedPaths: List<PathPermission> = emptyList(),
    val deniedPaths: List<String> = emptyList(),
    val allowedCommands: List<CommandPermission> = emptyList(),
    val deniedCommands: List<String> = emptyList(),
    val allowedDomains: List<String> = emptyList(),
    val deniedDomains: List<String> = emptyList(),
    val maxFileSize: Long = 10 * 1024 * 1024, // 10MB
    val maxOutputSize: Int = 100_000, // characters
)

/**
 * Path permission specification.
 */
data class PathPermission(
    val path: String,
    val read: Boolean = true,
    val write: Boolean = false,
    val recursive: Boolean = true,
)

/**
 * Command permission specification.
 */
data class CommandPermission(
    val pattern: Regex,
    val description: String = "",
)

/**
 * Permissive sandbox implementation that allows all operations but logs them.
 * Use for development and trusted environments only.
 */
class PermissiveSandbox(
    private val workDir: Path,
    private val securityConfig: SecurityConfig = SecurityConfig(),
) : Sandbox {

    override fun workDir(): Path = workDir

    override fun config(): SecurityConfig = securityConfig

    override suspend fun readFile(path: String, maxLines: Int): SandboxResult<String> =
        withContext(Dispatchers.IO) {
            try {
                val resolvedPath = resolvePath(path)

                if (!resolvedPath.exists()) {
                    return@withContext SandboxResult.Error("File not found: $path")
                }

                if (!resolvedPath.isRegularFile()) {
                    return@withContext SandboxResult.Error("Not a regular file: $path")
                }

                val content = if (maxLines == Int.MAX_VALUE) {
                    resolvedPath.readText()
                } else {
                    resolvedPath.readLines().take(maxLines).joinToString("\n")
                }

                SandboxResult.Success(content)
            } catch (e: Exception) {
                SandboxResult.Error("Failed to read file: ${e.message}", e)
            }
        }

    override suspend fun writeFile(path: String, content: String): SandboxResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val resolvedPath = resolvePath(path)
                resolvedPath.parent?.createDirectories()
                resolvedPath.writeText(content)
                SandboxResult.Success(Unit)
            } catch (e: Exception) {
                SandboxResult.Error("Failed to write file: ${e.message}", e)
            }
        }

    override suspend fun appendFile(path: String, content: String): SandboxResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val resolvedPath = resolvePath(path)
                resolvedPath.parent?.createDirectories()
                resolvedPath.appendText(content)
                SandboxResult.Success(Unit)
            } catch (e: Exception) {
                SandboxResult.Error("Failed to append to file: ${e.message}", e)
            }
        }

    override suspend fun deleteFile(path: String): SandboxResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val resolvedPath = resolvePath(path)
                if (resolvedPath.exists()) {
                    resolvedPath.deleteExisting()
                }
                SandboxResult.Success(Unit)
            } catch (e: Exception) {
                SandboxResult.Error("Failed to delete file: ${e.message}", e)
            }
        }

    override suspend fun listFiles(path: String, recursive: Boolean): SandboxResult<List<FileInfo>> =
        withContext(Dispatchers.IO) {
            try {
                val resolvedPath = resolvePath(path)

                if (!resolvedPath.exists()) {
                    return@withContext SandboxResult.Error("Directory not found: $path")
                }

                if (!resolvedPath.isDirectory()) {
                    return@withContext SandboxResult.Error("Not a directory: $path")
                }

                val files = if (recursive) {
                    resolvedPath.walk().toList()
                } else {
                    resolvedPath.listDirectoryEntries()
                }

                val fileInfos = files.map { file ->
                    FileInfo(
                        path = file.absolutePathString(),
                        name = file.name,
                        isDirectory = file.isDirectory(),
                        size = if (file.isRegularFile()) file.fileSize() else 0,
                        lastModified = file.getLastModifiedTime().toMillis(),
                    )
                }

                SandboxResult.Success(fileInfos)
            } catch (e: Exception) {
                SandboxResult.Error("Failed to list files: ${e.message}", e)
            }
        }

    override suspend fun exists(path: String): SandboxResult<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val resolvedPath = resolvePath(path)
                SandboxResult.Success(resolvedPath.exists())
            } catch (e: Exception) {
                SandboxResult.Error("Failed to check path: ${e.message}", e)
            }
        }

    override suspend fun execute(command: String, timeout: Duration): SandboxResult<ExecutionResult> =
        withContext(Dispatchers.IO) {
            try {
                val processBuilder = ProcessBuilder("sh", "-c", command)
                    .directory(workDir.toFile())
                    .redirectErrorStream(false)

                val process = processBuilder.start()

                val result = withTimeoutOrNull(timeout) {
                    val stdout = process.inputStream.bufferedReader().readText()
                    val stderr = process.errorStream.bufferedReader().readText()
                    val exitCode = process.waitFor()

                    ExecutionResult(
                        exitCode = exitCode,
                        stdout = stdout.take(securityConfig.maxOutputSize),
                        stderr = stderr.take(securityConfig.maxOutputSize),
                        timedOut = false,
                    )
                }

                if (result == null) {
                    process.destroyForcibly()
                    SandboxResult.Success(
                        ExecutionResult(
                            exitCode = -1,
                            stdout = "",
                            stderr = "Command timed out after $timeout",
                            timedOut = true,
                        )
                    )
                } else {
                    SandboxResult.Success(result)
                }
            } catch (e: Exception) {
                SandboxResult.Error("Failed to execute command: ${e.message}", e)
            }
        }

    override suspend fun fetch(url: String, timeout: Duration): SandboxResult<FetchResult> =
        withContext(Dispatchers.IO) {
            try {
                val connection = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = timeout.inWholeMilliseconds.toInt()
                connection.readTimeout = timeout.inWholeMilliseconds.toInt()
                connection.requestMethod = "GET"

                val statusCode = connection.responseCode
                val contentType = connection.contentType
                val headers = connection.headerFields
                    .filterKeys { it != null }
                    .mapValues { it.value.firstOrNull() ?: "" }

                val body = try {
                    connection.inputStream.bufferedReader().readText()
                } catch (_: Exception) {
                    connection.errorStream?.bufferedReader()?.readText() ?: ""
                }

                SandboxResult.Success(
                    FetchResult(
                        statusCode = statusCode,
                        body = body.take(securityConfig.maxOutputSize),
                        contentType = contentType,
                        headers = headers,
                    )
                )
            } catch (e: Exception) {
                SandboxResult.Error("Failed to fetch URL: ${e.message}", e)
            }
        }

    private fun resolvePath(path: String): Path {
        val p = Path.of(path)
        return if (p.isAbsolute) p else workDir.resolve(p).normalize()
    }
}
