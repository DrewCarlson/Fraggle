package fraggle.tools

import ai.koog.agents.core.tools.ToolRegistry
import io.ktor.client.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import fraggle.executor.RemoteToolClient
import fraggle.executor.ToolExecutor
import fraggle.executor.managed
import fraggle.executor.supervision.ToolArg
import fraggle.executor.supervision.ToolArgKind
import fraggle.executor.supervision.ToolArgTypes
import fraggle.executor.supervision.ToolSupervisor
import fraggle.tools.file.*
import fraggle.tools.scheduling.*
import fraggle.tools.shell.ExecuteCommandTool
import fraggle.tools.time.GetCurrentTimeTool
import fraggle.tools.web.FetchApiTool
import fraggle.tools.web.FetchWebpageTool
import fraggle.tools.web.PlaywrightFetcher
import fraggle.tools.web.ScreenshotPageTool

/**
 * Factory for creating the default Koog ToolRegistry with all built-in tools.
 */
object DefaultTools {

    /**
     * All managed tool names paired with their Args serializers.
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
     * Create a tool registry with all built-in tools.
     * File, shell, and web tools are wrapped with [ManagedTool] for supervision
     * and optional remote forwarding. Scheduling tools are NOT wrapped.
     */
    fun createToolRegistry(
        toolExecutor: ToolExecutor,
        httpClient: HttpClient,
        taskScheduler: TaskScheduler,
        supervisor: ToolSupervisor,
        remoteClient: RemoteToolClient? = null,
        playwrightFetcher: PlaywrightFetcher? = null,
    ): ToolRegistry = ToolRegistry {
        // File tools (managed)
        tool(ReadFileTool(toolExecutor).managed(supervisor, remoteClient))
        tool(WriteFileTool(toolExecutor).managed(supervisor, remoteClient))
        tool(AppendFileTool(toolExecutor).managed(supervisor, remoteClient))
        tool(ListFilesTool(toolExecutor).managed(supervisor, remoteClient))
        tool(SearchFilesTool(toolExecutor).managed(supervisor, remoteClient))
        tool(FileExistsTool(toolExecutor).managed(supervisor, remoteClient))
        tool(DeleteFileTool(toolExecutor).managed(supervisor, remoteClient))

        // Web tools (managed)
        tool(FetchWebpageTool(httpClient, playwrightFetcher).managed(supervisor, remoteClient))
        tool(FetchApiTool(httpClient).managed(supervisor, remoteClient))
        if (playwrightFetcher != null) {
            tool(ScreenshotPageTool(playwrightFetcher).managed(supervisor, remoteClient))
        }

        // Shell tools (managed)
        tool(ExecuteCommandTool(toolExecutor).managed(supervisor, remoteClient))

        // Time tools (NOT managed — no supervision needed)
        tool(GetCurrentTimeTool())

        // Scheduling tools (NOT managed — no supervision needed)
        tool(ScheduleTaskTool(taskScheduler))
        tool(ListTasksTool(taskScheduler))
        tool(CancelTaskTool(taskScheduler))
        tool(GetTaskTool(taskScheduler))
    }
}
