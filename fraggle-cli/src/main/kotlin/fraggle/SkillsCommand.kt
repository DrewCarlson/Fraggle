package fraggle

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import fraggle.agent.skill.InMemorySkillRegistry
import fraggle.agent.skill.Skill
import fraggle.agent.skill.SkillDiagnostic
import fraggle.agent.skill.SkillLoader
import fraggle.agent.skill.SkillSource
import fraggle.models.SkillsConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * Parent command for the `fraggle skills` subcommand family — a skill package
 * manager modelled on [vercel-labs/skills](https://github.com/vercel-labs/skills),
 * scoped to Fraggle's own skill directories ({FRAGGLE_ROOT}/skills for global
 * and {CWD}/.fraggle/skills for project-local). It does not write to other
 * agents' directories; use `npx skills` for cross-agent install.
 *
 * PR1 ships `list` and `validate`. Subsequent PRs add `init`, `add`, `remove`,
 * and `find`. See `docs/plans/agent-skills.md` → "Follow-up work" for the
 * delivery plan.
 *
 * The subcommands deliberately avoid booting the full [AppGraph] so they can
 * run on a broken configuration (no provider reachable, database locked, etc.).
 * They read only [SkillsConfig] off the config file and invoke [SkillLoader]
 * directly.
 */
class SkillsCommand : CliktCommand(name = "skills") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Manage agent skills (list, validate, install, …)"

    override fun run() = Unit

    init {
        subcommands(
            SkillsListCommand(),
            SkillsValidateCommand(),
            SkillsInitCommand(),
            SkillsAddCommand(),
            SkillsUpdateCommand(),
            SkillsRemoveCommand(),
            SkillsFindCommand(),
        )
    }
}

// ---------- shared helpers ----------

internal enum class SkillTarget { BOTH, GLOBAL, PROJECT }

/**
 * Loads the effective [SkillsConfig] for a CLI subcommand. Falls back to
 * [SkillsConfig] defaults if the config file doesn't exist or doesn't define
 * a skills section, so `fraggle skills list` works on a fresh install.
 */
internal fun loadSkillsConfig(configPath: String?): SkillsConfig {
    val path = if (configPath != null) Path(configPath).toAbsolutePath() else FraggleEnvironment.defaultConfigPath
    val config = if (path.exists()) ConfigLoader.load(path) else null
    return config?.fraggle?.skills ?: SkillsConfig()
}

/** Resolved global skills directory for [config]. */
internal fun globalSkillsDir(config: SkillsConfig): Path =
    config.globalDir
        ?.takeIf { it.isNotBlank() }
        ?.let { FraggleEnvironment.resolvePath(it) }
        ?: FraggleEnvironment.skillsDir

/** Resolved project-scoped skills directory for [config]. */
internal fun projectSkillsDir(config: SkillsConfig): Path =
    config.projectDir
        ?.takeIf { it.isNotBlank() }
        ?.let { FraggleEnvironment.resolveProjectPath(it) }
        ?: FraggleEnvironment.projectSkillsDir

/**
 * Loads skills from the configured sources, matching the runtime wiring in
 * `AgentCoreModule.provideSkillRegistry` but without DI. Respects [target]
 * so the CLI can filter to global-only or project-only.
 */
internal fun loadConfiguredSkills(
    config: SkillsConfig,
    target: SkillTarget,
): Pair<List<Skill>, List<SkillDiagnostic>> {
    if (!config.enabled) return emptyList<Skill>() to emptyList()

    val loader = SkillLoader()
    val entries = mutableListOf<Skill>()
    val diagnostics = mutableListOf<SkillDiagnostic>()

    if (target != SkillTarget.PROJECT) {
        val result = loader.loadFromDirectory(globalSkillsDir(config), SkillSource.GLOBAL)
        entries += result.skills
        diagnostics += result.diagnostics
    }

    if (target != SkillTarget.GLOBAL) {
        val result = loader.loadFromDirectory(projectSkillsDir(config), SkillSource.PROJECT)
        entries += result.skills
        diagnostics += result.diagnostics
    }

    // EXPLICIT paths are only loaded when showing the full picture.
    if (target == SkillTarget.BOTH) {
        for (extra in config.extraPaths) {
            val path = FraggleEnvironment.resolvePath(extra)
            val result = if (path.isRegularFile()) {
                loader.loadFromFile(path, SkillSource.EXPLICIT)
            } else {
                loader.loadFromDirectory(path, SkillSource.EXPLICIT)
            }
            entries += result.skills
            diagnostics += result.diagnostics
        }
    }

    return entries to diagnostics
}

