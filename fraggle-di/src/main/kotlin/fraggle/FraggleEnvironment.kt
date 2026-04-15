package fraggle

import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Environment configuration for Fraggle runtime directories.
 */
object FraggleEnvironment {
    /**
     * The root directory for all Fraggle runtime files.
     * Set via FRAGGLE_ROOT environment variable, defaults to ~/.fraggle.
     */
    val root: Path by lazy {
        val envRoot = System.getenv("FRAGGLE_ROOT")
        if (envRoot == null) {
            Path(System.getProperty("user.home")).resolve(".fraggle").toAbsolutePath().normalize()
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
     * Global skills directory: {FRAGGLE_ROOT}/skills
     *
     * Holds SKILL.md bundles that are shared across every Fraggle session.
     * The directory is created on demand by whichever component first writes
     * to it (typically the `fraggle skills add` CLI).
     */
    val skillsDir: Path get() = root.resolve("skills")

    /**
     * Current working directory — the user's active project when running
     * `fraggle code` or `fraggle skills` inside a repo. Resolved lazily at
     * each access so test harnesses can chdir without stale caching.
     */
    val projectDir: Path get() = Path(System.getProperty("user.dir")).toAbsolutePath().normalize()

    /**
     * Per-project Fraggle state directory: {CWD}/.fraggle
     *
     * Everything project-scoped lives under here (skills, future session
     * overrides, etc.). Keeping one well-known sub-directory means we never
     * litter a user's repo with ad-hoc top-level folders.
     */
    val projectStateDir: Path get() = projectDir.resolve(".fraggle")

    /** Per-project skills directory: {CWD}/.fraggle/skills */
    val projectSkillsDir: Path get() = projectStateDir.resolve("skills")

    /**
     * Resolve a path that should be treated as CWD-relative (as opposed to
     * [resolvePath], which treats relative paths as FRAGGLE_ROOT-relative).
     * Absolute paths are returned unchanged; `~` still expands to the user's
     * home directory.
     */
    fun resolveProjectPath(path: String): Path {
        val expanded = path.replace("~", System.getProperty("user.home"))
        val p = Path(expanded)
        return if (p.isAbsolute) p else projectDir.resolve(p).normalize()
    }

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
