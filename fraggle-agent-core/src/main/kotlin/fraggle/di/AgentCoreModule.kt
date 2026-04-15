package fraggle.di

import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.ktor.client.*
import fraggle.FraggleEnvironment
import fraggle.agent.skill.EmptySkillRegistry
import fraggle.agent.skill.InMemorySkillRegistry
import fraggle.agent.skill.Skill
import fraggle.agent.skill.SkillDiagnostic
import fraggle.agent.skill.SkillLoader
import fraggle.agent.skill.SkillRegistry
import fraggle.agent.skill.SkillSource
import fraggle.executor.LocalToolExecutor
import fraggle.executor.RemoteToolClient
import fraggle.executor.ToolExecutor
import fraggle.executor.supervision.InteractiveToolSupervisor
import fraggle.executor.supervision.NoOpToolSupervisor
import fraggle.executor.supervision.ToolArgTypes
import fraggle.executor.supervision.ToolPermissionHandler
import fraggle.executor.supervision.ToolPolicyEvaluator
import fraggle.executor.supervision.ToolSupervisor
import fraggle.models.ExecutorConfig
import fraggle.models.ExecutorType
import fraggle.models.ProviderConfig
import fraggle.models.SkillsConfig
import fraggle.models.SupervisionMode
import fraggle.models.TracingConfig
import fraggle.models.TracingLevel
import fraggle.provider.LMStudioProvider
import fraggle.tracing.TraceStore
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile

/**
 * Provides generic agent runtime services: LLM provider, tool executor, supervision,
 * remote tool client, trace store. All bindings here are reusable by any agent
 * application (messenger assistant, coding agent, etc.).
 */
@ContributesTo(AppScope::class)
interface AgentCoreModule {

    @Provides
    @SingleIn(AppScope::class)
    fun provideLMStudioProvider(
        config: ProviderConfig,
        @LlmHttpClient httpClient: HttpClient,
    ): LMStudioProvider = LMStudioProvider(
        baseUrl = config.url,
        httpClient = httpClient,
        apiKey = config.apiKey,
    )

    @Provides
    @SingleIn(AppScope::class)
    fun provideToolExecutor(config: ExecutorConfig): ToolExecutor {
        val workDir = FraggleEnvironment.resolvePath(config.workDir)
        workDir.createDirectories()
        return LocalToolExecutor(workDir)
    }

    @Provides
    @SingleIn(AppScope::class)
    fun provideToolSupervisor(
        config: ExecutorConfig,
        handler: ToolPermissionHandler?,
        argTypes: ToolArgTypes,
    ): ToolSupervisor {
        return when (config.supervision) {
            SupervisionMode.NONE -> NoOpToolSupervisor()
            SupervisionMode.SUPERVISED -> {
                val permHandler = handler ?: return NoOpToolSupervisor()
                val evaluator = ToolPolicyEvaluator(config.toolPolicies, argTypes)
                InteractiveToolSupervisor(evaluator, permHandler)
            }
        }
    }

    @Provides
    @SingleIn(AppScope::class)
    fun provideRemoteToolClient(
        config: ExecutorConfig,
        @DefaultHttpClient httpClient: HttpClient,
    ): RemoteToolClient? {
        if (config.type != ExecutorType.REMOTE || config.remoteUrl.isBlank()) return null
        return RemoteToolClient(httpClient, config.remoteUrl)
    }

    @Provides
    @SingleIn(AppScope::class)
    fun provideTraceStore(config: TracingConfig): TraceStore? {
        if (config.level == TracingLevel.OFF) return null
        return TraceStore()
    }

    @Provides
    @SingleIn(AppScope::class)
    fun provideSkillRegistry(config: SkillsConfig): SkillRegistry {
        if (!config.enabled) return EmptySkillRegistry

        val loader = SkillLoader()
        val entries = mutableListOf<Skill>()
        val diagnostics = mutableListOf<SkillDiagnostic>()

        val globalDir = config.globalDir
            ?.takeIf { it.isNotBlank() }
            ?.let { FraggleEnvironment.resolvePath(it) }
            ?: FraggleEnvironment.skillsDir
        val globalResult = loader.loadFromDirectory(globalDir, SkillSource.GLOBAL)
        entries += globalResult.skills
        diagnostics += globalResult.diagnostics

        val projectDir = config.projectDir
            ?.takeIf { it.isNotBlank() }
            ?.let { FraggleEnvironment.resolveProjectPath(it) }
            ?: FraggleEnvironment.projectSkillsDir
        val projectResult = loader.loadFromDirectory(projectDir, SkillSource.PROJECT)
        entries += projectResult.skills
        diagnostics += projectResult.diagnostics

        for (extra in config.extraPaths) {
            val path: Path = FraggleEnvironment.resolvePath(extra)
            val result = if (path.isRegularFile()) {
                loader.loadFromFile(path, SkillSource.EXPLICIT)
            } else {
                loader.loadFromDirectory(path, SkillSource.EXPLICIT)
            }
            entries += result.skills
            diagnostics += result.diagnostics
        }

        val registry = InMemorySkillRegistry(entries, diagnostics)
        val log = LoggerFactory.getLogger("fraggle.skills")
        if (registry.skills.isNotEmpty()) {
            log.info("Loaded {} skill(s): {}", registry.skills.size, registry.skills.joinToString { it.name })
        }
        for (d in registry.diagnostics) {
            when (d) {
                is SkillDiagnostic.Warning -> log.warn("skill {}: {}", d.path, d.message)
                is SkillDiagnostic.Error -> log.error("skill {}: {}", d.path, d.message)
            }
        }
        return registry
    }
}