/**
 * Resolves where `init` / `add` / `remove` should write. Only ever targets
 * Fraggle's own skill directories — never `.claude/skills`, never
 * `.cursor/skills`. Cross-agent installs are out of scope on purpose.
 *
 * Resolution order, first match wins:
 *  1. `--path <dir>` — explicit override.
 *  2. `--global` — global skills directory.
 *  3. `--project` — project skills directory.
 *  4. Default: global.
 */
internal fun resolveInstallTarget(
    config: SkillsConfig,
    global: Boolean,
    project: Boolean,
    pathOverride: String?,
): Path {
    if (pathOverride != null) return Path(pathOverride).toAbsolutePath()
    return when {
        project -> projectSkillsDir(config)
        global -> globalSkillsDir(config)
        else -> globalSkillsDir(config)
    }
}

// ---------- skills list ----------

class SkillsListCommand : CliktCommand(name = "list") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "List installed skills from the configured sources"

    private val configPath by option(
        "-c", "--config",
        help = $$"Path to configuration file (default: $FRAGGLE_ROOT/config/fraggle.yaml)"
    )

    private val globalOnly by option(
        "-g", "--global",
        help = "Only list skills from the global directory",
    ).flag()

    private val projectOnly by option(
        "-p", "--project",
        help = "Only list skills from the project directory",
    ).flag()

    private val showHidden by option(
        "--all",
        help = "Include skills with disable-model-invocation: true",
    ).flag()

    override fun run() {
        val config = loadSkillsConfig(configPath)
        if (!config.enabled) {
            println("Skills are disabled in config (skills.enabled = false).")
            return
        }

        val target = when {
            globalOnly && !projectOnly -> SkillTarget.GLOBAL
            projectOnly && !globalOnly -> SkillTarget.PROJECT
            else -> SkillTarget.BOTH
        }

        val (rawSkills, diagnostics) = loadConfiguredSkills(config, target)
        // Apply the same precedence resolution the runtime uses — callers
        // should see the exact set the agent will advertise.
        val registry = InMemorySkillRegistry(rawSkills, diagnostics)
        val skills = if (showHidden) registry.skills else registry.visibleToModel()

        if (skills.isEmpty()) {
            println("No skills found.")
            printSourcesHint(config, target)
            printDiagnostics(diagnostics)
            return
        }

        val maxName = skills.maxOf { it.name.length }
        val maxSource = skills.maxOf { it.source.name.length }

        println("Loaded ${skills.size} skill(s):")
        println()
        for (skill in skills) {
            val hiddenMarker = if (skill.disableModelInvocation) " [hidden]" else ""
            println(
                "  %-${maxName}s  %-${maxSource}s  %s%s".format(
                    skill.name,
                    skill.source.name.lowercase(),
                    skill.description,
                    hiddenMarker,
                ),
            )
            println("    " + skill.filePath)
        }
        printDiagnostics(diagnostics)
    }

    private fun printSourcesHint(config: SkillsConfig, target: SkillTarget) {
        println()
        println("Searched:")
        if (target != SkillTarget.PROJECT) {
            println("  global : ${globalSkillsDir(config)}")
        }
        if (target != SkillTarget.GLOBAL) {
            println("  project: ${projectSkillsDir(config)}")
        }
    }
}

// ---------- skills validate ----------

