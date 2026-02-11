package fraggle

import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Environment configuration for Fraggle runtime directories.
 */
object FraggleEnvironment {
    /**
     * The root directory for all Fraggle runtime files.
     * Set via FRAGGLE_ROOT environment variable, defaults to current directory.
     */
    val root: Path by lazy {
        val envRoot = System.getenv("FRAGGLE_ROOT")
        if (envRoot == null) {
            Path(".").toAbsolutePath().normalize()
        } else {
            Path(envRoot).toAbsolutePath().normalize()
        }
    }

    /**
     * Config directory: {FRAGGLE_ROOT}/config
     */
    val configDir: Path get() = root.resolve("config")

    /**
     * Data directory: {FRAGGLE_ROOT}/data
     */
    val dataDir: Path get() = root.resolve("data")

    /**
     * Logs directory: {FRAGGLE_ROOT}/logs
     */
    val logsDir: Path get() = root.resolve("logs")

    /**
     * Default config file path.
     */
    val defaultConfigPath: Path get() = configDir.resolve("fraggle.yaml")

    /**
     * Resolve a path relative to the root, expanding ~ for home directory.
     */
    fun resolvePath(path: String): Path {
        val expanded = path.replace("~", System.getProperty("user.home"))
        val p = Path(expanded)
        return if (p.isAbsolute) p else root.resolve(p).normalize()
    }
}
