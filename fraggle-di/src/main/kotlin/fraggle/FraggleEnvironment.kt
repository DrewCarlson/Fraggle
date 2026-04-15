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
     * Coding-agent directory: {FRAGGLE_ROOT}/coding
     *
     * Holds settings.json, AGENTS.md (global), SYSTEM.md override, prompt
     * templates, and `sessions/<project-hash>/<uuid>.jsonl` session files.
     * The directory is created on demand by whichever coding-agent
     * component first needs it.
     */
    val codingDir: Path get() = root.resolve("coding")

    /** Global coding-agent settings file: {FRAGGLE_ROOT}/coding/settings.json */
    val codingSettingsFile: Path get() = codingDir.resolve("settings.json")

    /** Global coding-agent AGENTS.md file: {FRAGGLE_ROOT}/coding/AGENTS.md */
    val codingGlobalAgentsFile: Path get() = codingDir.resolve("AGENTS.md")

    /** Global coding-agent session storage: {FRAGGLE_ROOT}/coding/sessions */
    val codingSessionsDir: Path get() = codingDir.resolve("sessions")

    /** Global coding-agent prompt templates: {FRAGGLE_ROOT}/coding/prompts */
    val codingPromptsDir: Path get() = codingDir.resolve("prompts")

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