class SkillsValidateCommand : CliktCommand(name = "validate") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Validate SKILL.md files at a path; exits non-zero on errors"

    private val pathArg by argument(
        name = "path",
        help = "Directory or SKILL.md file to validate. Defaults to the configured skills directories.",
    ).optional()

    private val configPath by option(
        "-c", "--config",
        help = $$"Path to configuration file (default: $FRAGGLE_ROOT/config/fraggle.yaml)"
    )

    override fun run() {
        val loader = SkillLoader()
        val (skills, diagnostics) = if (pathArg != null) {
            val p = Path(pathArg!!).toAbsolutePath()
            if (!p.exists()) {
                println("Path does not exist: $p")
                exitProcess(2)
            }
            val result = when {
                p.isRegularFile() -> loader.loadFromFile(p, SkillSource.EXPLICIT)
                p.isDirectory() -> loader.loadFromDirectory(p, SkillSource.EXPLICIT)
                else -> {
                    println("Path is neither a regular file nor a directory: $p")
                    exitProcess(2)
                }
            }
            result.skills to result.diagnostics
        } else {
            val config = loadSkillsConfig(configPath)
            loadConfiguredSkills(config, SkillTarget.BOTH)
        }

        println("Validated ${skills.size} skill(s).")
        val errors = diagnostics.filterIsInstance<SkillDiagnostic.Error>()
        val warnings = diagnostics.filterIsInstance<SkillDiagnostic.Warning>()

        if (warnings.isNotEmpty()) {
            println()
            println("Warnings (${warnings.size}):")
            for (w in warnings) println("  WARN ${w.path}: ${w.message}")
        }
        if (errors.isNotEmpty()) {
            println()
            println("Errors (${errors.size}):")
            for (e in errors) println("  ERR  ${e.path}: ${e.message}")
            exitProcess(1)
        }

        if (warnings.isEmpty() && errors.isEmpty() && skills.isNotEmpty()) {
            println("All skills valid.")
        }
    }
}

// ---------- skills init ----------

class SkillsInitCommand : CliktCommand(name = "init") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Scaffold a new SKILL.md under a target skills directory"

    private val nameArg by argument(
        name = "name",
        help = "Skill name (lowercase a-z, 0-9, hyphens; must match directory name).",
    )

    private val descriptionOpt by option(
        "-d", "--description",
        help = "Skill description (shown to the model for automatic invocation).",
    )

    private val configPath by option(
        "-c", "--config",
        help = $$"Path to configuration file (default: $FRAGGLE_ROOT/config/fraggle.yaml)",
    )

    private val global by option("-g", "--global", help = "Scaffold under the global skills directory.").flag()
    private val project by option("-p", "--project", help = "Scaffold under the project skills directory.").flag()
    private val pathOpt by option("--path", help = "Override the target directory.")
    private val force by option("--force", help = "Overwrite an existing SKILL.md at the target.").flag()

    override fun run() {
        val config = loadSkillsConfig(configPath)
        val target = resolveInstallTarget(config, global, project, pathOpt)
        val skillDir = target.resolve(nameArg)
        val skillFile = skillDir.resolve(SkillLoader.SKILL_FILE_NAME)

        if (skillFile.exists() && !force) {
            println("Refusing to overwrite existing SKILL.md at $skillFile (pass --force to overwrite).")
            exitProcess(1)
        }

        val description = descriptionOpt?.takeIf { it.isNotBlank() }
            ?: "TODO: describe what this skill does and when the agent should reach for it."

        skillDir.createDirectories()
        skillFile.writeText(SkillTemplate.render(nameArg, description))

        // Run the loader against the single file so the user sees the same
        // diagnostics they'd see if they listed the target afterwards.
        val result = SkillLoader().loadFromFile(skillFile, SkillSource.EXPLICIT)
        println("Created $skillFile")
        if (result.diagnostics.isNotEmpty()) {
            printDiagnostics(result.diagnostics)
        }
    }
}

// ---------- skills add ----------

