package fraggle.tools

import io.ktor.client.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import fraggle.agent.skill.SkillExecutionContext
import fraggle.agent.tool.AgentToolDef
import fraggle.agent.tool.FraggleToolRegistry
import fraggle.executor.ToolExecutor
import fraggle.executor.supervision.ToolArg
import fraggle.executor.supervision.ToolArgKind
import fraggle.executor.supervision.ToolArgTypes
import fraggle.tools.file.*
import fraggle.tools.shell.ExecuteCommandTool
import fraggle.tools.time.GetCurrentTimeTool
import fraggle.tools.web.FetchApiTool
import fraggle.tools.web.FetchWebpageTool
import fraggle.tools.web.PlaywrightFetcher
import fraggle.tools.web.ScreenshotPageTool

/**
 * Factory for building the default [FraggleToolRegistry] with all built-in tools.
 *
 * Supervision and remote forwarding are handled by the loop-level
 * `SupervisedToolCallExecutor` — tools themselves do not wrap anymore.
 */
object DefaultTools {

    /**
     * All built-in tool names paired with their Args serializers.
     * Used by [extractArgTypes] to inspect `@ToolArg` annotations.
     */
    private val toolSerializers: List<Pair<String, KSerializer<*>>> = listOf(
        "read_file" to ReadFileTool.Args.serializer(),
        "write_file" to WriteFileTool.Args.serializer(),
        "append_file" to AppendFileTool.Args.serializer(),
        "list_files" to ListFilesTool.Args.serializer(),
        "search_files" to SearchFilesTool.Args.serializer(),
        "file_exists" to FileExistsTool.Args.serializer(),
        "delete_file" to DeleteFileTool.Args.serializer(),
        "execute_command" to ExecuteCommandTool.Args.serializer(),
    )

    /**
     * Extract `@ToolArg` annotation metadata from all built-in tool Args serializers.
     * Returns a [ToolArgTypes] mapping tool names to their annotated arg kinds.
     */
    fun extractArgTypes(): ToolArgTypes {
        val types = mutableMapOf<String, Map<String, ToolArgKind>>()
        for ((toolName, serializer) in toolSerializers) {
            val argKinds = extractFromDescriptor(serializer.descriptor)
            if (argKinds.isNotEmpty()) {
                types[toolName] = argKinds
            }
        }
        return ToolArgTypes(types)
    }

    private fun extractFromDescriptor(descriptor: SerialDescriptor): Map<String, ToolArgKind> {
        val result = mutableMapOf<String, ToolArgKind>()
        for (i in 0 until descriptor.elementsCount) {
            val annotations = descriptor.getElementAnnotations(i)
            val toolArg = annotations.filterIsInstance<ToolArg>().firstOrNull()
            if (toolArg != null) {
                result[descriptor.getElementName(i)] = toolArg.kind
            }
        }
        return result
    }

    /**
     * Create a [FraggleToolRegistry] with all generic built-in tools
     * (filesystem, shell, web, time). App-specific tools (like the messenger
     * assistant's scheduling tools) are added on top of this base registry
     * by the app's own DI module.
     */
    fun createToolRegistry(
        toolExecutor: ToolExecutor,
        httpClient: HttpClient,
        playwrightFetcher: PlaywrightFetcher? = null,
        skillExecutionContext: SkillExecutionContext? = null,
    ): FraggleToolRegistry {
        val tools = buildList<AgentToolDef<*>> {
            // File tools
            add(ReadFileTool(toolExecutor))
            add(WriteFileTool(toolExecutor))
            add(AppendFileTool(toolExecutor))
            add(ListFilesTool(toolExecutor))
            add(SearchFilesTool(toolExecutor))
            add(FileExistsTool(toolExecutor))
            add(DeleteFileTool(toolExecutor))

            // Web tools
            add(FetchWebpageTool(httpClient, playwrightFetcher))
            add(FetchApiTool(httpClient))
            if (playwrightFetcher != null) {
                add(ScreenshotPageTool(playwrightFetcher))
            }

            // Shell
            add(ExecuteCommandTool(toolExecutor, skillExecutionContext))

            // Time
            add(GetCurrentTimeTool())
        }
        return FraggleToolRegistry(tools)
    }
}
