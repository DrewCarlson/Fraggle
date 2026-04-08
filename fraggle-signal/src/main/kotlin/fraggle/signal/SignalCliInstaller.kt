package fraggle.signal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.*

/**
 * Handles automatic installation of signal-cli.
 *
 * Downloads and extracts signal-cli to the apps directory if not found in PATH.
 * On Linux, downloads the native binary; on other platforms, downloads the Java-based version.
 */
class SignalCliInstaller(
    private val appsDir: Path,
    private val version: String = DEFAULT_VERSION,
) {
    private val logger = LoggerFactory.getLogger(SignalCliInstaller::class.java)

    companion object {
        const val DEFAULT_VERSION = "0.14.2"
        private const val GITHUB_RELEASES_URL = "https://github.com/AsamK/signal-cli/releases/download"

        /**
         * Check if signal-cli is available in the system PATH.
         */
        fun isInPath(): Boolean {
            return try {
                val process = ProcessBuilder("which", "signal-cli")
                    .redirectErrorStream(true)
                    .start()
                val exitCode = process.waitFor()
                exitCode == 0
            } catch (_: Exception) {
                // Try Windows-style check
                try {
                    val process = ProcessBuilder("where", "signal-cli")
                        .redirectErrorStream(true)
                        .start()
                    process.waitFor() == 0
                } catch (_: Exception) {
                    false
                }
            }
        }

        /**
         * Check if the current OS is Linux.
         */
        fun isLinux(): Boolean {
            return System.getProperty("os.name").lowercase().contains("linux")
        }
    }

    /**
     * The directory where signal-cli will be installed.
     */
    val installDir: Path
        get() = appsDir.resolve("signal-cli-$version")

    /**
     * Get the path to the signal-cli executable.
     * Returns null if not installed.
     */
    fun getSignalCliPath(): Path? {
        return if (isLinux()) {
            // Native binary is directly in the install directory
            val binary = installDir.resolve("signal-cli")
            if (binary.exists() && binary.isExecutable()) binary else null
        } else {
            // Java version has bin/signal-cli script
            val script = installDir.resolve("signal-cli-$version").resolve("bin").resolve("signal-cli")
            if (script.exists()) script else null
        }
    }

    /**
     * Check if signal-cli is already installed.
     */
    fun isInstalled(): Boolean {
        return getSignalCliPath() != null
    }

    /**
     * Install signal-cli if not already installed.
     *
     * @return The path to the signal-cli executable, or null if installation failed.
     */
    suspend fun ensureInstalled(): Path? {
        // Check if already installed
        getSignalCliPath()?.let { return it }

        logger.info("signal-cli not found, installing version $version...")
        return install()
    }

    /**
     * Install signal-cli.
     *
     * @return The path to the signal-cli executable, or null if installation failed.
     */
    @OptIn(ExperimentalPathApi::class)
    suspend fun install(): Path? = withContext(Dispatchers.IO) {
        try {
            // Create apps directory if needed
            appsDir.createDirectories()

            // Determine which archive to download
            val archiveUrl = if (isLinux()) {
                "$GITHUB_RELEASES_URL/v$version/signal-cli-$version-Linux-native.tar.gz"
            } else {
                "$GITHUB_RELEASES_URL/v$version/signal-cli-$version.tar.gz"
            }

            logger.info("Downloading signal-cli from $archiveUrl")

            // Create install directory
            installDir.createDirectories()

            // Download the archive
            val archivePath = installDir.resolve("signal-cli.tar.gz")
            downloadFile(archiveUrl, archivePath)

            logger.info("Extracting signal-cli...")

            // Extract the archive
            extractTarGz(archivePath, installDir)

            // Clean up the archive
            archivePath.deleteIfExists()

            // Make the binary executable on Unix systems
            val signalCliPath = getSignalCliPath()
            if (signalCliPath != null && !System.getProperty("os.name").lowercase().contains("windows")) {
                makeExecutable(signalCliPath)
            }

            logger.info("signal-cli installed successfully at $signalCliPath")
            signalCliPath
        } catch (e: Exception) {
            logger.error("Failed to install signal-cli: ${e.message}", e)
            // Clean up partial installation
            try {
                installDir.deleteRecursively()
            } catch (e2: Exception) {
                logger.debug("Failed to clean up partial installation: ${e2.message}")
            }
            null
        }
    }

    private fun downloadFile(url: String, destination: Path) {
        val connection = URI(url).toURL().openConnection()
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000

        BufferedInputStream(connection.getInputStream()).use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytes = 0L
                val contentLength = connection.contentLengthLong

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytes += bytesRead

                    // Log progress every 10MB
                    if (totalBytes % (10 * 1024 * 1024) < 8192) {
                        val progress = if (contentLength > 0) {
                            " (${(totalBytes * 100 / contentLength)}%)"
                        } else {
                            ""
                        }
                        logger.debug("Downloaded ${totalBytes / 1024 / 1024}MB$progress")
                    }
                }
            }
        }
    }

    private fun extractTarGz(archive: Path, destination: Path) {
        // Use tar command for extraction (available on Linux, macOS, and Windows with WSL/Git Bash)
        val process = ProcessBuilder("tar", "-xzf", archive.toString(), "-C", destination.toString())
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw RuntimeException("Failed to extract archive: $output")
        }
    }

    private fun makeExecutable(path: Path) {
        try {
            val permissions = Files.getPosixFilePermissions(path).toMutableSet()
            permissions.add(PosixFilePermission.OWNER_EXECUTE)
            permissions.add(PosixFilePermission.GROUP_EXECUTE)
            permissions.add(PosixFilePermission.OTHERS_EXECUTE)
            Files.setPosixFilePermissions(path, permissions)
        } catch (e: UnsupportedOperationException) {
            // Not a POSIX file system, skip
            logger.debug("Cannot set executable permission: ${e.message}")
        }
    }

    /**
     * Uninstall signal-cli.
     */
    @OptIn(ExperimentalPathApi::class)
    fun uninstall(): Boolean {
        return try {
            installDir.deleteRecursively()
            true
        } catch (e: Exception) {
            logger.error("Failed to uninstall signal-cli: ${e.message}")
            false
        }
    }
}