class SkillsAddCommand : CliktCommand(name = "add") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Install skills from a local path, GitHub repo, or git URL"

    private val sourceArg by argument(
        name = "source",
        help = "Local path, owner/repo[@ref][/subpath] shorthand, github.com URL, or git URL.",
    )

    private val configPath by option(
        "-c", "--config",
        help = $$"Path to configuration file (default: $FRAGGLE_ROOT/config/fraggle.yaml)",
    )

    private val global by option("-g", "--global", help = "Install to the global skills directory.").flag()
    private val project by option("-p", "--project", help = "Install to the project skills directory.").flag()
    private val pathOpt by option("--path", help = "Override the target directory.")
    private val symlink by option("--symlink", help = "Symlink instead of copying (local sources only).").flag()
    private val force by option("--force", help = "Overwrite existing skills with the same name.").flag()

    override fun run() {
        val config = loadSkillsConfig(configPath)
        val target = resolveInstallTarget(config, global, project, pathOpt)

        val spec = SkillSourceSpec.parse(sourceArg) ?: run {
            println("Could not parse source: $sourceArg")
            println("Expected a local path, owner/repo shorthand, github.com URL, or git URL.")
            exitProcess(2)
        }

        // Symlinks only make sense for local sources — remote trees land in
        // temp directories that we delete after the install, so a symlink to
        // them would dangle immediately.
        val mode = when {
            symlink && spec !is SkillSourceSpec.Local -> {
                println("Warning: --symlink only applies to local sources; falling back to copy mode.")
                SkillInstaller.InstallMode.COPY
            }
            symlink -> SkillInstaller.InstallMode.SYMLINK
            else -> SkillInstaller.InstallMode.COPY
        }

        runBlocking { runAdd(spec, target, mode) }
    }

    private suspend fun runAdd(
        spec: SkillSourceSpec,
        target: Path,
        mode: SkillInstaller.InstallMode,
    ) {
        val client = buildSkillsHttpClient()
        try {
            val resolver = SkillSourceResolver(httpClient = client)
            val outcome = installFromSpec(spec, target, mode, force = force, resolver = resolver)
            when (outcome) {
                is StagedInstallOutcome.ResolveFailed -> {
                    println("Failed to fetch source: ${outcome.reason}")
                    exitProcess(1)
                }
                is StagedInstallOutcome.Success -> {
                    val result = outcome.result
                    if (result.installed.isNotEmpty()) {
                        println("Installed ${result.installed.size} skill(s) into $target (mode=${mode.name.lowercase()}):")
                        for (i in result.installed) println("  + ${i.name}  →  ${i.destination}")
                        println("  source: ${outcome.sourceLabel}")
                    }
                    if (result.skipped.isNotEmpty()) {
                        println()
                        println("Skipped ${result.skipped.size}:")
                        for (s in result.skipped) println("  - ${s.name}: ${s.reason}")
                    }
                    if (result.diagnostics.isNotEmpty()) {
                        printDiagnostics(result.diagnostics)
                    }
                    if (result.installed.isEmpty()) {
                        val onlyCollisions = result.skipped.isNotEmpty() &&
                            result.skipped.all { "already exists" in it.reason }
                        if (!onlyCollisions) exitProcess(1)
                    }
                }
            }
        } finally {
            client.close()
        }
    }
}

// ---------- skills update ----------

