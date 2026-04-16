package fraggle

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.split
import fraggle.agent.loop.ProviderLlmBridge
import fraggle.agent.loop.ToolCallExecutor
import fraggle.agent.message.AgentMessage
import fraggle.agent.skill.DefaultSkillExecutionContext
import fraggle.agent.skill.SkillCommandExpander
import fraggle.agent.skill.SkillPromptFormatter
import fraggle.agent.skill.SkillRegistryLoader
import fraggle.agent.skill.SkillSecretsStore
import fraggle.agent.skill.SkillVenvManager
import fraggle.agent.tool.SupervisedToolCallExecutor
import fraggle.coding.CodingAgent
import fraggle.coding.CodingAgentOptions
import fraggle.coding.CodingAgentOptions.SupervisionMode
import fraggle.coding.context.AgentsFileLoader
import fraggle.coding.context.SystemPromptBuilder
import fraggle.coding.context.WorkspaceSnapshot
import fraggle.coding.prompt.DefaultSystemPrompt
import fraggle.coding.prompt.PromptTemplateLoader
import fraggle.coding.session.Session
import fraggle.coding.session.SessionManager
import fraggle.coding.session.replayCurrentBranch
import fraggle.coding.settings.CodingSettingsDefaults
import fraggle.coding.settings.SettingsStore
import fraggle.coding.tools.CodingToolRegistry
import fraggle.coding.tui.HeaderInfo
import fraggle.coding.tui.TuiToolPermissionHandler
import fraggle.coding.tui.runCodingApp
import fraggle.executor.LocalToolExecutor
import fraggle.executor.supervision.InteractiveToolSupervisor
import fraggle.executor.supervision.NoOpToolSupervisor
import fraggle.executor.supervision.ToolPolicyEvaluator
import fraggle.executor.supervision.ToolSupervisor
import fraggle.provider.LMStudioProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * `fraggle code` — terminal coding agent.
 *
 * Ties together every phase of the coding-agent module:
 *  - Config loading from the shared `$FRAGGLE_ROOT/config/fraggle.yaml`
 *  - Coding-specific settings from `$FRAGGLE_ROOT/coding/settings.json`
 *    (+ project override `<cwd>/.fraggle/coding/settings.json`)
 *  - Session resolution (new / continue / resume-latest / --session / --fork / --no-session)
 *  - Context files via [AgentsFileLoader] (cwd → git root + global)
 *  - System prompt via [SystemPromptBuilder] (default + workspace snapshot + AGENTS.md + templates + append)
 *  - Tool registry via [CodingToolRegistry] (filesystem/shell/web base + edit_file)
 *  - Supervision: [SupervisionMode.NONE] → [NoOpToolSupervisor]; [SupervisionMode.ASK] → [InteractiveToolSupervisor] + [TuiToolPermissionHandler]
 *  - LM Studio via [LMStudioProvider] + [ProviderLlmBridge]
 *  - Runs the TUI via [runCodingApp]
 *
 * Flag precedence (last wins): defaults → global settings → project settings → command-line flag.
 */
class CodeCommand : CliktCommand(name = "code") {

    // ── Flags ───────────────────────────────────────────────────────────────

    private val modelOverride by option(
        "--model",
        help = "Model id to use (overrides provider config and settings.json)",
    )

    private val workDirOverride by option(
        "--workdir",
        help = "Working directory for the coding agent (defaults to the current directory)",
    )

    private val continueSession by option(
        "-c", "--continue",
        help = "Continue the most recent session for this project",
    ).flag(default = false)

    private val resumeSession by option(
        "-r", "--resume",
        help = "Resume the most recent session for this project (alias of --continue in MVP; picker UI is future work)",
    ).flag(default = false)

    private val sessionPath by option(
        "--session",
        help = "Open a specific session file by path",
    )

    private val forkPath by option(
        "--fork",
        help = "Fork a specific session file by path into a new session",
    )

    private val noSession by option(
        "--no-session",
        help = "Ephemeral mode — don't persist this session to disk",
    ).flag(default = false)

    private val enabledTools by option(
        "--tools",
        help = "Comma-separated list of tools to enable (default: all)",
    ).split(",")

