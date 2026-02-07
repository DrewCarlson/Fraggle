package org.drewcarlson.fraggle.tools

import ai.koog.agents.core.tools.ToolRegistry
import io.ktor.client.*
import org.drewcarlson.fraggle.sandbox.Sandbox
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
     */
    fun createToolRegistry(
        sandbox: Sandbox,
        httpClient: HttpClient,
        taskScheduler: TaskScheduler,
        playwrightFetcher: PlaywrightFetcher? = null,
    ): ToolRegistry = ToolRegistry {
        // File tools
        tool(ReadFileTool(sandbox))
        tool(WriteFileTool(sandbox))
        tool(AppendFileTool(sandbox))
        tool(ListFilesTool(sandbox))
        tool(SearchFilesTool(sandbox))
        tool(FileExistsTool(sandbox))
        tool(DeleteFileTool(sandbox))

        // Web tools
        tool(FetchWebpageTool(sandbox, playwrightFetcher))
        tool(FetchApiTool(sandbox, httpClient))
        if (playwrightFetcher != null) {
            tool(ScreenshotPageTool(playwrightFetcher))
        }

        // Shell tools
        tool(ExecuteCommandTool(sandbox))

        // Scheduling tools
        tool(ScheduleTaskTool(taskScheduler))
        tool(ListTasksTool(taskScheduler))
        tool(CancelTaskTool(taskScheduler))
        tool(GetTaskTool(taskScheduler))
    }
}