class SkillsUpdateCommand : CliktCommand(name = "update") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Re-fetch and reinstall skills previously added via `skills add`"

    private val nameArgs by argument(
        name = "names",
        help = "Skills to update. Omit to update every tracked skill.",
    ).multiple()

    private val configPath by option(
        "-c", "--config",
        help = $$"Path to configuration file (default: $FRAGGLE_ROOT/config/fraggle.yaml)",
    )

    private val global by option("-g", "--global", help = "Update skills in the global directory.").flag()
    private val project by option("-p", "--project", help = "Update skills in the project directory.").flag()
    private val pathOpt by option("--path", help = "Override the target directory.")

    private val dryRun by option(
        "--dry-run",
        help = "Resolve sources and report what would change without touching disk.",
    ).flag()

    override fun run() {
        val config = loadSkillsConfig(configPath)
        val target = resolveInstallTarget(config, global, project, pathOpt)
        val manifestPath = SkillInstaller.manifestPath(target)
        val manifest = SkillsManifest.read(manifestPath)

        if (manifest.skills.isEmpty()) {
            println("No tracked skills in $target.")
            return
        }

        // Pick the subset of entries to update. An explicit name that isn't in
        // the manifest is a hard error — the user clearly expected something
        // that isn't there.
        val entries: List<SkillsManifest.Entry> = if (nameArgs.isEmpty()) {
            manifest.skills
        } else {
            val byName = manifest.skills.associateBy { it.name }
            val missing = nameArgs.filterNot { it in byName }
            if (missing.isNotEmpty()) {
                println("Not tracked in manifest: ${missing.joinToString(", ")}")
                exitProcess(1)
            }
            nameArgs.mapNotNull { byName[it] }
        }

        println(
            if (dryRun) "Dry-run: would update ${entries.size} skill(s) in $target"
            else "Updating ${entries.size} skill(s) in $target",
        )
        println()

        var hadFailure = false
        runBlocking {
            val client = buildSkillsHttpClient()
            try {
                val resolver = SkillSourceResolver(httpClient = client)
                for (entry in entries) {
                    val outcome = updateOne(entry, target, resolver)
                    when (outcome) {
                        is UpdateOutcome.Updated ->
                            println("↻ ${entry.name}  →  updated from ${entry.source}")
                        is UpdateOutcome.WouldUpdate ->
                            println("? ${entry.name}  →  would update from ${entry.source}")
                        is UpdateOutcome.Unchanged ->
                            println("= ${entry.name}  →  ${outcome.reason}")
                        is UpdateOutcome.Failed -> {
                            println("! ${entry.name}  →  ${outcome.reason}")
                            hadFailure = true
                        }
                    }
                }
            } finally {
                client.close()
            }
        }

        if (hadFailure) exitProcess(1)
    }

    private suspend fun updateOne(
        entry: SkillsManifest.Entry,
        target: Path,
        resolver: SkillSourceResolver,
    ): UpdateOutcome {
        val spec = SkillSourceSpec.parseLabel(entry.source)
            ?: return UpdateOutcome.Failed("unparseable source label: ${entry.source}")

        // A symlinked local install already points at live content — re-copying
        // would defeat the whole "single source of truth" point of symlinks.
        if (entry.mode == "symlink" && spec is SkillSourceSpec.Local) {
            return UpdateOutcome.Unchanged("symlinked local; source is already live")
        }

        // Defensive: remote specs must not be symlinked even if a hand-edited
        // manifest claims otherwise. Fall back to copy mode.
        val mode = when {
            entry.mode == "symlink" && spec is SkillSourceSpec.Local ->
                SkillInstaller.InstallMode.SYMLINK
            else -> SkillInstaller.InstallMode.COPY
        }

        if (dryRun) {
            // Run the resolver only; skip the installer write. Still exercises
            // network / clone so the user sees genuine "would this actually
            // work" results.
            return try {
                val staged = resolver.resolve(spec)
                staged.cleanup()
                UpdateOutcome.WouldUpdate
            } catch (e: Exception) {
                UpdateOutcome.Failed("dry-run resolve failed: ${e.message ?: e::class.simpleName}")
            }
        }

        val outcome = installFromSpec(spec, target, mode, force = true, resolver = resolver)
        return when (outcome) {
            is StagedInstallOutcome.ResolveFailed -> UpdateOutcome.Failed(outcome.reason)
            is StagedInstallOutcome.Success -> {
                val result = outcome.result
                if (result.installed.isNotEmpty()) {
                    UpdateOutcome.Updated
                } else {
                    val reason = result.skipped.firstOrNull()?.reason
                        ?: result.diagnostics.firstOrNull()?.message
                        ?: "install returned no skills"
                    UpdateOutcome.Failed(reason)
                }
            }
        }
    }

    private sealed class UpdateOutcome {
        data object Updated : UpdateOutcome()
        data object WouldUpdate : UpdateOutcome()
        data class Unchanged(val reason: String) : UpdateOutcome()
        data class Failed(val reason: String) : UpdateOutcome()
    }
}

// ---------- install helper shared between add and update ----------

/**
 * Outcome of a single resolve + install attempt. Used by both [SkillsAddCommand]
 * and [SkillsUpdateCommand] so the network / filesystem orchestration lives in
 * exactly one place and failures surface as values instead of thrown exceptions.
 */
internal sealed class StagedInstallOutcome {
    data class Success(
        val sourceLabel: String,
        val result: SkillInstaller.Result,
    ) : StagedInstallOutcome()

    /** The resolver failed to fetch / clone / find the source. */
    data class ResolveFailed(val reason: String) : StagedInstallOutcome()
}

/**
 * Resolve [spec] via [resolver], hand the staged path off to [SkillInstaller],
 * and always clean up the temporary directory the resolver created. Returns a
 * [StagedInstallOutcome] so callers can print per-entry results without any
 * exception-for-control-flow.
 */
internal suspend fun installFromSpec(
    spec: SkillSourceSpec,
    target: Path,
    mode: SkillInstaller.InstallMode,
    force: Boolean,
    resolver: SkillSourceResolver,
): StagedInstallOutcome {
    val staged = try {
        resolver.resolve(spec)
    } catch (e: Exception) {
        return StagedInstallOutcome.ResolveFailed(e.message ?: e::class.simpleName.orEmpty())
    }
    return try {
        val installer = SkillInstaller(target, mode, force)
        val result = installer.install(staged.path, sourceLabel = staged.label)
        StagedInstallOutcome.Success(staged.label, result)
    } finally {
        staged.cleanup()
    }
}

