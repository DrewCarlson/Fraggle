package org.drewcarlson.fraggle.tools

import ai.koog.agents.core.tools.ToolRegistry
import io.ktor.client.*
import org.drewcarlson.fraggle.executor.RemoteToolClient
import org.drewcarlson.fraggle.executor.ToolExecutor
import org.drewcarlson.fraggle.executor.managed
import org.drewcarlson.fraggle.executor.supervision.ToolSupervisor
import org.drewcarlson.fraggle.tools.file.*
import org.drewcarlson.fraggle.tools.scheduling.*
import org.drewcarlson.fraggle.tools.shell.ExecuteCommandTool
import org.drewcarlson.fraggle.tools.web.FetchApiTool
import org.drewcarlson.fraggle.tools.web.FetchWebpageTool
import org.drewcarlson.fraggle.tools.web.PlaywrightFetcher
import org.drewcarlson.fraggle.tools.web.ScreenshotPageTool

/**
 * Factory for creating the default Koog ToolRegistry with all built-in tools.
 */
object DefaultTools {

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

        // Scheduling tools (NOT managed — no supervision needed)
        tool(ScheduleTaskTool(taskScheduler))
        tool(ListTasksTool(taskScheduler))
        tool(CancelTaskTool(taskScheduler))
        tool(GetTaskTool(taskScheduler))
    }
}
