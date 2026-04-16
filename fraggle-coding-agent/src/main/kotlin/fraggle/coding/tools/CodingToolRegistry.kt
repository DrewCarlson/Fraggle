package fraggle.coding.tools

import fraggle.agent.skill.SkillExecutionContext
import fraggle.agent.tool.AgentToolDef
import fraggle.agent.tool.FraggleToolRegistry
import fraggle.executor.ToolExecutor
import fraggle.executor.supervision.ToolArgKind
import fraggle.executor.supervision.ToolArgTypes
import fraggle.tools.DefaultTools
import fraggle.tools.web.PlaywrightFetcher
import io.ktor.client.HttpClient

/**
 * Builds the [FraggleToolRegistry] for the coding agent.
 *
 * The registry is a composition:
 * 1. Start with the generic base tools from [DefaultTools.createToolRegistry]
 *    (`read_file`, `write_file`, `append_file`, `list_files`, `search_files`,
 *    `file_exists`, `delete_file`, `fetch_webpage`, `fetch_api`, `execute_command`,
 *    `get_current_time`). Scheduling tools are intentionally not included —
 *    they live in `fraggle-assistant` where they belong, next to the chat bridges.
 * 2. Append the coding-specific [EditFileTool] for targeted edits via exact-string
 *    replace.
 *
 * Optional [enabledTools]: when non-null, only tools whose names are in the
 * set survive. `null` means "all".
 *
 * Optional [playwrightFetcher] is passed through to the base registry for
 * `fetch_webpage` / `screenshot_page`; pass null to disable browser-automation
 * tools (the default for a terminal coding agent).
 *
 * [ToolArgTypes] is also produced here, extended with the edit_file tool's
 * path-typed `path` argument so the supervisor can apply path-based rules to
 * it if anyone ever wires up policy-based supervision for the coding agent.
 */
object CodingToolRegistry {

    /**
     * Build the final registry. Returns both the [FraggleToolRegistry] (for
     * the agent loop to look up tools by name) and the [ToolArgTypes] (for
     * the supervisor to know which arg fields are paths/commands).
     */
    fun build(
        toolExecutor: ToolExecutor,
        httpClient: HttpClient,
        playwrightFetcher: PlaywrightFetcher? = null,
        skillExecutionContext: SkillExecutionContext? = null,
        enabledTools: Set<String>? = null,
    ): Built {
        val base = DefaultTools.createToolRegistry(
            toolExecutor = toolExecutor,
            httpClient = httpClient,
            playwrightFetcher = playwrightFetcher,
            skillExecutionContext = skillExecutionContext,
        )

        val codingTools: List<AgentToolDef<*>> = listOf(
            EditFileTool(toolExecutor),
        )

        val combined: List<AgentToolDef<*>> = base.tools + codingTools
        val filtered = if (enabledTools == null) combined else combined.filter { it.name in enabledTools }

        val argTypes = buildArgTypes(base.tools + codingTools)

        return Built(
            registry = FraggleToolRegistry(filtered),
            argTypes = argTypes,
        )
    }

    /**
     * Compose the [ToolArgTypes] for the coding agent. Starts from the
     * base-tool arg-type map produced by [DefaultTools.extractArgTypes], then
     * appends an entry for `edit_file` whose `path` argument is a [PATH].
     *
     * We hand-wire `edit_file` here rather than registering it in
     * [DefaultTools.toolSerializers] because the coding agent owns the tool
     * and `fraggle-tools` shouldn't know about coding-specific tools.
     */
    private fun buildArgTypes(allTools: List<AgentToolDef<*>>): ToolArgTypes {
        val base = DefaultTools.extractArgTypes().types.toMutableMap()
        base["edit_file"] = mapOf("path" to ToolArgKind.PATH)
        return ToolArgTypes(base)
    }

    /**
     * Result of [build]: the registry the agent loop uses, plus the arg-type
     * map the supervisor uses.
     */
    data class Built(
        val registry: FraggleToolRegistry,
        val argTypes: ToolArgTypes,
    )
}