/**
 * Shared HTTP client factory for `add` and `update`. Long-ish timeouts since
 * repo zipballs can be several MB and GitHub redirects through codeload.
 */
internal fun buildSkillsHttpClient(): HttpClient = HttpClient(CIO) {
    install(HttpTimeout) {
        requestTimeoutMillis = 60.seconds.inWholeMilliseconds
        connectTimeoutMillis = 15.seconds.inWholeMilliseconds
        socketTimeoutMillis = 60.seconds.inWholeMilliseconds
    }
}

// ---------- skills remove ----------

class SkillsRemoveCommand : CliktCommand(name = "remove") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Uninstall skills previously installed via `skills add`"

    private val nameArgs by argument(
        name = "names",
        help = "One or more skill names to remove (must be tracked in the target's manifest).",
    ).multiple(required = true)

    private val configPath by option(
        "-c", "--config",
        help = $$"Path to configuration file (default: $FRAGGLE_ROOT/config/fraggle.yaml)",
    )

    private val global by option("-g", "--global", help = "Remove from the global skills directory.").flag()
    private val project by option("-p", "--project", help = "Remove from the project skills directory.").flag()
    private val pathOpt by option("--path", help = "Override the target directory.")

    override fun run() {
        val config = loadSkillsConfig(configPath)
        val target = resolveInstallTarget(config, global, project, pathOpt)
        val installer = SkillInstaller(target)

        var hadFailure = false
        for (name in nameArgs) {
            when (val result = installer.uninstall(name)) {
                is SkillInstaller.UninstallResult.Removed ->
                    println("- $name  →  removed (${result.mode}) from ${result.path}")
                is SkillInstaller.UninstallResult.NotInManifest -> {
                    println("? $name  →  not tracked in manifest (skipping — delete manually if needed)")
                    hadFailure = true
                }
                is SkillInstaller.UninstallResult.Failed -> {
                    println("! $name  →  failed: ${result.reason}")
                    hadFailure = true
                }
            }
        }
        if (hadFailure) exitProcess(1)
    }
}

// ---------- skills find ----------

class SkillsFindCommand : CliktCommand(name = "find") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Search installed skills by name or description substring"

    private val queryArgs by argument(
        name = "query",
        help = "One or more search terms (AND-matched, case-insensitive).",
    ).multiple(required = true)

    private val configPath by option(
        "-c", "--config",
        help = $$"Path to configuration file (default: $FRAGGLE_ROOT/config/fraggle.yaml)",
    )

    private val showHidden by option(
        "--all",
        help = "Include hidden skills (disable-model-invocation: true).",
    ).flag()

    override fun run() {
        val config = loadSkillsConfig(configPath)
        if (!config.enabled) {
            println("Skills are disabled in config (skills.enabled = false).")
            return
        }

        val (rawSkills, _) = loadConfiguredSkills(config, SkillTarget.BOTH)
        val registry = InMemorySkillRegistry(rawSkills)
        val pool = if (showHidden) registry.skills else registry.visibleToModel()
        val terms = queryArgs.map { it.lowercase() }

        val matches = pool.filter { skill ->
            val haystack = (skill.name + " " + skill.description).lowercase()
            terms.all { it in haystack }
        }

        if (matches.isEmpty()) {
            println("No skills matched: ${queryArgs.joinToString(" ")}")
            return
        }

        val maxName = matches.maxOf { it.name.length }
        val maxSource = matches.maxOf { it.source.name.length }
        println("Matched ${matches.size} of ${pool.size} skill(s):")
        println()
        for (skill in matches) {
            val hiddenMarker = if (skill.disableModelInvocation) " [hidden]" else ""
            println(
                "  %-${maxName}s  %-${maxSource}s  %s%s".format(
                    skill.name,
                    skill.source.name.lowercase(),
                    skill.description,
                    hiddenMarker,
                ),
            )
            println("    " + skill.filePath)
        }
    }
}

// ---------- diagnostics pretty-printer ----------

private fun printDiagnostics(diagnostics: List<SkillDiagnostic>) {
    if (diagnostics.isEmpty()) return
    println()
    println("Diagnostics (${diagnostics.size}):")
    for (d in diagnostics) {
        val level = when (d) {
            is SkillDiagnostic.Warning -> "WARN"
            is SkillDiagnostic.Error -> "ERR "
        }
        println("  $level ${d.path}: ${d.message}")
    }
}