    private val noTools by option(
        "--no-tools",
        help = "Disable all built-in tools",
    ).flag(default = false)

    private val systemPromptOverride by option(
        "--system-prompt",
        help = "Replace the default system prompt entirely",
    )

    private val appendSystemPrompt by option(
        "--append-system-prompt",
        help = "Append text to the default system prompt",
    )

    private val supervisionOverride by option(
        "--supervision",
        help = "Supervision mode: 'ask' (prompt for every tool call) or 'none' (auto-approve all)",
    )

    /**
     * Positional arguments. Each may be:
     *  - `@path.md` → read the file and include its contents in the first user message
     *  - any other word → part of the initial user message text
     *
     * `@file` references match pi's convention so a user can drop files into
     * the initial prompt without an editor round-trip.
     */
    private val messageArgs by argument(
        name = "message",
        help = "Initial prompt text. Prefix with @ to include a file's contents.",
    ).multiple()

    // ── Entry point ─────────────────────────────────────────────────────────

    override fun run() {
        val cwd: Path = workDirOverride?.let { Path(it).toAbsolutePath() }
            ?: Path(".").toAbsolutePath().normalize()

        // 1. Shared provider config (from $FRAGGLE_ROOT/config/fraggle.yaml)
        val fraggleConfig = ConfigLoader.loadOrCreateDefault(FraggleEnvironment.defaultConfigPath)
        val providerConfig = fraggleConfig.fraggle.provider

        // 2. Coding-agent settings (global + project, merged with defaults)
        val projectSettingsFile = cwd.resolve(".fraggle").resolve("coding").resolve("settings.json")
        val merged = SettingsStore.load(
            globalFile = FraggleEnvironment.codingSettingsFile,
            projectFile = projectSettingsFile,
        )
        val settings = merged.effective

        // 3. Effective values after applying CLI overrides
        val effectiveModel = (modelOverride ?: settings.model ?: providerConfig.model)
            .takeIf { it.isNotBlank() }
            ?: error("No model configured. Set provider.model in fraggle.yaml, coding/settings.json, or pass --model.")

        val effectiveSupervision = parseSupervision(
            supervisionOverride ?: settings.supervision ?: CodingSettingsDefaults.supervision,
        )

        val contextWindowTokens = settings.contextWindowTokens ?: CodingSettingsDefaults.contextWindowTokens

        // 4. Resolve the session on disk
        val sessionManager = SessionManager(
            sessionsRoot = FraggleEnvironment.codingSessionsDir,
            projectRoot = cwd,
        )
        val session = resolveSession(sessionManager, effectiveModel)
        val initialMessages: List<AgentMessage> = session.tree.replayCurrentBranch()

        // 5. Context files (AGENTS.md walk + global)
        val contextLoader = AgentsFileLoader(
            cwd = cwd,
            globalAgentsFile = FraggleEnvironment.codingGlobalAgentsFile
                .takeIf { it.exists() && it.isRegularFile() },
        )
        val contextFiles = contextLoader.load()

        // 6. Workspace snapshot (git info, best effort)
        val workspace = WorkspaceSnapshot.capture(cwd)

        // 7. Prompt templates
        val templateLoader = PromptTemplateLoader()
        val templates = buildList {
            // Global templates first, then project
            addAll(templateLoader.loadFromDirectory(FraggleEnvironment.codingPromptsDir))
            addAll(templateLoader.loadFromDirectory(cwd.resolve(".fraggle").resolve("coding").resolve("prompts")))
        }

        // 8. Skills — load registry, build execution context, render catalog
        val skillsConfig = fraggleConfig.fraggle.skills
        val skillRegistryLoader = SkillRegistryLoader()
        val skillRegistry = skillRegistryLoader.load(skillsConfig)
        val skillSecretsStore = SkillSecretsStore(FraggleEnvironment.secretsDir)
        val skillVenvManager = SkillVenvManager(FraggleEnvironment.venvsDir)
        val skillExecutionContext = DefaultSkillExecutionContext(
            registry = skillRegistry,
            secretsStore = skillSecretsStore,
            venvManager = skillVenvManager,
        )
        val skillCatalog = SkillPromptFormatter.format(
            skills = skillRegistry.skills,
            envChecker = { skillName, varName -> skillSecretsStore.isConfigured(skillName, varName) },
        ).ifBlank { null }
        val skillExpander = SkillCommandExpander { skillRegistryLoader.load(skillsConfig) }

        // 9. Compose the system prompt
        val basePrompt = systemPromptOverride
            ?: readFileOrNull(projectFile = cwd.resolve(".fraggle").resolve("coding").resolve("SYSTEM.md"))
            ?: readFileOrNull(projectFile = FraggleEnvironment.codingDir.resolve("SYSTEM.md"))
            ?: DefaultSystemPrompt.load()
        val appendFromFile = readFileOrNull(projectFile = cwd.resolve(".fraggle").resolve("coding").resolve("APPEND_SYSTEM.md"))
            ?: readFileOrNull(projectFile = FraggleEnvironment.codingDir.resolve("APPEND_SYSTEM.md"))
        val finalAppend = listOfNotNull(appendFromFile, appendSystemPrompt)
            .joinToString("\n\n")
            .ifBlank { null }

        val systemPrompt = SystemPromptBuilder.build(
            basePrompt = basePrompt,
            workspace = workspace,
            contextFiles = contextFiles,
            skillCatalog = skillCatalog,
            availableTemplates = templates.map { t ->
                fraggle.coding.context.TemplateDescriptor(
                    name = t.name,
                    description = null,
                )
            },
            appendText = finalAppend,
        )

        // 10. HTTP client, provider, bridge
        val httpClient = buildLlmHttpClient()
        val provider = LMStudioProvider(
            baseUrl = providerConfig.url,
            httpClient = httpClient,
            apiKey = providerConfig.apiKey,
        )
        val llmBridge = ProviderLlmBridge(
            provider = provider,
            model = effectiveModel,
        )

        // 11. Tool executor + registry + supervisor + permission handler
        val toolExecutor = LocalToolExecutor(cwd)
        val toolEnabled: Set<String>? = when {
            noTools -> emptySet()
            enabledTools != null -> enabledTools!!.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            else -> null
        }
        val built = CodingToolRegistry.build(
            toolExecutor = toolExecutor,
            httpClient = httpClient,
            playwrightFetcher = null,
            skillExecutionContext = skillExecutionContext,
            enabledTools = toolEnabled,
        )

        val permissionHandler: TuiToolPermissionHandler? = when (effectiveSupervision) {
            SupervisionMode.NONE -> null
            SupervisionMode.ASK -> TuiToolPermissionHandler()
        }
        val supervisor: ToolSupervisor = when (effectiveSupervision) {
            SupervisionMode.NONE -> NoOpToolSupervisor()
            SupervisionMode.ASK -> InteractiveToolSupervisor(
                // Empty policy list → every call falls through to the handler
                evaluator = ToolPolicyEvaluator(rules = emptyList(), argTypes = built.argTypes),
                handler = permissionHandler!!,
            )
        }
        val toolCallExecutor: ToolCallExecutor = SupervisedToolCallExecutor(
            registry = built.registry,
            supervisor = supervisor,
        )

        // 12. Initial-message seed from @file positionals or raw text
        val seedFromArgs = buildInitialUserMessage(messageArgs, cwd)
        val seededInitialMessages: List<AgentMessage> = if (seedFromArgs != null) {
            initialMessages + AgentMessage.User(seedFromArgs)
        } else {
            initialMessages
        }

        // 13. Orchestrator
        val options = CodingAgentOptions(
            model = effectiveModel,
            workDir = cwd,
            llmBridge = llmBridge,
            toolCallExecutor = toolCallExecutor,
            systemPrompt = systemPrompt,
            initialMessages = seededInitialMessages,
            maxIterations = settings.maxIterations ?: CodingSettingsDefaults.maxIterations,
            supervisionMode = effectiveSupervision,
            contextWindowTokens = contextWindowTokens,
        )
        val agent = CodingAgent(options, session)

        val header = HeaderInfo(
            model = effectiveModel,
            contextFileCount = contextFiles.size,
        )
        val supervisionLabel = effectiveSupervision.name.lowercase()

        // 14. Run the TUI. runCodingApp blocks until the composition exits.
        Runtime.getRuntime().addShutdownHook(
            Thread {
                runCatching { httpClient.close() }
            },
        )
        try {
            runCodingApp(
                agent = agent,
                options = options,
                header = header,
                supervisionLabel = supervisionLabel,
                skillExpander = skillExpander,
                onExitRequest = {
                    // Mosaic has no composition-level exit; shutdown hook above handles cleanup.
                    exitProcess(0)
                },
                permissionHandler = permissionHandler,
            )
        } catch (_: CancellationException) {
            // Normal exit path.
        }

        httpClient.close()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun parseSupervision(raw: String): SupervisionMode = when (raw.lowercase()) {
        "ask" -> SupervisionMode.ASK
        "none", "off", "yolo" -> SupervisionMode.NONE
        else -> {
            System.err.println("warning: unknown supervision mode '$raw' — using 'ask'")
            SupervisionMode.ASK
        }
    }

    /**
     * Resolve which [Session] to open based on the mutually-exclusive flags.
     * Validates combinations and produces a helpful error when the user asks
     * for contradictory things (e.g., `--continue` + `--session`).
     */
    private fun resolveSession(manager: SessionManager, model: String): Session {
        val flagsSet = listOf(
            continueSession,
            resumeSession,
            sessionPath != null,
            forkPath != null,
            noSession,
        ).count { it }
        if (flagsSet > 1) {
            error("Choose at most one of: --continue, --resume, --session, --fork, --no-session")
        }

        // --no-session: start a fresh session without resuming previous state.
        if (noSession) {
            return manager.createNew(model = model)
        }

        sessionPath?.let { raw ->
            val p = Path(raw).toAbsolutePath()
            if (!p.exists()) error("Session file not found: $p")
            return manager.open(p)
        }

        forkPath?.let { raw ->
            val p = Path(raw).toAbsolutePath()
            if (!p.exists()) error("Session file not found: $p")
            val source = manager.open(p)
            val tip = source.tree.currentBranch().lastOrNull()
                ?: error("Source session is empty, nothing to fork")
            return manager.fork(source, tip)
        }

        if (continueSession || resumeSession) {
            val latest = manager.mostRecent()
                ?: run {
                    System.err.println("No previous session found for this project — starting a new one.")
                    return manager.createNew(model = model)
                }
            return manager.open(latest.file)
        }

        return manager.createNew(model = model)
    }

    /**
     * Combine positional [args] into a single user message string. Arguments
     * that start with `@` are interpreted as file paths to read and inline.
     * Returns `null` when no positional args were given (the caller then
     * leaves the initial-message seed empty and the user types in the TUI).
     */
    private fun buildInitialUserMessage(args: List<String>, cwd: Path): String? {
        if (args.isEmpty()) return null
        val sb = StringBuilder()
        for (arg in args) {
            if (arg.startsWith("@")) {
                val rel = arg.substring(1)
                val path = Path(rel).let { if (it.isAbsolute) it else cwd.resolve(it) }
                if (path.exists() && path.isRegularFile()) {
                    sb.appendLine("`$rel`:")
                    sb.appendLine("```")
                    sb.appendLine(path.readText())
                    sb.appendLine("```")
                } else {
                    System.err.println("warning: @file not found or not a regular file: $path")
                    sb.append(arg).append(' ')
                }
            } else {
                sb.append(arg).append(' ')
            }
        }
        return sb.toString().trim().ifEmpty { null }
    }

    private fun readFileOrNull(projectFile: Path): String? =
        if (projectFile.exists() && projectFile.isRegularFile()) projectFile.readText() else null

    private fun buildLlmHttpClient(): HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                    isLenient = true
                    explicitNulls = false
                },
            )
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 5.minutes.inWholeMilliseconds
            connectTimeoutMillis = 10.seconds.inWholeMilliseconds
        }
        defaultRequest {
            header(HttpHeaders.UserAgent, "Fraggle/1.0 (coding-agent)")
            contentType(ContentType.Application.Json)
        }
    }
}
